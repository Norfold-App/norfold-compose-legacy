package com.norfold.app.editor.recovery

/**
 * Store of [RecoverySnapshot]s (roadmap Phase 0 D3 / spec §13.7).
 *
 * Two implementations ship: [InMemoryRecoverySnapshotStore] for headless use and tests, and
 * [FileRecoverySnapshotStore] for the durable, app-private on-disk store that survives process death.
 * The store is deliberately dumb: it holds snapshots newest-last, enforces a bounded retention, and never
 * inspects payloads. Retention keeps recovery from growing without bound while always preserving the most
 * recent restore points (the ones a user is most likely to need) and every named one it can.
 */
interface RecoverySnapshotStore {
    /** Persists [snapshot], evicting the oldest if capacity is exceeded. */
    fun put(snapshot: RecoverySnapshot)

    /** All snapshots, oldest first. */
    fun all(): List<RecoverySnapshot>

    /** The snapshot with [id], or null if it was never stored or has been evicted. */
    fun get(id: String): RecoverySnapshot?

    /** The most recent snapshot, or null if the store is empty. */
    fun latest(): RecoverySnapshot?

    /** Removes the snapshot with [id]; returns true if it was present. */
    fun remove(id: String): Boolean

    /** Drops every stored snapshot. */
    fun clear()
}

/**
 * Bounded in-memory [RecoverySnapshotStore]. Retains at most [capacity] snapshots; adding beyond capacity
 * evicts the oldest. Not thread-safe (single-threaded editor loop, matching the History Engine).
 */
class InMemoryRecoverySnapshotStore(
    private val capacity: Int = DEFAULT_CAPACITY,
) : RecoverySnapshotStore {
    init {
        require(capacity > 0) { "Recovery snapshot store capacity must be positive" }
    }

    private val snapshots = ArrayDeque<RecoverySnapshot>()

    override fun put(snapshot: RecoverySnapshot) {
        // Replace an existing snapshot with the same id in place rather than duplicating it.
        val existing = snapshots.indexOfFirst { it.id == snapshot.id }
        if (existing >= 0) {
            snapshots[existing] = snapshot
            return
        }
        snapshots.addLast(snapshot)
        while (snapshots.size > capacity) evictOne()
    }

    /**
     * Evicts the oldest automatic snapshot, falling back to the oldest snapshot of any kind only once no
     * automatic one is left.
     *
     * Named restore points are what a user deliberately created, so a burst of automatic captures (an
     * import, a format conversion, a mass style replacement) must not silently push them out. The fallback
     * still exists because a store made entirely of named points has to stay bounded too.
     */
    private fun evictOne() {
        val automatic = snapshots.indexOfFirst { !it.isManual }
        snapshots.removeAt(if (automatic >= 0) automatic else 0)
    }

    override fun all(): List<RecoverySnapshot> = snapshots.toList()

    override fun get(id: String): RecoverySnapshot? = snapshots.firstOrNull { it.id == id }

    override fun latest(): RecoverySnapshot? = snapshots.lastOrNull()

    override fun remove(id: String): Boolean {
        val index = snapshots.indexOfFirst { it.id == id }
        if (index < 0) return false
        snapshots.removeAt(index)
        return true
    }

    override fun clear() = snapshots.clear()

    val size: Int get() = snapshots.size

    companion object {
        const val DEFAULT_CAPACITY = 20
    }
}
