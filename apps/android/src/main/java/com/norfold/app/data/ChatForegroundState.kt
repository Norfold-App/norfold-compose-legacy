package com.norfold.app.data

/**
 * In-process signal of which chat conversation (if any) is on screen right now,
 * so an incoming push for that exact conversation can be suppressed as a
 * redundant system notification (spec §149, foreground dedup).
 *
 * Deliberately in-memory only: the messaging service and the UI share one
 * process, and this is transient foreground state that must never persist or
 * sync. It holds routing identifiers only — never message content.
 */
object ChatForegroundState {
    // Remote account conversation id (direct_conversations.id) currently open, if any.
    @Volatile
    var activeAccountConversationId: String? = null

    // Remote workspace channel sync id currently open, if any.
    @Volatile
    var activeWorkspaceChannelSyncId: String? = null

    // True only while a conversation view is actually visible in the foreground.
    @Volatile
    var conversationVisible: Boolean = false

    fun isAccountConversationActive(conversationId: String?): Boolean =
        conversationVisible && conversationId != null && conversationId == activeAccountConversationId

    fun isWorkspaceChannelActive(channelSyncId: String?): Boolean =
        conversationVisible && channelSyncId != null && channelSyncId == activeWorkspaceChannelSyncId

    fun clear() {
        conversationVisible = false
        activeAccountConversationId = null
        activeWorkspaceChannelSyncId = null
    }
}
