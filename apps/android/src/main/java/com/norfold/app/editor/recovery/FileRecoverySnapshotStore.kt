package com.norfold.app.editor.recovery

import java.io.File

/**
 * Lightweight description of a stored restore point, readable without decoding its payloads.
 *
 * The recovery browser lists these. Listing must not cost one full document decode per row, and it must
 * never need the payload in memory just to draw a title — so everything a row shows lives here.
 */
data class RecoverySnapshotSummary(
    val id: String,
    val reason: RecoveryReason,
    val createdAt: Long,
    val label: String,
    val name: String?,
    val description: String,
    val targetIds: List<String>,
    val scopeBlockIds: List<String>,
    val sizeBytes: Long,
    /** True when the record on disk could not be read; it is listed as damaged rather than hidden. */
    val damaged: Boolean = false,
    /** True when the record was written by a newer build and this one must not interpret it. */
    val unsupported: Boolean = false,
) {
    val isManual: Boolean get() = reason.isManual

    fun displayName(): String = name?.takeIf(String::isNotBlank)
        ?: label.takeIf(String::isNotBlank)
        ?: reason.defaultLabel
}

/**
 * How much recovery data may accumulate (spec §13.7; Phase-10 §19).
 *
 * Manual and automatic snapshots are budgeted **separately and deliberately**. Automatic snapshots are
 * produced by the app, often several per session, and losing an old one costs nothing. A named restore
 * point is something a person chose to create. A single shared budget would let a burst of imports quietly
 * evict every named point the user was relying on, which is the failure this split exists to prevent.
 */
data class RecoveryRetentionPolicy(
    val maxAutomatic: Int = DEFAULT_MAX_AUTOMATIC,
    val maxManual: Int = DEFAULT_MAX_MANUAL,
    val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
) {
    init {
        require(maxAutomatic > 0) { "Automatic retention must keep at least one snapshot" }
        require(maxManual > 0) { "Manual retention must keep at least one snapshot" }
        require(maxTotalBytes > 0) { "Recovery storage budget must be positive" }
    }

    companion object {
        const val DEFAULT_MAX_AUTOMATIC = 12
        const val DEFAULT_MAX_MANUAL = 24
        const val DEFAULT_MAX_TOTAL_BYTES = 24L * 1024 * 1024
    }
}

/**
 * Whether a stored record can be read by this build, without saying anything about what is in it.
 *
 * The browser needs this per row to mark a restore point damaged; it must never need the payload to do so.
 */
enum class RecoveryRecordHealth {
    HEALTHY,

    /** Missing, unreadable, or failed integrity verification. */
    DAMAGED,

    /** Written by a newer build; kept untouched rather than guessed at. */
    UNSUPPORTED,
}

/**
 * A [RecoverySnapshotStore] that survives process death, backed by one file per snapshot in an
 * app-private directory (spec §13.7; Phase-10 §17–§19).
 *
 * Durability details that matter:
 *
 *  - **Atomic replace.** A record is written to `<id>.part` and renamed over `<id>.snapshot`. A kill
 *    mid-write leaves the temp file, never a half-written record where a whole one used to be.
 *  - **Payload first, index last.** The index is only updated once the payload is safely on disk, and is
 *    itself written the same way. A crash between the two leaves an unindexed payload rather than an index
 *    entry pointing at nothing.
 *  - **The records are the truth; the index is a cache.** A record on disk that the index does not mention
 *    is adopted back on the next load, so losing or corrupting the index costs a directory scan and not a
 *    single restore point. [pruneOrphans] sweeps only what adoption could not rescue.
 *  - **Verified on read.** Every decode checks the record's SHA-256. A damaged record is reported as
 *    damaged and refused, never silently restored as truncated content.
 *  - **No crypto, no database, no network.** This class takes a plain [File] and nothing else. Protection
 *    comes from the app-private sandbox the directory lives in; adding a cipher here would mean inventing
 *    a key-management story this phase has no authority to invent.
 *
 * Pure JVM by construction — the Android wiring supplies `context.filesDir`, which keeps the whole
 * durability story unit-testable against a real filesystem.
 */
class FileRecoverySnapshotStore(
    private val directory: File,
    private val retention: RecoveryRetentionPolicy = RecoveryRetentionPolicy(),
) : RecoverySnapshotStore {

    /** In-memory mirror of the on-disk index, keyed by id and kept oldest-first by creation time. */
    private val index = LinkedHashMap<String, RecoverySnapshotSummary>()
    private var loaded = false

    /**
     * Memoized read verdicts, so listing does not re-verify every payload on every rebuild.
     *
     * Deciding whether a row is damaged requires reading that record, and the browser rebuilds its list on
     * every filter change, selection and scroll. Without this cache a single filter tap would re-read and
     * re-hash the entire recovery directory — up to the whole storage budget — which is the opposite of the
     * cheap listing the index exists to provide. An entry is invalidated when the file's size or timestamp
     * moves, so a record replaced or damaged underneath us is re-verified rather than remembered.
     */
    private val health = HashMap<String, HealthEntry>()

    private data class HealthEntry(val length: Long, val modifiedAt: Long, val health: RecoveryRecordHealth)

    override fun put(snapshot: RecoverySnapshot) {
        ensureLoaded()
        // Encoded to bytes once, and measured in bytes: the retention budget and everything reported to the
        // user are byte figures, while the encoded string's length is a UTF-16 char count. For a note in a
        // non-Latin script the two differ by a factor of three, and taking the char count would let recovery
        // sit well past the cap it advertises with every internal check agreeing it was inside it.
        val bytes = RecoverySnapshotCodec.encode(snapshot).toByteArray(Charsets.UTF_8)
        if (!writeAtomically(fileFor(snapshot.id), bytes)) return

        index[snapshot.id] = summaryOf(snapshot, bytes.size.toLong())
        health.remove(snapshot.id)
        prune(protecting = snapshot.id)
        if (!writeIndex()) {
            // The index is the record of truth for listing; without it the payload would be an orphan.
            fileFor(snapshot.id).delete()
            index.remove(snapshot.id)
        }
    }

    override fun all(): List<RecoverySnapshot> {
        ensureLoaded()
        return orderedSummaries().mapNotNull { read(it.id) as? RecoveryDecodeResult.Success }.map { it.snapshot }
    }

    override fun get(id: String): RecoverySnapshot? =
        (read(id) as? RecoveryDecodeResult.Success)?.snapshot

    override fun latest(): RecoverySnapshot? {
        ensureLoaded()
        return orderedSummaries().asReversed()
            .firstNotNullOfOrNull { (read(it.id) as? RecoveryDecodeResult.Success)?.snapshot }
    }

    override fun remove(id: String): Boolean {
        ensureLoaded()
        if (index.remove(id) == null && !fileFor(id).exists()) return false
        health.remove(id)
        fileFor(id).delete()
        writeIndex()
        return true
    }

    override fun clear() {
        ensureLoaded()
        index.keys.toList().forEach { fileFor(it).delete() }
        index.clear()
        health.clear()
        writeIndex()
    }

    /** Every stored restore point, oldest first, without decoding any payload. */
    fun summaries(): List<RecoverySnapshotSummary> {
        ensureLoaded()
        return orderedSummaries()
    }

    /**
     * Reads and verifies one record.
     *
     * Returns [RecoveryDecodeResult.Corrupt] for a missing, unreadable or checksum-mismatched record, and
     * [RecoveryDecodeResult.UnsupportedVersion] for one a newer build wrote. Neither case deletes anything:
     * a record this build cannot read may still be readable by the build that wrote it.
     */
    fun read(id: String): RecoveryDecodeResult {
        ensureLoaded()
        val file = fileFor(id)
        if (!file.isFile) return RecoveryDecodeResult.Corrupt("Recovery record is missing")
        val raw = runCatching { file.readText(Charsets.UTF_8) }
            .getOrElse { return RecoveryDecodeResult.Corrupt("Recovery record could not be read") }
        return RecoverySnapshotCodec.decode(raw)
    }

    /** Total bytes currently occupied by stored records, for honest storage reporting. */
    fun storageBytes(): Long {
        ensureLoaded()
        return index.values.sumOf { it.sizeBytes }
    }

    /**
     * Whether the record for [id] can be read, verified once and remembered until the file changes.
     *
     * This is what a list should ask. [read] returns the decoded snapshot and therefore costs a full parse
     * and hash every time; a browser that called it per row per rebuild would turn opening a filter into a
     * pass over every byte of recovery storage.
     */
    fun healthOf(id: String): RecoveryRecordHealth {
        ensureLoaded()
        val file = fileFor(id)
        val length = file.length()
        val modifiedAt = file.lastModified()
        health[id]?.let { if (it.length == length && it.modifiedAt == modifiedAt) return it.health }
        val verdict = when (read(id)) {
            is RecoveryDecodeResult.Success -> RecoveryRecordHealth.HEALTHY
            is RecoveryDecodeResult.UnsupportedVersion -> RecoveryRecordHealth.UNSUPPORTED
            is RecoveryDecodeResult.Corrupt -> RecoveryRecordHealth.DAMAGED
        }
        health[id] = HealthEntry(length, modifiedAt, verdict)
        return verdict
    }

    /**
     * Deletes write residue and record files that cannot be rescued.
     *
     * `.part` files are always residue — a kill mid-write, never data. A record file that is still
     * unreferenced after [adoptUnindexedRecords] has run is one this build could not decode, and only
     * demonstrable damage is swept: a record written by a **newer** build is perfectly readable by the build
     * that wrote it, so downgrading once must not be what destroys it.
     *
     * "Referenced" is decided by comparing file *names*, not by turning a file name back into an id. The
     * reverse mapping does not exist — [storageName] is deliberately lossy — so reconstructing an id from a
     * name would classify every record whose id needed rewriting as an orphan and delete live restore
     * points on the next sweep.
     */
    fun pruneOrphans(): Int {
        ensureLoaded()
        val referenced = index.keys.mapTo(HashSet()) { fileFor(it).name }
        val files = directory.listFiles().orEmpty()
        var removed = 0
        for (file in files) {
            if (file.name == INDEX_FILE) continue
            if (file.name.endsWith(PART_SUFFIX)) {
                if (file.delete()) removed++
                continue
            }
            if (!file.name.endsWith(RECORD_SUFFIX)) continue
            if (file.name in referenced) continue
            val raw = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
            val damaged = raw == null || RecoverySnapshotCodec.decode(raw) is RecoveryDecodeResult.Corrupt
            if (damaged && file.delete()) removed++
        }
        return removed
    }

    // ── internals ────────────────────────────────────────────────────────────────────────────────────

    private fun orderedSummaries(): List<RecoverySnapshotSummary> = index.values.sortedBy { it.createdAt }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val file = File(directory, INDEX_FILE)
        if (file.isFile) {
            val raw = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
            if (raw != null) {
                RecoveryIndexCodec.decode(raw).forEach { summary ->
                    // An index entry whose payload vanished would be a row that cannot be restored; drop it.
                    if (fileFor(summary.id).isFile) index[summary.id] = summary
                }
            }
        }
        adoptUnindexedRecords()
    }

    /**
     * Takes ownership of readable records the index does not mention.
     *
     * Two things produce them: a crash between the payload write and the index write, and an index file that
     * was itself lost or damaged. Treating either as litter would mean the user loses restore points that
     * are sitting intact on disk — so the record wins over the index, and the index is rewritten to match.
     *
     * A record whose id does not map back to its own file name is skipped: it could not be reached by id
     * afterwards, and adopting it would let the next write with the real id silently overwrite it.
     */
    private fun adoptUnindexedRecords() {
        val referenced = index.keys.mapTo(HashSet()) { fileFor(it).name }
        val strays = directory.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(RECORD_SUFFIX) && it.name !in referenced }
        if (strays.isEmpty()) return
        var adopted = false
        for (stray in strays) {
            val raw = runCatching { stray.readText(Charsets.UTF_8) }.getOrNull() ?: continue
            val snapshot = (RecoverySnapshotCodec.decode(raw) as? RecoveryDecodeResult.Success)?.snapshot
                ?: continue
            if (fileFor(snapshot.id).name != stray.name) continue
            // The file's own length, for the same reason `put` counts bytes: an adopted record must not be
            // budgeted differently from the identical record written normally.
            index[snapshot.id] = summaryOf(snapshot, stray.length())
            adopted = true
        }
        if (adopted) writeIndex()
    }

    private fun summaryOf(snapshot: RecoverySnapshot, sizeBytes: Long) = RecoverySnapshotSummary(
        id = snapshot.id,
        reason = snapshot.reason,
        createdAt = snapshot.createdAt,
        label = snapshot.label,
        name = snapshot.name,
        description = snapshot.description,
        targetIds = snapshot.documents.keys.sorted(),
        scopeBlockIds = snapshot.scopeBlockIds,
        sizeBytes = sizeBytes,
    )

    /**
     * Enforces [retention], never evicting [protecting] (the record just written).
     *
     * Order is the policy: count limits first within each class, then the byte budget, and the byte budget
     * exhausts every automatic snapshot before it touches a single named one.
     *
     * The counts include the record just written — it occupies a slot like any other — while the eviction
     * candidates exclude it. Counting only the candidates would let each class keep one more than its stated
     * budget forever, which is a limit that quietly is not the limit.
     */
    private fun prune(protecting: String) {
        val evict = LinkedHashSet<String>()

        fun ofClass(manual: Boolean) = orderedSummaries()
            .filter { it.isManual == manual && it.id !in evict }

        fun candidates(manual: Boolean) = ofClass(manual).filter { it.id != protecting }

        val automaticOverflow = ofClass(manual = false).size - retention.maxAutomatic
        if (automaticOverflow > 0) evict += candidates(manual = false).take(automaticOverflow).map { it.id }

        val manualOverflow = ofClass(manual = true).size - retention.maxManual
        if (manualOverflow > 0) evict += candidates(manual = true).take(manualOverflow).map { it.id }

        fun remaining() = index.values.filter { it.id !in evict }.sumOf { it.sizeBytes }
        if (remaining() > retention.maxTotalBytes) {
            for (summary in candidates(manual = false)) {
                if (remaining() <= retention.maxTotalBytes) break
                evict += summary.id
            }
        }
        if (remaining() > retention.maxTotalBytes) {
            for (summary in candidates(manual = true)) {
                if (remaining() <= retention.maxTotalBytes) break
                evict += summary.id
            }
        }

        evict.forEach {
            index.remove(it)
            health.remove(it)
            fileFor(it).delete()
        }
    }

    private fun fileFor(id: String) = File(directory, "${storageName(id)}$RECORD_SUFFIX")

    /**
     * The file name for [id]: sanitized, and disambiguated when sanitizing changed it.
     *
     * [sanitize] is lossy — `a/b` and `a:b` both reduce to `a_b` — and two index entries resolving to one
     * file is the worst failure this class has: the second write silently overwrites the first, and
     * restoring the first restore point then produces content belonging to a different one. Ids are UUIDs in
     * production and pay nothing for this; anything that needed rewriting carries a discriminator so
     * distinct ids keep distinct files.
     */
    private fun storageName(id: String): String {
        val safe = sanitize(id)
        return if (safe == id) safe else "$safe-${Integer.toHexString(id.hashCode()).padStart(8, '0')}"
    }

    /**
     * Keeps an id from escaping the recovery directory.
     *
     * Ids are generated internally, but a file name derived from data is exactly the place where a stray
     * separator turns into a write outside the sandbox, so the constraint is enforced rather than assumed.
     */
    private fun sanitize(id: String): String =
        id.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")

    private fun writeIndex(): Boolean =
        writeAtomically(File(directory, INDEX_FILE), RecoveryIndexCodec.encode(orderedSummaries()).toByteArray(Charsets.UTF_8))

    /**
     * Writes [bytes] to [target] so that a kill leaves either the old content or the new one, never neither.
     *
     * The rename is attempted **before** any delete. On this platform rename replaces an existing file
     * atomically, so deleting the target first would buy nothing and would open the one window this method
     * exists to close: a process killed between the delete and the rename has destroyed the previous record
     * and put nothing in its place, and the `.part` file left behind is swept as residue by [pruneOrphans].
     * The delete stays only as a fallback for a filesystem whose rename refuses to clobber — there the
     * window is unavoidable, and losing the ability to write at all would be the worse outcome.
     */
    private fun writeAtomically(target: File, bytes: ByteArray): Boolean = runCatching {
        directory.mkdirs()
        val temp = File(target.parentFile, "${target.name}$PART_SUFFIX")
        temp.writeBytes(bytes)
        if (temp.renameTo(target)) return@runCatching true
        if (target.exists() && target.delete() && temp.renameTo(target)) return@runCatching true
        temp.delete()
        false
    }.getOrDefault(false)

    companion object {
        const val INDEX_FILE = "index.json"
        const val RECORD_SUFFIX = ".snapshot"
        const val PART_SUFFIX = ".part"
    }
}
