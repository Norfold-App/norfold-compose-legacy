package com.norfold.app.collaboration

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CollaborationServerContractTest {
    @Test fun `durable truth is server broadcast and direct channel writes are absent`() {
        val migration = repositoryFile("supabase/migrations/202608100001_collaboration_durability.sql").readText()
        assertTrue(migration.contains("perform realtime.send("))
        assertTrue(migration.contains("'type', 'semantic.batch'"))
        assertFalse(migration.contains("collaboration_realtime_write"))
        assertTrue(migration.contains("authorize_collaboration_connection"))
    }

    @Test fun `edge relay derives actor and device then rejects durable envelopes`() {
        val worker = repositoryFile("infra/collaboration/cloudflare/src/index.ts").readText()
        assertTrue(worker.contains("jwtVerify(token, jwks"))
        assertTrue(worker.contains("authorize_collaboration_connection"))
        assertTrue(worker.contains("actor: attachment.userId"))
        assertTrue(worker.contains("device: attachment.deviceId"))
        assertTrue(worker.contains("parsed.cls === \"EPHEMERAL_LATEST\""))
        assertTrue(worker.contains("parsed.type !== \"semantic.batch\""))
    }

    @Test fun `mobile source contains no Ably server credential and token is receive scoped`() {
        val android = repositoryFile(
            "apps/android/src/main/java/com/norfold/app/collaboration/AblyTokenIssuerClient.kt",
        ).readText()
        val issuer = repositoryFile("supabase/functions/collaboration-token/index.ts").readText()
        assertFalse(android.contains("ABLY_API_KEY"))
        assertTrue(issuer.contains("requiredEnv(\"ABLY_API_KEY\")"))
        assertTrue(issuer.contains("[\"subscribe\", \"presence\"]"))
        assertFalse(issuer.contains("[\"publish\", \"subscribe\""))
    }

    private fun repositoryFile(path: String): File {
        val candidates = listOf(File(path), File("../../$path"))
        return candidates.firstOrNull(File::isFile) ?: error("Repository file not found: $path")
    }
}
