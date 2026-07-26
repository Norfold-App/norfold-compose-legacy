package com.norfold.app.data

import android.content.Context

/**
 * Per-conversation composer drafts. When a message is typed but not sent, it should still be
 * there after switching conversations, backgrounding the app, or process death — the behaviour
 * every mainstream messenger has.
 *
 * SharedPreferences rather than Room, for the same reasons as [ChatReadMarkers]: a draft is
 * device-local UI state, must not sync to the workspace, must not trigger snapshot/backup flows,
 * and is cheap to read on every conversation open. Keyed per workspace + channel; the General
 * feed uses the "general" key since it has no channel row.
 */
object ChatDrafts {
    private const val Preferences = "norfold_chat_drafts"

    fun channelKey(channelId: Long?): String = ChatReadMarkers.channelKey(channelId)

    fun draft(context: Context, workspaceId: Long, channelKey: String): String =
        context.getSharedPreferences(Preferences, Context.MODE_PRIVATE)
            .getString(key(workspaceId, channelKey), "")
            .orEmpty()

    /** Stores (or clears, when [text] is blank) the draft for one conversation. */
    fun setDraft(context: Context, workspaceId: Long, channelKey: String, text: String) {
        val prefs = context.getSharedPreferences(Preferences, Context.MODE_PRIVATE).edit()
        if (text.isBlank()) prefs.remove(key(workspaceId, channelKey)) else prefs.putString(key(workspaceId, channelKey), text)
        prefs.apply()
    }

    private fun key(workspaceId: Long, channelKey: String) = "ws:$workspaceId:$channelKey"
}
