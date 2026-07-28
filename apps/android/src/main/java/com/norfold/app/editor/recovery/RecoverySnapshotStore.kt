package com.norfold.app.editor.recovery

/**
 * Durable-shaped store of [RecoverySnapshot]s (roadmap Phase 0 D3 / spec §13.7).
 *
 * Phase 0 provides the in-memory implementation ([InMemoryRecoverySnapshotStore]); a later phase can back
 * the same interface with encrypted on-disk storage without changing the [RecoverySnapshotManager]. The
 * store is deliberately dumb: it holds snapshots newest-last, enforces a bounded retention, and never
 * inspects payloads. Retention keeps recovery from growing without bound while always preserving the most
 * recent restore points (the ones a user is most likely to need).
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
        while (snapshots.size > capacity) snapshots.removeFirst()
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
