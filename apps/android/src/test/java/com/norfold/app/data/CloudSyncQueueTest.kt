package com.norfold.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CloudSyncQueueTest {
    @Test
    fun retryDelayUsesBoundedExponentialBackoff() {
        assertEquals(15_000L, CloudSyncQueue.retryDelayMs(0))
        assertEquals(30_000L, CloudSyncQueue.retryDelayMs(1))
        assertEquals(480_000L, CloudSyncQueue.retryDelayMs(5))
        assertEquals(6L * 60L * 60L * 1_000L, CloudSyncQueue.retryDelayMs(20))
    }
}
