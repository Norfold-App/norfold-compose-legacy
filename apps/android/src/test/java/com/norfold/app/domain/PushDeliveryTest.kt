package com.norfold.app.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What an arrived push is allowed to do, which is the half that can be certified without a device.
 *
 * The delivery itself — FCM handing the message over, a channel's sound, whether `setSilent` really
 * silences on a given OEM build — cannot be. What is pinned here is the decision, because that is
 * where a preference silently becomes a lost message rather than a quiet one. A push suppressed by
 * the wrong flag produces *nothing*, and nothing is exactly what a working suppression also
 * produces, so the failure is invisible on a device and obvious only here.
 *
 * The single most important pin in the file is [aChatPushIsNeverSuppressed]: every state that means
 * "do not interrupt me" has to come out as silent, because the alternative discards a message
 * somebody sent.
 */
class PushDeliveryTest {
    private fun decide(
        kind: String?,
        tasks: Boolean = true,
        quiet: Boolean = false,
        silencedConversation: Boolean = false,
    ) = PushDelivery.decide(kind, tasks, quiet, silencedConversation)

    // ── Kind classification ──
    // Both prefixes are matched rather than compared, because the server emits several kinds under
    // each: chat_message, chat_dm, chat_group and task_changed all exist in the migrations.

    @Test
    fun everyKindTheServerEmitsIsClassified() {
        listOf("chat_message", "chat_dm", "chat_group").forEach {
            assertTrue(it, PushDelivery.isChatKind(it))
            assertFalse(it, PushDelivery.isTaskKind(it))
        }
        assertTrue(PushDelivery.isTaskKind("task_changed"))
        assertFalse(PushDelivery.isChatKind("task_changed"))
    }

    @Test
    fun aKindNeitherChatNorTaskIsNeither() {
        // document_changed, document_comment, chat_request, backup, sync — and the empty string the
        // account migration writes into 'kind' in at least one branch.
        listOf("document_changed", "document_comment", "backup", "sync", "", null).forEach {
            assertFalse("$it", PushDelivery.isChatKind(it))
            assertFalse("$it", PushDelivery.isTaskKind(it))
        }
    }

    @Test
    fun chatRequestIsAChatKind() {
        // 'chat_request' is the message-request push, and it shares the chat prefix, so it is muted
        // and deduped by the same rules. That is the intended reading: a request is a conversation.
        assertTrue(PushDelivery.isChatKind("chat_request"))
    }

    // ── Task notifications: the only thing that is ever suppressed ──

    @Test
    fun aTaskPushIsSuppressedWhenTaskNotificationsAreOff() {
        val decision = decide("task_changed", tasks = false)
        assertTrue(decision.suppressed)
    }

    @Test
    fun aTaskPushArrivesWhenTaskNotificationsAreOn() {
        val decision = decide("task_changed", tasks = true)
        assertFalse(decision.suppressed)
        assertFalse(decision.silent)
    }

    @Test
    fun switchingOffTaskNotificationsDoesNotSilenceChat() {
        // The flag is `notificationPush`, and its name invites exactly this bug: reading it as "all
        // push" would stop delivering messages other people sent, from a switch both onboarding and
        // Settings label "Task reminders".
        val decision = decide("chat_message", tasks = false)
        assertFalse(decision.suppressed)
        assertFalse(decision.silent)
    }

    @Test
    fun switchingOffTaskNotificationsDoesNotSuppressAWorkspaceObject() {
        listOf("document_changed", "document_comment", "backup", "sync").forEach {
            assertFalse(it, decide(it, tasks = false).suppressed)
        }
    }

    // ── Quiet hours: silent, never suppressed ──

    @Test
    fun quietHoursSilenceEveryKindRatherThanDroppingIt() {
        listOf("chat_message", "chat_dm", "task_changed", "document_changed", "backup", null).forEach {
            val decision = decide(it, quiet = true)
            assertFalse("$it was suppressed", decision.suppressed)
            assertTrue("$it was not silenced", decision.silent)
        }
    }

    @Test
    fun outsideQuietHoursNothingIsSilenced() {
        listOf("chat_message", "task_changed", "document_changed").forEach {
            assertFalse(it, decide(it, quiet = false).silent)
        }
    }

    @Test
    fun quietHoursAndTaskNotificationsOffStillSuppressTheTaskPush() {
        // Suppression wins over silence when both apply: "I don't want these at all" is a stronger
        // statement than "not right now", and posting it silently would leave a notification the
        // owner has said they do not want.
        val decision = decide("task_changed", tasks = false, quiet = true)
        assertTrue(decision.suppressed)
    }

    // ── Per-conversation mute ──

    @Test
    fun aSilencedConversationIsSilentNotSuppressed() {
        val decision = decide("chat_dm", silencedConversation = true)
        assertFalse(decision.suppressed)
        assertTrue(decision.silent)
    }

    @Test
    fun aConversationPreferenceDoesNotReachANonChatPush() {
        // The flag is only meaningful for something that belongs to a conversation. A caller that
        // resolved it for a task push — which the service will not do, but the rule must not rely on
        // that — must not have it applied.
        listOf("task_changed", "document_changed", "backup").forEach {
            assertFalse(it, decide(it, silencedConversation = true).silent)
        }
    }

    @Test
    fun aChatPushIsNeverSuppressed() {
        // The property the whole design rests on, swept across every combination of the three
        // preferences. A message that somebody sent must always end up in the shade; the most any
        // preference may do is take away its sound. Anything else deletes a message before the owner
        // can see it, and they would never know it existed.
        listOf("chat_message", "chat_dm", "chat_group", "chat_request").forEach { kind ->
            listOf(true, false).forEach { tasks ->
                listOf(true, false).forEach { quiet ->
                    listOf(true, false).forEach { silenced ->
                        val decision = decide(kind, tasks, quiet, silenced)
                        assertFalse(
                            "$kind suppressed at tasks=$tasks quiet=$quiet silenced=$silenced",
                            decision.suppressed,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun nothingIsBothSuppressedAndSilent() {
        // A suppressed push is not posted, so describing it as silent would be meaningless — and a
        // caller reading `silent` without checking `suppressed` first would be misled into posting.
        listOf("chat_message", "task_changed", "document_changed", null).forEach { kind ->
            listOf(true, false).forEach { tasks ->
                listOf(true, false).forEach { quiet ->
                    listOf(true, false).forEach { silenced ->
                        val decision = decide(kind, tasks, quiet, silenced)
                        assertFalse(
                            "$kind is both at tasks=$tasks quiet=$quiet silenced=$silenced",
                            decision.suppressed && decision.silent,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun theDefaultStateDeliversEverythingAudibly() {
        // A fresh install, no quiet hours, nothing muted: nothing is held back. This is the pin that
        // catches a gate accidentally inverted — the failure that would silence the whole product.
        listOf("chat_message", "chat_dm", "task_changed", "document_changed", "backup", "", null).forEach {
            val decision = decide(it)
            assertFalse("$it suppressed by default", decision.suppressed)
            assertFalse("$it silenced by default", decision.silent)
        }
    }
}
