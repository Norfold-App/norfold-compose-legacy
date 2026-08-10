package com.norfold.app.collaboration

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class CollaborationHealth {
    OFFLINE, CONNECTING, CONNECTED, RECONNECTING, AUTHORIZATION_DENIED,
    PROTOCOL_INCOMPATIBLE, OPERATION_REJECTED, OUTBOX_BLOCKED, TRANSPORT_UNAVAILABLE,
    MESSAGE_AUTHENTICITY_FAILED, ASSET_MISSING, MERGE_CONFLICT,
}

class OutboxBlockedException : IllegalStateException("Pending collaboration storage is full; local content remains unsent")
class OperationIdCollisionException : IllegalStateException("A different pending operation already uses this id")

/** One artifact session. Durable persistence precedes realtime notification and only server ACK removes outbox data. */
class CollaborationSession(
    val transport: RealtimeCollaborationTransport,
    val adaptiveSync: AdaptiveSyncController = AdaptiveSyncController(),
    private val crypto: CollabEnvelopeCrypto = ManagedCollabEnvelopeCrypto,
    private val cryptoContextProvider: CollabCryptoContextProvider? = null,
    private val capabilities: CollabCapabilities = CollabCapabilities.BETA,
    private val outbox: OfflineOutbox = OfflineOutbox(),
    private val durableGateway: DurableOperationGateway = OfflineDurableOperationGateway,
    private val deviceId: DeviceId = DeviceId("unknown-device"),
    private val artifactDeliverySupported: Boolean = true,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val coalescer = PreviewCoalescer()
    private val seqCounter = AtomicLong(System.currentTimeMillis())
    private var tickerJob: Job? = null
    private var retryJob: Job? = null
    private var room: RoomId? = null
    private val durableInFlight = ConcurrentHashMap.newKeySet<String>()
    private val _health = MutableStateFlow(CollaborationHealth.OFFLINE)

    val state: StateFlow<TransportState> get() = transport.state
    val health: StateFlow<CollaborationHealth> get() = _health
    val lifecycle: Flow<TransportLifecycleEvent> get() = transport.lifecycle
    val inbound: Flow<InboundEnvelope> = transport.inbound.mapNotNull { decryptInbound(it) }

    suspend fun connect(roomId: RoomId, actorId: ActorId, tokenProvider: CollabTokenProvider) {
        require(roomId.isBenchmark || roomId.artifactScope != null) { "Invalid artifact room" }
        require(roomId.isBenchmark || outbox.isProcessDurable) {
            "Artifact collaboration requires a process-durable outbox"
        }
        require(roomId.isBenchmark || artifactDeliverySupported) {
            "This provider is benchmark-only until durable artifact delivery is certified"
        }
        require(room == null || room == roomId) { "Create a new collaboration session when changing artifacts" }
        room = roomId
        _health.value = CollaborationHealth.CONNECTING
        transport.connect(roomId, actorId, tokenProvider)
        startCoalescerTicker()
        startRetryLoop()
        scope.launch { replayWhenConnected() }
    }

    suspend fun send(
        roomId: RoomId,
        actorId: ActorId,
        eventClass: CollabEventClass,
        eventType: String,
        targetId: String? = null,
        payload: kotlinx.serialization.json.JsonElement? = null,
        operationId: OperationId? = null,
        batchId: String? = null,
        semanticVersion: Int = 1,
        artifactSchemaVersion: Int = 1,
        keyEpoch: Long = 0,
    ) {
        if (!capabilities.enabled) return
        require(room == null || room == roomId) { "A collaboration session cannot change artifact rooms" }
        if (eventClass == CollabEventClass.EPHEMERAL_LATEST && !adaptiveSync.profile.value.sendPreviews) return
        val seq = CollabSeq(seqCounter.incrementAndGet())
        var envelope = OutboundEnvelope(
            roomId = roomId, actorId = actorId, deviceId = deviceId,
            operationId = operationId ?: OperationId(UUID.randomUUID().toString()),
            batchId = batchId, eventClass = eventClass, eventType = eventType,
            targetId = targetId, seq = seq, payload = payload,
            semanticVersion = semanticVersion, artifactSchemaVersion = artifactSchemaVersion,
            keyEpoch = keyEpoch,
        )
        envelope = encryptOutbound(envelope)
        coalescer.offer(envelope, System.currentTimeMillis()).forEach { dispatchOrQueue(it) }
    }

    private suspend fun dispatchOrQueue(envelope: OutboundEnvelope) {
        if (envelope.eventClass != CollabEventClass.DURABLE) {
            if (transport.state.value == TransportState.CONNECTED) transport.send(envelope)
            return
        }
        when (outbox.enqueue(envelope)) {
            is OutboxEnqueueResult.Blocked -> {
                _health.value = CollaborationHealth.OUTBOX_BLOCKED
                throw OutboxBlockedException()
            }
            OutboxEnqueueResult.OperationIdCollision -> {
                _health.value = CollaborationHealth.OPERATION_REJECTED
                throw OperationIdCollisionException()
            }
            OutboxEnqueueResult.AlreadyPending, OutboxEnqueueResult.Enqueued -> Unit
        }
        if (transport.state.value == TransportState.CONNECTED) persistAndNotify(envelope)
    }

    private suspend fun persistAndNotify(envelope: OutboundEnvelope) {
        if (!durableInFlight.add(envelope.operationId.value)) return
        try {
            when (val accepted = durableGateway.accept(envelope)) {
                is DurableAcceptance.Accepted -> {
                    if (accepted.operationId != envelope.operationId || accepted.serverRevision <= 0 ||
                        !accepted.revisionHash.matches(Regex("[0-9a-f]{64}"))
                    ) {
                        outbox.recordFailure(envelope.operationId, OutboxFailureKind.TRANSIENT, "invalid_durable_ack")
                        _health.value = CollaborationHealth.TRANSPORT_UNAVAILABLE
                        return
                    }
                    outbox.acknowledge(listOf(accepted.operationId))
                    runCatching {
                        transport.send(envelope.copy(
                            serverRevision = accepted.serverRevision,
                            revisionHash = accepted.revisionHash,
                        ))
                    }
                    _health.value = CollaborationHealth.CONNECTED
                }
                is DurableAcceptance.AuthExpired -> {
                    val retryAt = System.currentTimeMillis() + retryDelay(outbox.attempts(envelope.operationId))
                    outbox.recordFailure(envelope.operationId, OutboxFailureKind.AUTH_EXPIRED, accepted.code, retryAt)
                    _health.value = CollaborationHealth.AUTHORIZATION_DENIED
                }
                is DurableAcceptance.Rejected -> {
                    outbox.recordFailure(envelope.operationId, OutboxFailureKind.PERMANENT, accepted.code)
                    _health.value = CollaborationHealth.OPERATION_REJECTED
                }
                is DurableAcceptance.Retryable -> {
                    val retryAt = System.currentTimeMillis() + retryDelay(outbox.attempts(envelope.operationId))
                    outbox.recordFailure(envelope.operationId, OutboxFailureKind.TRANSIENT, accepted.code, retryAt)
                    _health.value = CollaborationHealth.TRANSPORT_UNAVAILABLE
                }
            }
        } finally {
            durableInFlight.remove(envelope.operationId.value)
        }
    }

    private fun startCoalescerTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (true) {
                val profile = adaptiveSync.profile.value
                delay(if (profile.sendPreviews && profile.previewHz > 0) profile.previewIntervalMs else 100L)
                coalescer.poll(profile, System.currentTimeMillis()).forEach { envelope ->
                    if (transport.state.value == TransportState.CONNECTED) transport.send(envelope)
                }
            }
        }
    }

    private suspend fun replayWhenConnected() {
        transport.state.collect { current ->
            _health.value = when (current) {
                TransportState.IDLE, TransportState.CLOSED -> CollaborationHealth.OFFLINE
                TransportState.CONNECTING -> CollaborationHealth.CONNECTING
                TransportState.RECONNECTING -> CollaborationHealth.RECONNECTING
                TransportState.CONNECTED -> CollaborationHealth.CONNECTED
            }
            if (current == TransportState.CONNECTED) {
                room?.let { active -> outbox.pending(active).forEach { persistAndNotify(it) } }
            }
        }
    }

    private fun startRetryLoop() {
        retryJob?.cancel()
        retryJob = scope.launch {
            while (true) {
                delay(1_000)
                if (transport.state.value == TransportState.CONNECTED) {
                    room?.let { active -> outbox.pending(active).forEach { persistAndNotify(it) } }
                }
            }
        }
    }

    private fun encryptOutbound(envelope: OutboundEnvelope): OutboundEnvelope {
        if (!crypto.isE2ee || envelope.payload == null) return envelope
        val context = requireNotNull(cryptoContextProvider) { "E2EE collaboration requires a key context" }
            .contextFor(envelope).requireMatches(envelope)
        val sealed = crypto.sealPayload(envelope.payload, context)
        return envelope.copy(payload = sealed, payloadHash = hashPayload(sealed))
    }

    private fun decryptInbound(envelope: InboundEnvelope): InboundEnvelope? {
        if (!crypto.isE2ee || envelope.payload == null) return envelope
        if (envelope.protocolVersion != OutboundEnvelope.PROTOCOL_VERSION) {
            _health.value = CollaborationHealth.PROTOCOL_INCOMPATIBLE
            return null
        }
        val outbound = OutboundEnvelope(
            envelope.roomId, envelope.actorId, envelope.eventClass, envelope.eventType,
            envelope.targetId, envelope.seq, envelope.clientSentAt, envelope.payload,
            envelope.deviceId, envelope.operationId, envelope.batchId, envelope.protocolVersion,
            envelope.semanticVersion, envelope.artifactSchemaVersion, envelope.keyEpoch,
            envelope.contentType, envelope.payloadHash, envelope.serverRevision,
            envelope.revisionHash,
        )
        val context = cryptoContextProvider?.contextFor(outbound)?.requireMatches(outbound) ?: run {
            _health.value = CollaborationHealth.MESSAGE_AUTHENTICITY_FAILED
            return null
        }
        return runCatching {
            val plaintext = crypto.openPayload(envelope.payload, context)
            envelope.copy(payload = plaintext, payloadHash = hashPayload(plaintext))
        }.getOrElse {
            _health.value = CollaborationHealth.MESSAGE_AUTHENTICITY_FAILED
            null
        }
    }

    private fun retryDelay(attempt: Int): Long = (250L shl attempt.coerceIn(0, 7)).coerceAtMost(30_000L)

    fun updateSituation(situation: CollabSituation) = adaptiveSync.update(situation)
    fun reportIntegrationFailure(health: CollaborationHealth) {
        require(health in setOf(
            CollaborationHealth.PROTOCOL_INCOMPATIBLE,
            CollaborationHealth.OPERATION_REJECTED,
            CollaborationHealth.MERGE_CONFLICT,
            CollaborationHealth.ASSET_MISSING,
        )) { "Invalid integration health state" }
        _health.value = health
    }
    fun metricsSnapshot() = transport.metricsSnapshot()
    val previewsDropped: Long get() = coalescer.previewsDropped
    suspend fun pendingOperations(): Int = outbox.size()

    /** Applies the same authenticated decryption boundary to RPC-recovered envelopes as live traffic. */
    fun decodeRecovered(envelope: InboundEnvelope): InboundEnvelope? = decryptInbound(envelope)

    suspend fun close() {
        tickerJob?.cancel()
        retryJob?.cancel()
        transport.close()
        scope.cancel()
    }
}
