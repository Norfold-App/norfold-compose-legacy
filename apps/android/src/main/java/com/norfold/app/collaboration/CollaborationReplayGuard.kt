package com.norfold.app.collaboration

import java.util.LinkedHashMap

sealed interface ReplayDecision {
    data object Accept : ReplayDecision
    data object Duplicate : ReplayDecision
    data object StaleKeyEpoch : ReplayDecision
    data object WrongScope : ReplayDecision
    data object InvalidPayload : ReplayDecision
}

/** Bounded replay cache. Durable idempotency is also enforced by the server operation primary key. */
class CollaborationReplayGuard(
    private val roomId: RoomId,
    private val minimumKeyEpoch: () -> Long,
    private val maxOperationIds: Int = 16_384,
) {
    private val seen = object : LinkedHashMap<String, Unit>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean = size > maxOperationIds
    }

    @Synchronized
    fun validate(envelope: InboundEnvelope): ReplayDecision {
        if (envelope.roomId != roomId) return ReplayDecision.WrongScope
        if (envelope.keyEpoch < minimumKeyEpoch()) return ReplayDecision.StaleKeyEpoch
        if (envelope.payloadHash != hashPayload(envelope.payload)) return ReplayDecision.InvalidPayload
        if (envelope.operationId.value in seen) return ReplayDecision.Duplicate
        return ReplayDecision.Accept
    }

    @Synchronized
    fun markApplied(operationId: OperationId) {
        seen[operationId.value] = Unit
    }

    /** Convenience for consumers whose validation and application are one indivisible step. */
    @Synchronized
    fun inspect(envelope: InboundEnvelope): ReplayDecision = validate(envelope).also { decision ->
        if (decision == ReplayDecision.Accept) markApplied(envelope.operationId)
    }
}
