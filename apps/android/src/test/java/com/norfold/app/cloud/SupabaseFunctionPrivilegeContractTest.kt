package com.norfold.app.cloud

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SupabaseFunctionPrivilegeContractTest {
    private val migration by lazy {
        repositoryFile(
            "supabase/migrations/20260830154534_restrict_security_definer_execute.sql",
        ).readText().lowercase().replace(Regex("\\s+"), " ")
    }

    @Test
    fun `client and policy functions deny public and anonymous execution`() {
        authenticatedFunctions.forEach { signature ->
            assertTrue(
                "$signature must revoke the implicit public privilege",
                migration.contains("revoke all on function $signature from public, anon;"),
            )
            assertTrue(
                "$signature must remain callable by authenticated app and policy paths",
                migration.contains("grant execute on function $signature to authenticated;"),
            )
        }
    }

    @Test
    fun `trigger functions have no direct API execution privilege`() {
        triggerFunctions.forEach { signature ->
            assertTrue(
                "$signature must remain trigger-only",
                migration.contains(
                    "revoke all on function $signature from public, anon, authenticated;",
                ),
            )
        }
    }

    private fun repositoryFile(path: String): File {
        val candidates = listOf(File(path), File("../../$path"))
        return candidates.firstOrNull(File::isFile) ?: error("Repository file not found: $path")
    }

    private companion object {
        val authenticatedFunctions = listOf(
            "public.create_friend_invite()",
            "public.redeem_friend_invite(text)",
            "public.add_contact_by_handle(text)",
            "public.open_direct_conversation(uuid)",
            "public.record_message_receipts(uuid, uuid[], text)",
            "public.is_workspace_member(uuid)",
            "public.has_workspace_role(uuid, public.workspace_role[])",
        )

        val triggerFunctions = listOf(
            "public.handle_new_user()",
            "public.record_dm_reply_contact()",
            "public.enqueue_workspace_object_notification()",
        )
    }
}
