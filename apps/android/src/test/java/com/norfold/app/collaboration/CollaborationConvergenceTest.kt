package com.norfold.app.collaboration

import com.norfold.app.collaboration.merge.LocalOperation
import com.norfold.app.collaboration.merge.RemoteOperation
import com.norfold.app.collaboration.merge.SemanticOpSetMergeEngine
import com.norfold.app.collaboration.protocol.SemanticMutation
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class CollaborationConvergenceTest {
    @Test fun `three clients converge after duplicate and reordered delivery`() {
        val engine = SemanticOpSetMergeEngine()
        val clients = List(3) { engine.createDocument("artifact", "actor-$it") }
        val operations = listOf(
            remote("a", "device-a", "op-1", 1, "flow", "block.insert", "doc", "block-a"),
            remote("b", "device-b", "op-2", 2, "flow", "block.move", "doc", "block-a"),
            remote("c", "device-c", "op-3", 3, "flow", "block.replace", "doc", "block-a"),
            remote("b", "device-b", "op-4", 4, "comment", "thread.reply", "thread-1", "comment-2"),
        )
        clients[0].also { operations.forEach(it::applyRemote); it.applyRemote(operations[2]) }
        clients[1].also { operations.reversed().forEach(it::applyRemote); it.applyRemote(operations[0]) }
        clients[2].also { listOf(operations[1], operations[3], operations[0], operations[2]).forEach(it::applyRemote) }
        assertEquals(1, clients.map { it.currentRevision() }.distinct().size)
    }

    @Test fun `same target conflict converges by logical clock and identity tie break`() {
        val engine = SemanticOpSetMergeEngine()
        val a = engine.createDocument("artifact", "a")
        val b = engine.createDocument("artifact", "b")
        val first = remote("a", "d1", "op-a", 7, "spreadsheet", "cell.write", "book", "cell-1")
        val second = remote("b", "d2", "op-b", 7, "spreadsheet", "cell.write", "book", "cell-1")
        a.applyRemote(first); a.applyRemote(second)
        b.applyRemote(second); b.applyRemote(first)
        assertEquals(a.currentRevision(), b.currentRevision())
    }

    @Test fun `fixed seed hostile schedules always converge`() {
        val seeds = listOf(7, 41, 808, 20260810)
        seeds.forEach { seed ->
            val engine = SemanticOpSetMergeEngine()
            val clients = List(3) { engine.createDocument("artifact", "actor-$it") }
            val operations = List(120) { i ->
                remote("actor-${i % 3}", "device-${i % 5}", "seed-$seed-op-$i", i.toLong() + 1,
                    listOf("flow", "document", "spreadsheet", "chart", "image", "relationship", "comment")[i % 7],
                    "property.set", "target-${i % 17}", "member-${i % 11}")
            }
            clients.forEachIndexed { index, client ->
                val shuffled = operations.shuffled(Random(seed + index))
                (shuffled + shuffled.take(12)).forEach(client::applyRemote)
            }
            assertEquals("seed=$seed", 1, clients.map { it.currentRevision() }.distinct().size)
            assertEquals("seed=$seed canonical", 1, clients.map { it.canonicalOperations() }.distinct().size)
        }
    }

    @Test fun `snapshot synchronization converges independent replicas`() {
        val engine = SemanticOpSetMergeEngine()
        val a = engine.createDocument("artifact", "a")
        val b = engine.createDocument("artifact", "b")
        a.applyLocal(LocalOperation("op-a", "a", "cmd", "device-a", 1,
            SemanticMutation(domain = "image", action = "edit.commit", targetId = "image-1")))
        b.integrate(a.syncSince(b.currentRevision()))
        assertEquals(a.currentRevision(), b.currentRevision())
    }

    @Test fun `same account sessions remain distinct by device and converge after partition`() {
        val engine = SemanticOpSetMergeEngine()
        val phone = engine.createDocument("artifact", "same-user")
        val tablet = engine.createDocument("artifact", "same-user")
        val phoneEdit = remote("same-user", "phone", "phone-op", 1, "flow", "block.insert", "doc", "phone-block")
        val tabletEdit = remote("same-user", "tablet", "tablet-op", 1, "flow", "block.insert", "doc", "tablet-block")
        phone.applyRemote(phoneEdit)
        tablet.applyRemote(tabletEdit)
        phone.applyRemote(tabletEdit)
        tablet.applyRemote(phoneEdit)
        assertEquals(phone.currentRevision(), tablet.currentRevision())
        assertEquals(2, phone.canonicalOperations().size)
    }

    @Test fun `opposite reconnect orders converge after both clients edited offline`() {
        val engine = SemanticOpSetMergeEngine()
        val a = engine.createDocument("artifact", "a")
        val b = engine.createDocument("artifact", "b")
        val editsA = List(20) { remote("a", "phone", "a-$it", it + 1L, "document", "object.property", "page", "a-$it") }
        val editsB = List(20) { remote("b", "tablet", "b-$it", it + 1L, "spreadsheet", "cell.write", "book", "b-$it") }
        editsA.forEach(a::applyRemote)
        editsB.forEach(b::applyRemote)
        editsB.reversed().forEach(a::applyRemote)
        editsA.reversed().forEach(b::applyRemote)
        assertEquals(a.currentRevision(), b.currentRevision())
        assertEquals(a.canonicalOperations(), b.canonicalOperations())
    }

    @Test fun `delete wins over an older edit without resurrecting object`() {
        val engine = SemanticOpSetMergeEngine()
        val document = engine.createDocument("artifact", "a")
        document.applyRemote(remote("a", "d", "insert", 1, "document", "object.insert", "page", "object-1"))
        document.applyRemote(remote("b", "d", "edit", 2, "document", "object.property", "page", "object-1"))
        document.applyRemote(remote("c", "d", "delete", 3, "document", "object.delete", "page", "object-1"))
        val existence = document.canonicalOperations().single { it.action.endsWith("insert") || it.action.endsWith("delete") }
        assertEquals("object.delete", existence.action)
        assertEquals(listOf("object.delete"), document.canonicalOperations().map { it.action })
    }

    @Test fun `formula and value writes share one authoritative cell input register`() {
        val document = SemanticOpSetMergeEngine().createDocument("artifact", "a")
        document.applyRemote(remote("a", "d1", "value", 1, "spreadsheet", "value.write", "book", "cell-a"))
        document.applyRemote(remote("b", "d2", "formula", 2, "spreadsheet", "formula.write", "book", "cell-a"))
        assertEquals(listOf("formula.write"), document.canonicalOperations().map { it.action })
    }

    @Test fun `concurrent anchored text inserts are both retained deterministically`() {
        val engine = SemanticOpSetMergeEngine()
        val a = engine.createDocument("artifact", "a")
        val b = engine.createDocument("artifact", "b")
        val first = remote("a", "d1", "text-a", 1, "flow", "text.insert", "paragraph", "anchor")
        val second = remote("b", "d2", "text-b", 1, "flow", "text.insert", "paragraph", "anchor")
        a.applyRemote(first); a.applyRemote(second)
        b.applyRemote(second); b.applyRemote(first)
        assertEquals(2, a.canonicalOperations().size)
        assertEquals(a.currentRevision(), b.currentRevision())
        assertEquals(a.canonicalOperations(), b.canonicalOperations())
    }

    @Test fun `text range deletion does not become a structural block tombstone`() {
        val document = SemanticOpSetMergeEngine().createDocument("artifact", "a")
        document.applyRemote(remote("a", "d1", "insert", 1, "flow", "text.insert", "paragraph", "insert-a"))
        document.applyRemote(remote("b", "d2", "delete", 2, "flow", "text.delete", "paragraph", "range-a"))
        document.applyRemote(remote("c", "d3", "mark", 3, "flow", "text.mark", "paragraph", "range-a"))
        assertEquals(setOf("text.insert", "text.delete", "text.mark"), document.canonicalOperations().map { it.action }.toSet())
    }

    @Test fun `simultaneous comment replies retain both stable comment ids`() {
        val engine = SemanticOpSetMergeEngine()
        val document = engine.createDocument("artifact", "a")
        document.applyRemote(remote("a", "d1", "reply-a", 1, "comment", "comment.insert", "thread", "comment-a"))
        document.applyRemote(remote("b", "d2", "reply-b", 1, "comment", "comment.insert", "thread", "comment-b"))
        assertEquals(setOf("comment-a", "comment-b"), document.canonicalOperations().mapNotNull { it.payload["itemId"]?.toString()?.trim('"') }.toSet())
    }

    @Test fun `concurrent moves choose one deterministic position register`() {
        val engine = SemanticOpSetMergeEngine()
        val a = engine.createDocument("artifact", "a")
        val b = engine.createDocument("artifact", "b")
        val first = remote("a", "d1", "move-a", 9, "flow", "block.move", "doc", "block-a")
        val second = remote("b", "d2", "move-b", 9, "flow", "block.move", "doc", "block-a")
        a.applyRemote(first); a.applyRemote(second)
        b.applyRemote(second); b.applyRemote(first)
        assertEquals(a.canonicalOperations(), b.canonicalOperations())
        assertEquals(1, a.canonicalOperations().size)
    }

    @Test fun `comment resolve reopen race converges on latest review state`() {
        val document = SemanticOpSetMergeEngine().createDocument("artifact", "a")
        document.applyRemote(remote("a", "d1", "resolve", 4, "comment", "thread.resolve", "thread-1", "thread-1"))
        document.applyRemote(remote("b", "d2", "reopen", 5, "comment", "thread.reopen", "thread-1", "thread-1"))
        assertEquals(listOf("thread.reopen"), document.canonicalOperations().map { it.action })
    }

    @Test fun `superseded operations compact without changing canonical revision`() {
        val engine = SemanticOpSetMergeEngine()
        val compacted = engine.createDocument("artifact", "a")
        val full = engine.createDocument("artifact", "b")
        val older = remote("a", "d1", "older", 1, "document", "object.property", "page", "object-a")
        val newer = remote("b", "d2", "newer", 2, "document", "object.property", "page", "object-a")
        compacted.applyRemote(newer)
        full.applyRemote(older)
        full.applyRemote(newer)
        assertEquals(compacted.currentRevision(), full.currentRevision())
        assertEquals(compacted.snapshot(), full.snapshot())
    }

    @Test fun `domain rejection leaves merge state untouched and operation retryable`() {
        val document = SemanticOpSetMergeEngine().createDocument("artifact", "a")
        val operation = remote("a", "d1", "retryable", 1, "flow", "block.insert", "doc", "block-a")
        val before = document.currentRevision()
        val rejected = document.integrateRemoteBatch(listOf(operation)) { false }
        assertEquals(com.norfold.app.collaboration.merge.RemoteBatchResult.Rejected("domain_invariant_rejected"), rejected)
        assertEquals(before, document.currentRevision())
        val applied = document.integrateRemoteBatch(listOf(operation)) { true }
        assertTrue(applied is com.norfold.app.collaboration.merge.RemoteBatchResult.Applied)
    }

    @Test fun `selected production engine is repository owned implementation`() {
        val selected = com.norfold.app.collaboration.merge.CollaborationMergeEngineFactory.create()
        assertEquals("norfold-semantic-opset-v1", selected.engineId)
        assertTrue(selected is SemanticOpSetMergeEngine)
    }

    private fun remote(actor: String, device: String, opId: String, clock: Long, domain: String, action: String, target: String, member: String) =
        RemoteOperation(opId, actor, "command-$opId", device, clock,
            SemanticMutation(domain = domain, action = action, targetId = target,
                payload = buildJsonObject { put("itemId", member); put("property", action) }))
}
