package com.norfold.app.data

import android.content.Context

/**
 * Per-conversation notification preferences: mute state and notification level.
 * Account conversations (DMs + personal groups) are keyed by their global
 * accountConversationId so the setting follows the user across every workspace
 * and every workspace on this device; workspace channels fall back to the local
 * channel id.
 *
 * SharedPreferences rather than Room, for the same reasons as [ChatReadMarkers]:
 * these are device-local UI preferences, not workspace data — they don't back up,
 * and writing one shouldn't touch the database.
 *
 * They are not entirely device-local any more, and the exception is narrow enough
 * to name here: for an *account* conversation, [ConversationNotificationMirror]
 * copies the pair to the server after every write and reads it back on a social
 * refresh. Not to make this a synced store, but because the server decides whether
 * to create a push at all, and it cannot honour a preference it has never seen. A
 * workspace channel is still purely local — the server has no row for one. This
 * store stays the source of truth either way: a write lands here first and the
 * mirror is quiet when it fails.
 */
object ChatConversationPrefs {
    private const val Preferences = "norfold_chat_conversation_prefs"

    /** Ordered least→most interruptive; the per-conversation menu offers all three. */
    enum class NotificationLevel { None, Mentions, All }

    /**
     * Stable key for a conversation. Account conversations use their global id so
     * the preference is portable; everything else uses the local channel id.
     */
    fun conversationKey(accountConversationId: String?, channelId: Long?): String =
        accountConversationId?.let { "acct:$it" } ?: "chan:${channelId ?: 0L}"

    fun isMuted(context: Context, conversationKey: String): Boolean =
        prefs(context).getBoolean("mute:$conversationKey", false)

    fun setMuted(context: Context, conversationKey: String, muted: Boolean) {
        prefs(context).edit().putBoolean("mute:$conversationKey", muted).apply()
    }

    fun notificationLevel(context: Context, conversationKey: String): NotificationLevel {
        val raw = prefs(context).getString("notif:$conversationKey", null) ?: return NotificationLevel.All
        return runCatching { NotificationLevel.valueOf(raw) }.getOrDefault(NotificationLevel.All)
    }

    fun setNotificationLevel(context: Context, conversationKey: String, level: NotificationLevel) {
        prefs(context).edit().putString("notif:$conversationKey", level.name).apply()
    }

    fun rulesLastSeenAt(context: Context, conversationKey: String): Long =
        prefs(context).getLong("rules_seen:$conversationKey", 0L)

    fun markRulesSeen(context: Context, conversationKey: String, seenAt: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong("rules_seen:$conversationKey", seenAt).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(Preferences, Context.MODE_PRIVATE)
}
