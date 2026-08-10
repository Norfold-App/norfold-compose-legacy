package com.norfold.app.collaboration

import org.junit.Assert.*
import org.junit.Test

class PresenceAccessibilityTest {
    @Test fun `remote presence never becomes local selection and expires`() {
        var now = 100L
        val store = RemotePresenceStore(staleAfterMs = 50, clock = { now })
        val remote = RemotePresence(
            ActorId("peer"), DeviceId("phone"), "session", "Alex",
            SemanticSelectionAnchor("artifact", "block-1"), PresencePrecision.PRECISE, now,
        )
        store.update(remote)
        assertEquals(listOf(remote), store.active())
        now = 151
        assertTrue(store.active().isEmpty())
    }

    @Test fun `privacy mode exposes identity without precise target`() {
        val presence = RemotePresence(
            ActorId("peer"), DeviceId("phone"), "session", "Alex",
            SemanticSelectionAnchor("artifact", "private-block"), PresencePrecision.COARSE, 0,
        )
        assertEquals("Alex is viewing this artifact", presence.accessibleDescription())
    }

    @Test fun `delayed or wrong artifact presence cannot replace current session state`() {
        val store = RemotePresenceStore(expectedArtifactId = "artifact", clock = { 20 })
        val current = RemotePresence(
            ActorId("peer"), DeviceId("phone"), "session", "Alex",
            SemanticSelectionAnchor("artifact", "new"), PresencePrecision.PRECISE, 20,
        )
        store.update(current)
        store.update(current.copy(anchor = SemanticSelectionAnchor("artifact", "old"), updatedAt = 10))
        store.update(current.copy(anchor = SemanticSelectionAnchor("other", "wrong"), updatedAt = 30))
        assertEquals("new", store.active(now = 20).single().anchor?.targetId)
    }

    @Test fun `far future presence timestamp cannot pin a stale session forever`() {
        val store = RemotePresenceStore(clock = { 100 })
        store.update(RemotePresence(
            ActorId("peer"), DeviceId("phone"), "session", "Alex", null,
            PresencePrecision.COARSE, Long.MAX_VALUE,
        ))
        assertTrue(store.active(now = 100).isEmpty())
    }

    @Test fun `collaboration error states have textual status and retry semantics`() {
        val status = CollaborationAccessibilityPresenter.present(
            CollaborationHealth.TRANSPORT_UNAVAILABLE, unsent = 3, collaborators = emptyList(),
        )
        assertTrue(status.stateDescription.contains("3 unsent changes"))
        assertTrue(status.stateDescription.contains("service unavailable"))
        assertTrue(status.retryAvailable)
        assertNotNull(status.liveAnnouncement)
    }

    @Test fun `color independent collaborator text names every participant`() {
        val peer = RemotePresence(ActorId("p"), DeviceId("d"), "s", "Jordan", null, PresencePrecision.COARSE, 0)
        assertTrue(peer.accessibleDescription().contains("Jordan"))
    }

    @Test fun `every collaboration health state has nonvisual semantics`() {
        CollaborationHealth.entries.forEach { health ->
            val status = CollaborationAccessibilityPresenter.present(health, 1, emptyList())
            assertTrue("health=$health", status.stateDescription.isNotBlank())
        }
        assertTrue(
            CollaborationAccessibilityPresenter.present(CollaborationHealth.ASSET_MISSING, 0, emptyList())
                .stateDescription.contains("asset"),
        )
        assertTrue(
            CollaborationAccessibilityPresenter.present(CollaborationHealth.MERGE_CONFLICT, 0, emptyList())
                .stateDescription.contains("review"),
        )
    }
}
