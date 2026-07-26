package com.norfold.app.domain.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PrivateChatArchiveCodecTest {
    @Test
    fun roundTripPreservesPrivateHistoryAndTombstones() {
        val source = PrivateChatArchiveSnapshot(
            accountId = "account-a",
            generatedAt = 99,
            conversations = listOf(
                ArchivedChatConversation(
                    syncId = "channel-sync",
                    accountConversationId = "conversation-id",
                    kind = "Dm",
                    name = "Alice",
                    counterpartUsername = "alice",
                    icon = "app.people",
                    createdAt = 4,
                    pinned = true,
                    securityMode = "EndToEndEncrypted",
                    securityEpochId = "epoch-1",
                ),
            ),
            messages = listOf(
                ArchivedChatMessage(
                    clientSyncId = "message-sync",
                    remoteRelayId = "relay-id",
                    conversationSyncId = "channel-sync",
                    authorUsername = "alice",
                    authorDisplayName = "Alice",
                    body = "private body",
                    color = 7,
                    createdAt = 5,
                    starred = true,
                    securityMode = "EndToEndEncrypted",
                    securityEpochId = "epoch-1",
                ),
            ),
            tombstones = listOf(
                ArchivedChatTombstone("deleted-sync", "conversation-id", 6, "delete_everyone"),
            ),
        )

        assertEquals(source, PrivateChatArchiveCodec.decode(PrivateChatArchiveCodec.encode(source)))
    }

    @Test
    fun rejectsFutureArchiveVersion() {
        val future = """{"version":2,"accountId":"a","generatedAt":1,"conversations":[],"messages":[],"tombstones":[]}"""
        assertThrows(IllegalArgumentException::class.java) {
            PrivateChatArchiveCodec.decode(future.encodeToByteArray())
        }
    }
}
