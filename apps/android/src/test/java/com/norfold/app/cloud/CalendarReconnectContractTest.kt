package com.norfold.app.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CalendarReconnectContractTest {
    private val migration by lazy {
        repositoryFile(
            "supabase/migrations/20260831094834_serialize_calendar_account_reconnect.sql",
        ).readText().lowercase().replace(Regex("\\s+"), " ")
    }

    @Test
    fun `database permits one active account per provider`() {
        assertTrue(
            migration.contains(
                "create unique index integration_accounts_one_active_provider_idx " +
                    "on public.integration_accounts (user_id, provider) where revoked_at is null;",
            ),
        )
        assertTrue(migration.contains("pg_catalog.pg_advisory_xact_lock("))
        assertTrue(migration.contains("provider_subject <> p_provider_subject"))
    }

    @Test
    fun `replacement rpc is service-role only`() {
        val signature =
            "public.replace_calendar_integration( uuid, public.integration_provider, text, bytea, bytea, text[] )"
        assertTrue(migration.contains("revoke all on function $signature from public, anon, authenticated;"))
        assertTrue(migration.contains("grant execute on function $signature to service_role;"))
    }

    @Test
    fun `callback uses the atomic replacement rpc`() {
        val callback = repositoryFile("supabase/functions/calendar-callback/index.ts").readText()
        assertTrue(callback.contains("supabase.rpc("))
        assertTrue(callback.contains("\"replace_calendar_integration\""))
        assertFalse(callback.contains(".from(\"integration_accounts\").upsert("))
    }

    private fun repositoryFile(path: String): File {
        val candidates = listOf(File(path), File("../../$path"))
        return candidates.firstOrNull(File::isFile) ?: error("Repository file not found: $path")
    }
}
