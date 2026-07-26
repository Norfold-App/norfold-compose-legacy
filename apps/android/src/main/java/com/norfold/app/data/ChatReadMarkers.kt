package com.norfold.app.data

import android.content.Context

/**
 * Per-conversation "read up to" watermarks backing the inbox unread badges. A conversation's
 * unread count is its messages newer than the watermark that weren't authored by this user.
 *
 * SharedPreferences rather than Room: watermarks are device-local UI state (like scroll
 * positions), not workspace data — they don't sync, don't back up, and writing one shouldn't
 * touch the database or trigger snapshot flows. Keyed per workspace + channel; the General feed
 * uses the "general" key since it has no channel row.
 */
object ChatReadMarkers {
    private const val Preferences = "norfold_chat_read_markers"

    fun channelKey(channelId: Long?): String = channelId?.toString() ?: "general"

    fun lastReadAt(context: Context, workspaceId: Long, channelKey: String): Long =
        context.getSharedPreferences(Preferences, Context.MODE_PRIVATE)
            .getLong(key(workspaceId, channelKey), 0L)

    /** Moves the watermark to now. Called when a conversation is opened or left. */
    fun markRead(context: Context, workspaceId: Long, channelKey: String) {
        context.getSharedPreferences(Preferences, Context.MODE_PRIVATE)
            .edit()
            .putLong(key(workspaceId, channelKey), System.currentTimeMillis())
            .apply()
    }

    private fun key(workspaceId: Long, channelKey: String) = "ws:$workspaceId:$channelKey"
}
