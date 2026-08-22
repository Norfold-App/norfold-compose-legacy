package com.norfold.app.data

import com.norfold.app.data.ChatConversationPrefs.NotificationLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The translation between the device's notification preference and the server's.
 *
 * This is worth pinning because both failure modes are silent. The RPC is an upsert over `level`
 * *and* `muted_until`, so a mirror that sends one field clears the other — and the symptom is not an
 * error, it is a conversation quietly unmuting itself days later, on a different device, with
 * nothing in any log. In the other direction a level string nobody recognises must not become "do
 * not notify"; that would silence a conversation the owner never silenced.
 *
 * The single most important pin here is [aMutedConversationSurvivesALevelChange] together with
 * [anUnmutedConversationClearsTheServerMute]: both halves must always travel, and "not muted" has to
 * be sent as an explicit null rather than left out.
 */
class ConversationNotificationMirrorTest {
    private val now = 1_700_000_000_000L

    // ── Levels, both directions ──

    @Test
    fun everyLevelMapsToTheStringTheRpcAccepts() {
        // The RPC raises invalid_level for anything outside this set, so a typo here is a runtime
        // failure on a real device and nothing at compile time.
        assertEquals("off", ConversationNotificationMirror.toServer(NotificationLevel.None, false).level)
        assertEquals("mentions", ConversationNotificationMirror.toServer(NotificationLevel.Mentions, false).level)
        assertEquals("all", ConversationNotificationMirror.toServer(NotificationLevel.All, false).level)
    }

    @Test
    fun everyLevelSurvivesTheRoundTrip() {
        NotificationLevel.entries.forEach { level ->
            val sent = ConversationNotificationMirror.toServer(level, muted = false)
            val back = ConversationNotificationMirror.toLocal(sent.level, sent.mutedUntil, now)
            assertEquals("$level did not survive", level, back.level)
            assertFalse("$level came back muted", back.muted)
        }
    }

    @Test
    fun everyLevelSurvivesTheRoundTripWhileMuted() {
        // Muting must not disturb the level, and the level must not disturb the mute. This is the
        // property that fails if either field is ever sent on its own.
        NotificationLevel.entries.forEach { level ->
            val sent = ConversationNotificationMirror.toServer(level, muted = true)
            val back = ConversationNotificationMirror.toLocal(sent.level, sent.mutedUntil, now)
            assertEquals("$level did not survive", level, back.level)
            assertTrue("$level lost its mute", back.muted)
        }
    }

    @Test
    fun theServerLevelIsReadCaseInsensitively() {
        // Postgres stores exactly what the check constraint allows, but the value also arrives from
        // migrations, dashboards and any later client. Reading it strictly would turn a capitalised
        // 'Off' into All, which is the wrong direction to fail in.
        assertEquals(NotificationLevel.None, ConversationNotificationMirror.toLocal("OFF", null, now).level)
        assertEquals(NotificationLevel.Mentions, ConversationNotificationMirror.toLocal("Mentions", null, now).level)
    }

    @Test
    fun anUnreadableLevelFallsBackToAll() {
        // Same direction as ChatConversationPrefs' own valueOf fallback: a value nobody can parse
        // must never be the reason a message arrives without a sound.
        listOf(null, "", "  ", "silent", "muted", "none", "ALL_MESSAGES").forEach {
            assertEquals("$it", NotificationLevel.All, ConversationNotificationMirror.toLocal(it, null, now).level)
        }
    }

    // ── Mute: a boolean on one side, a timestamp on the other ──

    @Test
    fun anIndefiniteMuteIsSentAsTheSentinel() {
        val sent = ConversationNotificationMirror.toServer(NotificationLevel.All, muted = true)
        assertEquals(ConversationNotificationMirror.IndefiniteMuteUntil, sent.mutedUntil)
    }

    @Test
    fun theSentinelIsFarEnoughOutToOutliveTheProduct() {
        // The whole mechanism rests on this timestamp being in the future whenever it is read. A
        // sentinel that quietly passes would unmute every muted conversation at once.
        val sentinel = java.time.Instant.parse(ConversationNotificationMirror.IndefiniteMuteUntil).toEpochMilli()
        assertTrue(sentinel > now)
        assertTrue(
            "sentinel is not indefinite",
            ConversationNotificationMirror.toLocal("all", ConversationNotificationMirror.IndefiniteMuteUntil, sentinel - 1).muted,
        )
    }

    @Test
    fun anUnmutedConversationClearsTheServerMute() {
        // Explicitly null, not absent. The upsert writes whatever it is given, so omitting this
        // would leave a mute the owner has just switched off in place on the server — and the server
        // suppresses on an active mute, so the conversation would stay silent with the UI showing it
        // unmuted.
        val sent = ConversationNotificationMirror.toServer(NotificationLevel.All, muted = false)
        assertNull(sent.mutedUntil)
    }

    @Test
    fun aMutedConversationSurvivesALevelChange() {
        // What the pair-always-travels rule exists for: changing the level while muted must send the
        // mute along with it. Modelled the way the ViewModel does it — re-read both from the store,
        // then map — so the assertion is about the mapping contract the ViewModel relies on.
        val sent = ConversationNotificationMirror.toServer(NotificationLevel.Mentions, muted = true)
        assertEquals("mentions", sent.level)
        assertEquals(ConversationNotificationMirror.IndefiniteMuteUntil, sent.mutedUntil)
    }

    @Test
    fun aPastMuteHasExpired() {
        val past = java.time.Instant.ofEpochMilli(now - 60_000).toString()
        assertFalse(ConversationNotificationMirror.toLocal("all", past, now).muted)
    }

    @Test
    fun aFutureMuteIsStillActive() {
        val future = java.time.Instant.ofEpochMilli(now + 60_000).toString()
        assertTrue(ConversationNotificationMirror.toLocal("all", future, now).muted)
    }

    @Test
    fun aMuteExactlyAtTheCurrentInstantHasExpired() {
        // The server's own test is `muted_until > now()`, so the boundary is read the same way here.
        // Disagreeing by one instant would mean the two sides briefly show different states.
        val exact = java.time.Instant.ofEpochMilli(now).toString()
        assertFalse(ConversationNotificationMirror.toLocal("all", exact, now).muted)
    }

    @Test
    fun noMuteIsNotMuted() {
        listOf(null, "", "   ").forEach {
            assertFalse("$it", ConversationNotificationMirror.toLocal("all", it, now).muted)
        }
    }

    @Test
    fun bothTimestampSpellingsPostgrestEmitsAreUnderstood() {
        // The same timestamptz column comes back as '...Z' from some paths and '...+00:00' from
        // others; Instant.parse handles only the first. A spelling that fails to parse reads as "not
        // muted", so an unhandled format silently unmutes.
        val zulu = "2286-11-20T17:46:40Z"
        val offset = "2286-11-20T17:46:40+00:00"
        assertTrue(zulu, ConversationNotificationMirror.toLocal("all", zulu, now).muted)
        assertTrue(offset, ConversationNotificationMirror.toLocal("all", offset, now).muted)
    }

    @Test
    fun anUnparseableTimestampIsNotMuted() {
        // Not the other way round: a garbled value must not silence a conversation indefinitely with
        // no way for the owner to tell why.
        listOf("tomorrow", "9999", "2026-13-45T99:99:99Z", "0").forEach {
            assertFalse(it, ConversationNotificationMirror.toLocal("all", it, now).muted)
        }
    }

    // ── The combination the server acts on ──

    @Test
    fun onlyNoneAndAnActiveMuteAskTheServerToSuppress() {
        // enqueue_account_chat_push returns early on level 'off' or a future muted_until, and
        // delivers otherwise. This pins which of the app's states reach that branch, because it is
        // the difference between a quiet notification and no notification at all.
        fun suppresses(level: NotificationLevel, muted: Boolean): Boolean {
            val sent = ConversationNotificationMirror.toServer(level, muted)
            val mutedUntil = sent.mutedUntil?.let { java.time.Instant.parse(it).toEpochMilli() }
            return sent.level == "off" || (mutedUntil != null && mutedUntil > now)
        }
        assertTrue(suppresses(NotificationLevel.None, false))
        assertTrue(suppresses(NotificationLevel.All, true))
        assertTrue(suppresses(NotificationLevel.Mentions, true))
        assertFalse(suppresses(NotificationLevel.All, false))
        // 'mentions' unmuted must still be delivered: the server cannot find a mention in
        // ciphertext, so suppressing here would drop every mention it exists to surface.
        assertFalse(suppresses(NotificationLevel.Mentions, false))
    }

    @Test
    fun theDefaultStateAsksForNothing() {
        // A fresh conversation: level All, not muted. Mirroring this must not suppress anything, and
        // must clear any stale mute rather than leave one behind.
        val sent = ConversationNotificationMirror.toServer(NotificationLevel.All, muted = false)
        assertEquals("all", sent.level)
        assertNull(sent.mutedUntil)
        val back = ConversationNotificationMirror.toLocal(sent.level, sent.mutedUntil, now)
        assertEquals(NotificationLevel.All, back.level)
        assertFalse(back.muted)
    }
}
