package com.norfold.app.collaboration

import com.norfold.app.collaboration.merge.MergeSnapshotCodec
import com.norfold.app.collaboration.merge.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MergeSnapshotCodecTest {
    @Test fun `merge snapshot round trips without provider coupling`() {
        val expected = Snapshot("norfold-semantic-opset-v1", byteArrayOf(1, 2, 3, 4))
        assertEquals(expected, MergeSnapshotCodec.decode(MergeSnapshotCodec.encode(expected)))
    }

    @Test fun `merge snapshot decoder enforces decoded byte bound`() {
        val payload = MergeSnapshotCodec.encode(Snapshot("engine", ByteArray(128)))
        assertThrows(IllegalArgumentException::class.java) { MergeSnapshotCodec.decode(payload, maxBytes = 32) }
    }
}
