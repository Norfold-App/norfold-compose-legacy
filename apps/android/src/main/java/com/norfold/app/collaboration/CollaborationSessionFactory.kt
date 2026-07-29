package com.norfold.app.collaboration

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.realtime

/**
 * Factory that wires a [CollaborationSession] for a given provider and capability set.
 *
 * Swapping providers requires only changing which transport this factory instantiates —
 * [CollaborationSession], the merge engine, and the editor layer are unaffected.
 * This is the single point guaranteed by the architecture contract in PROVIDER_DECISION.md.
 *
 * Provider selection is PENDING device-side benchmark results. Until then, the factory
 * defaults to Supabase Broadcast (lowest lock-in, no extra SDK, same auth token).
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
    ): CollaborationSession {
        val transport: RealtimeCollaborationTransport = when (provider) {
            Provider.SUPABASE -> {
                requireNotNull(supabase) { "supabase client required for SUPABASE provider" }
                SupabaseBroadcastTransport(supabase)
            }
            Provider.CLOUDFLARE -> {
                requireNotNull(cloudflareWorkerUrl) { "cloudflareWorkerUrl required for CLOUDFLARE provider" }
                CloudflareDurableObjectTransport(workerBaseUrl = cloudflareWorkerUrl)
            }
            Provider.ABLY -> AblyPubSubTransport()
        }
        return CollaborationSession(
            transport = transport,
            crypto = crypto,
            capabilities = capabilities,
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
    ): CollaborationSession = create(
        provider = Provider.SUPABASE,
        supabase = supabase,
        capabilities = capabilities,
        crypto = crypto,
    )
}
