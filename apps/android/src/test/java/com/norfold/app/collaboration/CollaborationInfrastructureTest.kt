package com.norfold.app.collaboration

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class CollaborationInfrastructureTest {

    // ── Envelope round-trip ──────────────────────────────────────────────────

    @Test fun `wire envelope round-trips through toWire and toInbound`() {
        val env = OutboundEnvelope(
            roomId = RoomId("bench:test"),
            actorId = ActorId("actor-1"),
            eventClass = CollabEventClass.EPHEMERAL_LATEST,
            eventType = "cursor.move",
            targetId = "obj-1",
            seq = CollabSeq(42),
            clientSentAt = 1_000_000L,
        )
        val wire = env.toWire()
        assertEquals("bench:test", wire.room)
        assertEquals("EPHEMERAL_LATEST", wire.eventClass)
        val inbound = wire.toInbound(serverAt = 1_000_050L)
        assertEquals(RoomId("bench:test"), inbound.roomId)
        assertEquals(CollabSeq(42), inbound.seq)
        assertEquals(CollabEventClass.EPHEMERAL_LATEST, inbound.eventClass)
    }

    @Test fun `wire envelope serializes and deserializes via JSON`() {
        val json = Json { ignoreUnknownKeys = true }
        val env = OutboundEnvelope(
            roomId = RoomId("bench:json"),
            actorId = ActorId("a"),
            eventClass = CollabEventClass.DURABLE,
            eventType = "text.insert",
            seq = CollabSeq(1),
        )
        val wire = env.toWire()
        val encoded = json.encodeToString(CollabWireEnvelope.serializer(), wire)
        val decoded = json.decodeFromString(CollabWireEnvelope.serializer(), encoded)
        assertEquals(wire.room, decoded.room)
        assertEquals(wire.eventClass, decoded.eventClass)
        assertEquals(wire.seq, decoded.seq)
    }

    // ── SequenceTracker ──────────────────────────────────────────────────────

    private fun makeInbound(actor: String, type: String, target: String?, seq: Long, cls: CollabEventClass) =
        InboundEnvelope(
            roomId = RoomId("r"), actorId = ActorId(actor), eventClass = cls,
            eventType = type, targetId = target, seq = CollabSeq(seq),
            clientSentAt = 0, serverAt = 0,
        )

    @Test fun `SequenceTracker accepts fresh events in order`() {
        val t = SequenceTracker()
        assertTrue(t.accept(makeInbound("a", "cursor", "o1", 1, CollabEventClass.EPHEMERAL_LATEST)))
        assertTrue(t.accept(makeInbound("a", "cursor", "o1", 2, CollabEventClass.EPHEMERAL_LATEST)))
        assertEquals(0L, t.duplicatesDropped)
    }

    @Test fun `SequenceTracker drops duplicate seq`() {
        val t = SequenceTracker()
        t.accept(makeInbound("a", "cursor", "o1", 5, CollabEventClass.EPHEMERAL_LATEST))
        assertFalse(t.accept(makeInbound("a", "cursor", "o1", 5, CollabEventClass.EPHEMERAL_LATEST)))
        assertFalse(t.accept(makeInbound("a", "cursor", "o1", 3, CollabEventClass.EPHEMERAL_LATEST)))
        assertEquals(2L, t.duplicatesDropped)
    }

    @Test fun `SequenceTracker records gap for DURABLE`() {
        val t = SequenceTracker()
        t.accept(makeInbound("a", "text.insert", null, 1, CollabEventClass.DURABLE))
        t.accept(makeInbound("a", "text.insert", null, 5, CollabEventClass.DURABLE)) // gap 2-4
        assertEquals(1L, t.gapsSeen)
    }

    @Test fun `SequenceTracker tracks actors independently`() {
        val t = SequenceTracker()
        assertTrue(t.accept(makeInbound("a", "cursor", "o1", 3, CollabEventClass.EPHEMERAL_LATEST)))
        assertTrue(t.accept(makeInbound("b", "cursor", "o1", 1, CollabEventClass.EPHEMERAL_LATEST)))
        assertEquals(0L, t.duplicatesDropped)
    }

    // ── ReconnectPolicy ──────────────────────────────────────────────────────

    @Test fun `ReconnectPolicy retries on Transient`() {
        val p = ReconnectPolicy(maxAttempts = 3)
        assertTrue(p.shouldRetry(0, DisconnectReason.Transient("err")))
        assertTrue(p.shouldRetry(2, DisconnectReason.Transient("err")))
        assertFalse(p.shouldRetry(3, DisconnectReason.Transient("err")))
    }

    @Test fun `ReconnectPolicy does not retry ClientClosed or ServerClosed`() {
        val p = ReconnectPolicy()
        assertFalse(p.shouldRetry(0, DisconnectReason.ClientClosed))
        assertFalse(p.shouldRetry(0, DisconnectReason.ServerClosed("4000")))
    }

    @Test fun `ReconnectPolicy delay is within cap`() {
        val p = ReconnectPolicy(baseMs = 100, capMs = 5000)
        repeat(20) { attempt ->
            val d = p.delayMs(attempt)
            assertTrue("delay $d at attempt $attempt exceeds cap", d <= 5000)
            assertTrue(d >= 0)
        }
    }

    // ── PreviewCoalescer ─────────────────────────────────────────────────────

    private fun outbound(actor: String, type: String, target: String, seq: Long, cls: CollabEventClass) =
        OutboundEnvelope(RoomId("bench:r"), ActorId(actor), cls, type, target, CollabSeq(seq))

    @Test fun `PreviewCoalescer passes DURABLE immediately`() {
        val c = PreviewCoalescer()
        val env = outbound("a", "text.insert", "t1", 1, CollabEventClass.DURABLE)
        val out = c.offer(env, 0)
        assertEquals(1, out.size)
        assertSame(env, out[0])
    }

    @Test fun `PreviewCoalescer buffers EPHEMERAL_LATEST and coalesces`() {
        val c = PreviewCoalescer()
        val profile = AdaptiveSyncProfile.default(CollabMode.SHARED_REGION)
        c.offer(outbound("a", "cursor", "o1", 1, CollabEventClass.EPHEMERAL_LATEST), 0)
        c.offer(outbound("a", "cursor", "o1", 2, CollabEventClass.EPHEMERAL_LATEST), 10)
        // Only the latest (seq=2) should be pending; seq=1 was replaced
        assertEquals(1L, c.previewsDropped)
        val flushed = c.poll(profile, profile.previewIntervalMs + 1)
        assertEquals(1, flushed.size)
        assertEquals(CollabSeq(2), flushed[0].seq)
    }

    @Test fun `PreviewCoalescer drops all previews when sendPreviews=false`() {
        val c = PreviewCoalescer()
        c.offer(outbound("a", "cursor", "o1", 1, CollabEventClass.EPHEMERAL_LATEST), 0)
        val soloProfile = AdaptiveSyncProfile.default(CollabMode.SOLO)
        val flushed = c.poll(soloProfile, 9999)
        assertEquals(0, flushed.size)
    }

    @Test fun `PreviewCoalescer respects rate limit`() {
        val c = PreviewCoalescer()
        val profile = AdaptiveSyncProfile(CollabMode.SHARED_REGION, previewHz = 10, textOpBatchWindowMs = 80, sendPreviews = true)
        c.offer(outbound("a", "cursor", "o1", 1, CollabEventClass.EPHEMERAL_LATEST), 0)
        val flushed1 = c.poll(profile, 50) // 50ms < 100ms interval
        assertEquals(0, flushed1.size)
        val flushed2 = c.poll(profile, 110) // 110ms >= 100ms interval
        assertEquals(1, flushed2.size)
    }

    // ── AdaptiveSyncController ───────────────────────────────────────────────

    @Test fun `AdaptiveSyncController starts in SOLO`() {
        val ctrl = AdaptiveSyncController()
        assertEquals(CollabMode.SOLO, ctrl.mode.value)
        assertFalse(ctrl.profile.value.sendPreviews)
    }

    @Test fun `AdaptiveSyncController escalates to CONTENDED_TARGET`() {
        val ctrl = AdaptiveSyncController()
        ctrl.update(CollabSituation(peerCount = 2, peersInSameRegion = 1, peersOnSameTarget = 1, deviceIsWeak = false, networkIsWeak = false))
        assertEquals(CollabMode.CONTENDED_TARGET, ctrl.mode.value)
        assertTrue(ctrl.profile.value.previewHz >= 12)
    }

    @Test fun `AdaptiveSyncController applies DEGRADED overlay`() {
        val ctrl = AdaptiveSyncController()
        ctrl.update(CollabSituation(peerCount = 2, peersInSameRegion = 1, peersOnSameTarget = 1, deviceIsWeak = true, networkIsWeak = false))
        assertEquals(CollabMode.DEGRADED, ctrl.mode.value)
        val degradedCeiling = AdaptiveSyncProfile.default(CollabMode.DEGRADED).previewHz
        assertTrue(ctrl.profile.value.previewHz <= degradedCeiling)
    }

    @Test fun `CollabModeResolver returns SOLO when no peers`() {
        val s = CollabSituation(0, 0, 0, false, false)
        assertEquals(CollabMode.SOLO, CollabModeResolver.contentionTier(s))
        assertFalse(CollabModeResolver.isDegraded(s))
    }

    // ── RemoteInterpolator ───────────────────────────────────────────────────

    @Test fun `RemoteInterpolator returns null before two samples`() {
        val ri = RemoteInterpolator(interpolationDelayMs = 80)
        ri.submit("a", 100, doubleArrayOf(10.0, 20.0))
        assertNull(ri.sample("a", 200))
    }

    @Test fun `RemoteInterpolator interpolates between two samples`() {
        val ri = RemoteInterpolator(interpolationDelayMs = 0)
        ri.submit("a", 0, doubleArrayOf(0.0, 0.0))
        ri.submit("a", 100, doubleArrayOf(100.0, 100.0))
        val result = ri.sample("a", 50)!!
        assertEquals(50.0, result[0], 1.0)
        assertEquals(50.0, result[1], 1.0)
    }

    @Test fun `RemoteInterpolator holds last known when ahead of samples`() {
        val ri = RemoteInterpolator(interpolationDelayMs = 0)
        ri.submit("a", 0, doubleArrayOf(5.0))
        ri.submit("a", 10, doubleArrayOf(10.0))
        val result = ri.sample("a", 9999)!! // far ahead — hold last
        assertEquals(10.0, result[0], 0.01)
    }

    // ── OfflineOutbox ────────────────────────────────────────────────────────

    @Test fun `OfflineOutbox reads DURABLE ops without removing before ack`() = runBlocking {
        val box = OfflineOutbox()
        val env = OutboundEnvelope(RoomId("bench:r"), ActorId("a"), CollabEventClass.DURABLE, "text.insert", null, CollabSeq(1))
        box.enqueue(env)
        assertEquals(1, box.size())
        val drained = box.drainAll()
        assertEquals(1, drained.size)
        assertEquals(1, box.size())
    }

    @Test fun `OfflineOutbox rejects non-DURABLE`() = runBlocking {
        val box = OfflineOutbox()
        val env = OutboundEnvelope(RoomId("bench:r"), ActorId("a"), CollabEventClass.EPHEMERAL_LATEST, "cursor", "o1", CollabSeq(1))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { box.enqueue(env) } }
        Unit
    }

    @Test fun `OfflineOutbox blocks safely without dropping oldest`() = runBlocking {
        val box = OfflineOutbox(maxSize = 3)
        for (i in 1..3) assertEquals(OutboxEnqueueResult.Enqueued, box.enqueue(OutboundEnvelope(RoomId("bench:r"), ActorId("a"), CollabEventClass.DURABLE, "op", null, CollabSeq(i.toLong()))))
        assertTrue(box.enqueue(OutboundEnvelope(RoomId("bench:r"), ActorId("a"), CollabEventClass.DURABLE, "op", null, CollabSeq(4))) is OutboxEnqueueResult.Blocked)
        val drained = box.drainAll()
        assertEquals(3, drained.size)
        assertEquals(CollabSeq(1), drained[0].seq)
    }

    // ── NoOpMergeEngine ──────────────────────────────────────────────────────

    @Test fun `NoOpMergeEngine creates document and tracks revision`() {
        val engine = com.norfold.app.collaboration.merge.NoOpMergeEngine()
        val doc = engine.createDocument("art-1", "actor-1")
        assertEquals(com.norfold.app.collaboration.merge.Revision.EMPTY, doc.currentRevision())
        val op = com.norfold.app.collaboration.merge.LocalOperation(
            opId = "op-1", actorId = "actor-1", commandId = "cmd-1",
            op = com.norfold.app.collaboration.protocol.SemanticMutation(domain = "flow", action = "block.replace", targetId = "t1",
                payload = kotlinx.serialization.json.buildJsonObject { put("k", "v") })
        )
        doc.applyLocal(op)
        assertNotEquals(com.norfold.app.collaboration.merge.Revision.EMPTY, doc.currentRevision())
    }

    @Test fun `NoOpMergeEngine is idempotent for remote ops`() {
        val engine = com.norfold.app.collaboration.merge.NoOpMergeEngine()
        val doc = engine.createDocument("art-1", "actor-1")
        val op = com.norfold.app.collaboration.merge.RemoteOperation(
            opId = "op-r1", actorId = "actor-2", commandId = "cmd-2",
            op = com.norfold.app.collaboration.protocol.SemanticMutation(domain = "flow", action = "block.replace", targetId = "t1",
                payload = kotlinx.serialization.json.buildJsonObject { put("k", "v") })
        )
        doc.applyRemote(op)
        val rev1 = doc.currentRevision()
        doc.applyRemote(op) // duplicate
        assertEquals(rev1, doc.currentRevision())
    }

    @Test fun `NoOpMergeEngine snapshot round-trips`() {
        val engine = com.norfold.app.collaboration.merge.NoOpMergeEngine()
        val doc = engine.createDocument("art-1", "actor-1")
        val op = com.norfold.app.collaboration.merge.LocalOperation(
            opId = "op-1", actorId = "actor-1", commandId = "cmd-1",
            op = com.norfold.app.collaboration.protocol.SemanticMutation(domain = "flow", action = "block.replace", targetId = "t1",
                payload = kotlinx.serialization.json.buildJsonObject { put("k", "v") })
        )
        doc.applyLocal(op)
        val snap = doc.snapshot()
        val doc2 = engine.loadDocument("art-1", "actor-1", snap)
        assertEquals(doc.currentRevision(), doc2.currentRevision())
    }

    // ── CollabCapabilities ───────────────────────────────────────────────────

    @Test fun `CollabCapabilities BETA defaults are sane`() {
        val caps = CollabCapabilities.BETA
        assertTrue(caps.enabled)
        assertEquals(4, caps.maxParticipants)
        assertTrue(caps.liveCursors)
        assertFalse(caps.comments)
    }

    @Test fun `CollabCapabilities DISABLED disables everything`() {
        assertFalse(CollabCapabilities.DISABLED.enabled)
        assertFalse(CollabCapabilities.DISABLED.liveCursors)
    }
}
