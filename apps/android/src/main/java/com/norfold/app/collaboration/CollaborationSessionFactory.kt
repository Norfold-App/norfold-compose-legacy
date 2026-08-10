package com.norfold.app.collaboration

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.realtime

/**
 * Factory that wires a [CollaborationSession] for a given provider and capability set.
 *
 * Provider selection remains pending device-side benchmark results. Supabase is the only
 * enabled artifact path because its durable RPC and realtime notification share one server
 * transaction. Alternative transports remain available for isolated benchmark rooms until
 * an equally durable relay path is certified.
 */
object CollaborationSessionFactory {

    enum class Provider { SUPABASE, CLOUDFLARE, ABLY }

    /**
     * Create a session backed by [provider].
     *
     * @param supabase Required for SUPABASE provider; ignored for others.
     * @param cloudflareWorkerUrl Required for CLOUDFLARE provider.
     * @param capabilities Capability descriptor — policy is decided by the caller, not here.
     * @param crypto E2EE boundary. Defaults to managed (passthrough); inject
     *   [CryptoEngineCollabEnvelope] for E2EE artifacts.
     */
    fun create(
        provider: Provider = Provider.SUPABASE,
        supabase: SupabaseClient? = null,
        cloudflareWorkerUrl: String? = null,
        capabilities: CollabCapabilities = CollabCapabilities.BETA,
        crypto: CollabEnvelopeCrypto = ManagedCollabEnvelopeCrypto,
        cryptoContextProvider: CollabCryptoContextProvider? = null,
        outbox: OfflineOutbox,
        durableGateway: DurableOperationGateway? = null,
        deviceId: DeviceId = DeviceId("unknown-device"),
    ): CollaborationSession {
        val transport: RealtimeCollaborationTransport = when (provider) {
            Provider.SUPABASE -> {
                requireNotNull(supabase) { "supabase client required for SUPABASE provider" }
                SupabaseBroadcastTransport(supabase)
            }
            Provider.CLOUDFLARE -> {
                requireNotNull(cloudflareWorkerUrl) { "cloudflareWorkerUrl required for CLOUDFLARE provider" }
                CloudflareDurableObjectTransport(workerBaseUrl = cloudflareWorkerUrl, deviceId = deviceId)
            }
            Provider.ABLY -> AblyPubSubTransport()
        }
        return CollaborationSession(
            transport = transport,
            crypto = crypto,
            cryptoContextProvider = cryptoContextProvider,
            capabilities = capabilities,
            outbox = outbox,
            durableGateway = durableGateway
                ?: if (provider == Provider.SUPABASE) SupabaseDurableOperationGateway(requireNotNull(supabase))
                else OfflineDurableOperationGateway,
            deviceId = deviceId,
            artifactDeliverySupported = provider == Provider.SUPABASE,
        )
    }

    /**
     * Convenience: create a Supabase-backed session using the app's existing [SupabaseClient].
     * This is the expected production path until the provider benchmark selects a winner.
     */
    fun createDefault(
        supabase: SupabaseClient,
        capabilities: CollabCapabilities = CollabCapabilities.BETA,
        crypto: CollabEnvelopeCrypto = ManagedCollabEnvelopeCrypto,
        cryptoContextProvider: CollabCryptoContextProvider? = null,
        outbox: OfflineOutbox,
        deviceId: DeviceId = DeviceId("unknown-device"),
    ): CollaborationSession = create(
        provider = Provider.SUPABASE,
        supabase = supabase,
        capabilities = capabilities,
        crypto = crypto,
        cryptoContextProvider = cryptoContextProvider,
        outbox = outbox,
        durableGateway = SupabaseDurableOperationGateway(supabase),
        deviceId = deviceId,
    )
}
