package com.norfold.app.editor.recovery

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.BlockDocumentJson
import com.norfold.app.domain.InlineText
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.editor.history.EditTarget
import com.norfold.app.editor.history.InMemoryEditWorkspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Phase-10 coverage for named restore points and the durable on-disk store (spec §13.7; §37 items 30–48).
 *
 * The claim under test is that a restore point survives the process that wrote it. Every durability test
 * therefore does the same thing: one store instance writes, that instance is *discarded*, and a second
 * instance built over the same directory has to answer from what is actually on disk. A test that kept using
 * the first instance would be proving that a `HashMap` remembers things.
 *
 * The second claim is that recovery data is bounded and honest about it — and that bounding it never costs
 * the user a point they deliberately named.
 *
 * Pure JVM against a real filesystem: no Android, no database, no crypto, no network.
 */
class RecoveryDurabilityPhase10Test {

    private val noteA = EditTarget("note:A")
    private val noteB = EditTarget("note:B")

    private fun doc(text: String) =
        BlockDocument(listOf(ParagraphBlock(id = "p1", content = listOf(InlineText(text)))))

    private fun payload(text: String) = BlockDocumentJson.encodeDocumentPayload(doc(text))

    /** Runs [body] against a fresh temp directory that is removed however the test ends. */
    private fun withDirectory(body: (File) -> Unit) {
        val root = Files.createTempDirectory("norfold-recovery").toFile()
        try {
            body(root)
        } finally {
            root.deleteRecursively()
        }
    }

    /** A snapshot built directly, so store-level tests control ids, times and sizes exactly. */
    private fun record(
        id: String,
        createdAt: Long,
        reason: RecoveryReason = RecoveryReason.DESTRUCTIVE_EDIT,
        name: String? = null,
        text: String = "x",
        targets: List<EditTarget> = listOf(noteA),
    ) = RecoverySnapshot(
        id = id,
        reason = reason,
        createdAt = createdAt,
        documents = targets.associate { it.id to payload(text) },
        name = name,
    )

    private fun named(id: String, createdAt: Long, name: String = "Keep me") =
        record(id, createdAt, reason = RecoveryReason.MANUAL_CHECKPOINT, name = name)

    private class Clock(private var now: Long = 1_000L) {
        fun next(): Long = now++
    }

    private class Ids {
        private var n = 0
        fun next(): String = "snap-${n++}"
    }

    private fun manager(
        workspace: InMemoryEditWorkspace,
        store: RecoverySnapshotStore = InMemoryRecoverySnapshotStore(),
    ) = RecoverySnapshotManager(workspace, store, clock = Clock()::next, idGenerator = Ids()::next)

    private fun workspace(vararg entries: Pair<EditTarget, String>) = InMemoryEditWorkspace(
        entries.associate { (target, text) -> target to doc(text) },
    )

    private fun files(directory: File) = directory.listFiles().orEmpty().map { it.name }.sorted()

    // --- Named restore points (§13.7) -----------------------------------------------------------------

    @Test fun aBlankNameIsRefusedRatherThanBecomingAnUnnamedPoint() {
        val store = InMemoryRecoverySnapshotStore()
        val manager = manager(workspace(noteA to "a"), store)

        assertNull(manager.createNamedSnapshot(""))
        assertNull(manager.createNamedSnapshot("   "))
        assertNull(manager.createNamedSnapshot("\t\n "))
        assertTrue("A refused name must not leave a restore point behind", store.all().isEmpty())
    }

    @Test fun aNamedPointCarriesTheUsersOwnWordsTrimmedAndIsMarkedManual() {
        val manager = manager(workspace(noteA to "a"))
        val point = manager.createNamedSnapshot("  Before the rewrite  ", "  in case the tables break  ")!!

        assertEquals("Before the rewrite", point.name)
        assertEquals("in case the tables break", point.description)
        assertEquals(RecoveryReason.MANUAL_CHECKPOINT, point.reason)
        assertTrue(point.isManual)
        assertEquals("Before the rewrite", point.displayName())
    }

    @Test fun aPastedEssayCannotBecomeARestorePointTitle() {
        val manager = manager(workspace(noteA to "a"))
        val point = manager.createNamedSnapshot("n".repeat(500), "d".repeat(2_000))!!

        assertEquals(RecoverySnapshotManager.MAX_NAME_LENGTH, point.name!!.length)
        assertEquals(RecoverySnapshotManager.MAX_DESCRIPTION_LENGTH, point.description.length)
    }

    @Test fun aPointIsNamedByTheUserThenByItsLabelThenByItsReason() {
        val manager = manager(workspace(noteA to "a"))

        assertEquals(
            "Before migration",
            manager.snapshot(RecoveryReason.MIGRATION, listOf(noteA)).displayName(),
        )
        assertEquals(
            "Before importing three files",
            manager.snapshot(RecoveryReason.IMPORT, listOf(noteA), label = "Before importing three files")
                .displayName(),
        )
        assertEquals(
            "A name the user chose outranks both",
            "My checkpoint",
            manager.snapshot(
                RecoveryReason.MANUAL_CHECKPOINT,
                listOf(noteA),
                label = "ignored",
                name = "My checkpoint",
            ).displayName(),
        )
    }

    @Test fun aNamedPointOverNoTargetsIsRefusedInsteadOfCapturingNothing() {
        val manager = manager(InMemoryEditWorkspace())
        assertNull("There is nothing to protect, so there is no restore point", manager.createNamedSnapshot("x"))
    }

    @Test fun aScopedNamedPointNarrowsWhatARestoreWritesNotWhatWasRecorded() {
        val workspace = InMemoryEditWorkspace(
            mapOf(
                noteA to BlockDocument(
                    listOf(
                        ParagraphBlock(id = "p1", content = listOf(InlineText("one"))),
                        ParagraphBlock(id = "p2", content = listOf(InlineText("two"))),
                    ),
                ),
            ),
        )
        val point = manager(workspace).createNamedSnapshot("Just the second", scopeBlockIds = listOf("p2"))!!

        assertTrue(point.isScoped)
        assertEquals(listOf("p2"), point.scopeBlockIds)
        val captured = BlockDocumentJson.decodeDocumentPayload(point.documents.getValue(noteA.id))
        assertEquals("The whole document is captured; the scope only narrows the write-back", 2, captured.blocks.size)
    }

    @Test fun aNamedPointSpansEveryKnownTargetByDefault() {
        val manager = manager(workspace(noteA to "a", noteB to "b"))
        val point = manager.createNamedSnapshot("Everything")!!
        assertEquals(setOf(noteA.id, noteB.id), point.documents.keys)
    }

    @Test fun aNamedPointIsAnOrdinaryRestorePointOnEveryOtherPath() {
        val workspace = workspace(noteA to "before")
        val manager = manager(workspace)
        val point = manager.createNamedSnapshot("Checkpoint")!!

        workspace.setDocument(noteA, doc("after"))
        assertEquals(setOf(noteA), manager.restore(point))
        assertEquals("before", workspace.documentOf(noteA).plainText())
    }

    // --- Durability across process death --------------------------------------------------------------

    @Test fun aRestorePointOutlivesTheProcessThatWroteIt() = withDirectory { directory ->
        val written = record("snap-1", createdAt = 1_000L, text = "captured")
        FileRecoverySnapshotStore(directory).put(written)

        // Everything the first instance knew is discarded here. The second answers from disk alone.
        val reopened = FileRecoverySnapshotStore(directory)

        assertEquals(written, reopened.get("snap-1"))
        assertEquals(listOf("snap-1"), reopened.summaries().map { it.id })
        assertEquals("snap-1", reopened.latest()?.id)
        assertEquals(1, reopened.all().size)
    }

    @Test fun aRecordIsRenamedIntoPlaceSoAKillMidWriteCannotHalfReplaceOne() = withDirectory { directory ->
        val store = FileRecoverySnapshotStore(directory)
        store.put(record("snap-1", createdAt = 1_000L, text = "first"))
        store.put(record("snap-1", createdAt = 1_000L, text = "second"))

        assertEquals(
            "One record, one index, and no write residue",
            listOf("index.json", "snap-1.snapshot"),
            files(directory),
        )
        val reopened = FileRecoverySnapshotStore(directory)
        assertEquals(payload("second"), reopened.get("snap-1")?.documents?.getValue(noteA.id))
        assertEquals("Rewriting an id replaces it rather than duplicating it", 1, reopened.summaries().size)
    }

    @Test fun aRecordTheIndexNeverHeardOfIsAdoptedRatherThanLost() = withDirectory { directory ->
        // Exactly what a crash between the payload write and the index write leaves behind.
        val stray = record("snap-9", createdAt = 2_000L, text = "survivor")
        File(directory, "snap-9.snapshot").writeText(RecoverySnapshotCodec.encode(stray), Charsets.UTF_8)

        val store = FileRecoverySnapshotStore(directory)

        assertEquals(listOf("snap-9"), store.summaries().map { it.id })
        assertEquals(stray, store.get("snap-9"))
        assertTrue("Adoption rewrites the index it repaired", File(directory, "index.json").isFile)
        assertEquals("Nothing readable is residue", 0, store.pruneOrphans())
    }

    @Test fun aLostIndexCostsADirectoryScanAndNotASingleRestorePoint() = withDirectory { directory ->
        val store = FileRecoverySnapshotStore(directory)
        store.put(record("snap-1", createdAt = 1_000L))
        store.put(named("snap-2", createdAt = 1_001L, name = "Important"))

        File(directory, "index.json").writeText("this is not an index", Charsets.UTF_8)
        val reopened = FileRecoverySnapshotStore(directory)

        assertEquals(listOf("snap-1", "snap-2"), reopened.summaries().map { it.id }.sorted())
        assertEquals("Important", reopened.summaries().first { it.id == "snap-2" }.displayName())
        assertTrue(reopened.summaries().first { it.id == "snap-2" }.isManual)
    }

    @Test fun anIndexEntryWhoseRecordVanishedIsDroppedInsteadOfShownAsARow() = withDirectory { directory ->
        val store = FileRecoverySnapshotStore(directory)
        store.put(record("snap-1", createdAt = 1_000L))
        store.put(record("snap-2", createdAt = 1_001L))
        assertTrue(File(directory, "snap-1.snapshot").delete())

        val reopened = FileRecoverySnapshotStore(directory)
        assertEquals(
            "A row that cannot be restored is worse than no row",
            listOf("snap-2"),
            reopened.summaries().map { it.id },
        )
    }

    // --- Integrity ------------------------------------------------------------------------------------

    @Test fun aDamagedRecordIsRefusedRatherThanRestoredAsTruncatedContent() = withDirectory { directory ->
        FileRecoverySnapshotStore(directory).put(record("snap-1", createdAt = 1_000L))
        File(directory, "snap-1.snapshot").writeText(
            """{"schemaVersion":1,"id":"snap-1","reason":"MIGRATION","createdAt":1000,""" +
                """"documents":{"note:A":"[]"},"checksum":"0000000000000000"}""",
            Charsets.UTF_8,
        )

        val store = FileRecoverySnapshotStore(directory)
        val read = store.read("snap-1")

        assertTrue(read is RecoveryDecodeResult.Corrupt)
        assertNull("A record that failed verification is never handed out", store.get("snap-1"))
        assertTrue(store.all().isEmpty())
        assertEquals(
            "It is still listed, because a point that silently disappears reads as one never taken",
            listOf("snap-1"),
            store.summaries().map { it.id },
        )
    }

    @Test fun anUnreadableFileIsDamageAndNotAnException() = withDirectory { directory ->
        FileRecoverySnapshotStore(directory).put(record("snap-1", createdAt = 1_000L))
        File(directory, "snap-1.snapshot").writeText("{ truncated", Charsets.UTF_8)

        assertTrue(FileRecoverySnapshotStore(directory).read("snap-1") is RecoveryDecodeResult.Corrupt)
        assertTrue(FileRecoverySnapshotStore(directory).read("never-written") is RecoveryDecodeResult.Corrupt)
    }

    @Test fun aRecordFromANewerBuildIsReportedAndLeftExactlyWhereItIs() = withDirectory { directory ->
        File(directory, "snap-1.snapshot").writeText(
            """{"schemaVersion":99,"id":"snap-1","reason":"MIGRATION","createdAt":1000,""" +
                """"documents":{"note:A":"[]"},"checksum":""}""",
            Charsets.UTF_8,
        )

        val store = FileRecoverySnapshotStore(directory)
        assertEquals(RecoveryDecodeResult.UnsupportedVersion(99), store.read("snap-1"))
        assertEquals(RecoveryRecordHealth.UNSUPPORTED, store.healthOf("snap-1"))
        assertEquals("Sweeping is not for records a newer build can still read", 0, store.pruneOrphans())
        assertTrue(File(directory, "snap-1.snapshot").isFile)
    }

    @Test fun aHealthVerdictIsRememberedButNotBelievedAfterTheFileMoves() = withDirectory { directory ->
        val store = FileRecoverySnapshotStore(directory)
        store.put(record("snap-1", createdAt = 1_000L))

        assertEquals(RecoveryRecordHealth.HEALTHY, store.healthOf("snap-1"))
        assertEquals("Repeating the question costs nothing and answers the same", RecoveryRecordHealth.HEALTHY, store.healthOf("snap-1"))

        File(directory, "snap-1.snapshot").writeText("{ ruined", Charsets.UTF_8)
        assertEquals(
            "A remembered verdict must not outlive the file it was about",
            RecoveryRecordHealth.DAMAGED,
            store.healthOf("snap-1"),
        )
    }

    @Test fun aRowIsDrawnFromTheIndexAndNotFromDecodingTheRecord() = withDirectory { directory ->
        val store = FileRecoverySnapshotStore(directory)
        store.put(named("snap-1", createdAt = 1_000L, name = "Named point"))
        // Undecodable by this build, so anything the summary still knows came from the index.
        File(directory, "snap-1.snapshot").writeText("{ ruined", Charsets.UTF_8)

        val summary = FileRecoverySnapshotStore(directory).summaries().single()
        assertEquals("Named point", summary.displayName())
        assertTrue(summary.isManual)
        assertTrue("The indexed size survives a record this build cannot read", summary.sizeBytes > 0)
    }

    // --- Orphans and file naming ----------------------------------------------------------------------

    @Test fun theSweepRemovesWriteResidueAndDamageAndNothingElse() = withDirectory { directory ->
        val store = FileRecoverySnapshotStore(directory)
        store.put(record("snap-1", createdAt = 1_000L))
        File(directory, "snap-1.snapshot.part").writeText("half a record", Charsets.UTF_8)
        File(directory, "snap-7.snapshot").writeText("{ not a record", Charsets.UTF_8)
        File(directory, "unrelated.txt").writeText("not ours", Charsets.UTF_8)

        val reopened = FileRecoverySnapshotStore(directory)
        assertEquals(2, reopened.pruneOrphans())
        assertEquals(listOf("index.json", "snap-1.snapshot", "unrelated.txt"), files(directory))
        assertNotNull("The live record is untouched", reopened.get("snap-1"))
    }

    @Test fun theSweepKeepsARecordWhoseIdHadToBeRewrittenForTheFilesystem() = withDirectory { directory ->
        val store = FileRecoverySnapshotStore(directory)
        store.put(record("note:A/2026", createdAt = 1_000L))

        assertEquals("Sweeping must not mistake a rewritten name for an orphan", 0, store.pruneOrphans())
        assertNotNull(store.get("note:A/2026"))
        assertEquals(listOf("note:A/2026"), FileRecoverySnapshotStore(directory).summaries().map { it.id })
    }

    @Test fun twoIdsThatSanitizeAlikeKeepSeparateRecords() = withDirectory { directory ->
        val store = FileRecoverySnapshotStore(directory)
        store.put(record("a/b", createdAt = 1_000L, text = "slash"))
        store.put(record("a:b", createdAt = 1_001L, text = "colon"))

        assertEquals(payload("slash"), store.get("a/b")?.documents?.getValue(noteA.id))
        assertEquals(
            "A lossy file name must not let one restore point overwrite another",
            payload("colon"),
            store.get("a:b")?.documents?.getValue(noteA.id),
        )
        assertEquals(2, directory.listFiles().orEmpty().count { it.name.endsWith(".snapshot") })
    }

    @Test fun aHostileIdCannotWriteOutsideTheRecoveryDirectory() = withDirectory { directory ->
        val store = FileRecoverySnapshotStore(directory)
        store.put(record("../../escape", createdAt = 1_000L))

        val written = directory.listFiles().orEmpty().filter { it.name.endsWith(".snapshot") }
        assertEquals(1, written.size)
        assertFalse(written.single().name.contains(File.separatorChar))
        assertFalse(File(directory.parentFile, "escape.snapshot").exists())
        assertNotNull("It is still reachable by the id it was stored under", store.get("../../escape"))
    }

    // --- Retention ------------------------------------------------------------------------------------

    @Test fun automaticChurnEvictsTheOldestAutomaticPointAndNeverANamedOne() = withDirectory { directory ->
        val store = FileRecoverySnapshotStore(
            directory,
            RecoveryRetentionPolicy(maxAutomatic = 2, maxManual = 2),
        )
        store.put(record("auto-1", createdAt = 1_000L))
        store.put(named("keep-1", createdAt = 1_001L))
        store.put(record("auto-2", createdAt = 1_002L))
        store.put(record("auto-3", createdAt = 1_003L))
        store.put(record("auto-4", createdAt = 1_004L))

        assertEquals(
            "The two newest automatics remain and the named point is untouched",
            listOf("keep-1", "auto-3", "auto-4"),
            store.summaries().map { it.id },
        )
        assertFalse(File(directory, "auto-1.snapshot").exists())
        assertFalse(File(directory, "auto-2.snapshot").exists())
    }

    @Test fun namedPointsAreBudgetedSeparatelySoTheOldestNamedOneGoesOnlyToNamedOnes() = withDirectory { directory ->
        val store = FileRecoverySnapshotStore(directory, RecoveryRetentionPolicy(maxAutomatic = 1, maxManual = 2))
        store.put(named("keep-1", createdAt = 1_000L))
        store.put(named("keep-2", createdAt = 1_001L))
        store.put(named("keep-3", createdAt = 1_002L))

        assertEquals(listOf("keep-2", "keep-3"), store.summaries().map { it.id })
    }

    @Test fun theByteBudgetSpendsEveryAutomaticPointBeforeTouchingANamedOne() = withDirectory { directory ->
        val keep = named("keep-1", createdAt = 1_000L)
        val auto1 = record("auto-1", createdAt = 1_001L)
        val auto2 = record("auto-2", createdAt = 1_002L)
        val budget = (RecoverySnapshotCodec.encode(keep).length + RecoverySnapshotCodec.encode(auto2).length).toLong()

        val store = FileRecoverySnapshotStore(
            directory,
            RecoveryRetentionPolicy(maxAutomatic = 10, maxManual = 10, maxTotalBytes = budget),
        )
        store.put(keep)
        store.put(auto1)
        store.put(auto2)

        assertEquals(listOf("keep-1", "auto-2"), store.summaries().map { it.id })
        assertTrue("Storage stays inside the budget it advertises", store.storageBytes() <= budget)
    }

    @Test fun storageAccountingMatchesWhatIsOnDiskAndFallsWhenAPointIsDeleted() = withDirectory { directory ->
        val first = record("snap-1", createdAt = 1_000L)
        val second = record("snap-2", createdAt = 1_001L)
        val store = FileRecoverySnapshotStore(directory)
        store.put(first)
        store.put(second)

        val expected = (RecoverySnapshotCodec.encode(first).length + RecoverySnapshotCodec.encode(second).length).toLong()
        assertEquals(expected, store.storageBytes())
        assertEquals("The figure survives a restart", expected, FileRecoverySnapshotStore(directory).storageBytes())

        assertTrue(store.remove("snap-1"))
        assertEquals(RecoverySnapshotCodec.encode(second).length.toLong(), store.storageBytes())
        assertFalse(File(directory, "snap-1.snapshot").exists())
        assertFalse("Removing twice is not an error, it is nothing", store.remove("snap-1"))
    }

    @Test fun clearingRemovesEveryRecordAndNotJustTheIndexThatListedThem() = withDirectory { directory ->
        val store = FileRecoverySnapshotStore(directory)
        store.put(record("snap-1", createdAt = 1_000L))
        store.put(named("snap-2", createdAt = 1_001L))
        store.clear()

        assertEquals(listOf("index.json"), files(directory))
        assertTrue(FileRecoverySnapshotStore(directory).summaries().isEmpty())
        assertEquals(0L, store.storageBytes())
    }

    @Test fun aRetentionPolicyThatCouldKeepNothingIsRejectedAtConstruction() {
        listOf(
            { RecoveryRetentionPolicy(maxAutomatic = 0) },
            { RecoveryRetentionPolicy(maxManual = 0) },
            { RecoveryRetentionPolicy(maxTotalBytes = 0) },
        ).forEach { build ->
            assertTrue(runCatching { build() }.exceptionOrNull() is IllegalArgumentException)
        }
        assertEquals(12, RecoveryRetentionPolicy.DEFAULT_MAX_AUTOMATIC)
        assertEquals(24, RecoveryRetentionPolicy.DEFAULT_MAX_MANUAL)
        assertEquals(24L * 1024 * 1024, RecoveryRetentionPolicy.DEFAULT_MAX_TOTAL_BYTES)
    }

    // --- The manager over a durable store -------------------------------------------------------------

    @Test fun aNamedPointTakenThroughTheManagerIsReadableAfterARestart() = withDirectory { directory ->
        val workspace = workspace(noteA to "original")
        val created = manager(workspace, FileRecoverySnapshotStore(directory))
            .createNamedSnapshot("Before the risky bit", "keep this")!!

        val reopened = RecoverySnapshotManager(workspace, FileRecoverySnapshotStore(directory))
        workspace.setDocument(noteA, doc("wrecked"))
        assertEquals(setOf(noteA), reopened.restoreById(created.id))
        assertEquals("original", workspace.documentOf(noteA).plainText())
    }

    @Test fun theRecoveryDirectoryHoldsOnlyRecoveryFilesAndIsCreatedOnDemand() = withDirectory { root ->
        val directory = File(root, "recovery/points")
        assertFalse(directory.exists())

        FileRecoverySnapshotStore(directory).put(record("snap-1", createdAt = 1_000L))

        assertTrue(directory.isDirectory)
        assertEquals(listOf("index.json", "snap-1.snapshot"), files(directory))
    }

    // --- The budget is measured in the units it claims ------------------------------------------------

    /**
     * A document is not ASCII, and the budget is in bytes.
     *
     * The size recorded for a record used to be the encoded *string's* length — UTF-16 char count — while the
     * file is written as UTF-8. For a note in Japanese, Arabic, or anything with an emoji in it, every
     * multi-byte character made the recorded figure smaller than the file it described. That figure is what
     * `storageBytes()` reports to the user and what the retention budget is enforced against, so recovery
     * could sit at three times the cap it advertises while every internal check agreed it was inside it.
     *
     * The comparison is against `File.length()` deliberately: measuring the expectation the same way the
     * store does is how this went unnoticed in the first place.
     */
    @Test fun aRecordsSizeIsItsSizeOnDiskAndNotItsCharacterCount() = withDirectory { directory ->
        val store = FileRecoverySnapshotStore(directory)
        store.put(record("snap-1", createdAt = 1_000L, text = "日本語のメモ 🗒️ café"))

        val onDisk = File(directory, "snap-1.snapshot").length()
        assertTrue("the fixture has to actually be multi-byte, or this proves nothing", onDisk > 0)
        assertEquals("the recorded size is the file's size", onDisk, store.storageBytes())
        assertEquals(
            "and it still is after a restart, where the figure is read back from the index",
            onDisk,
            FileRecoverySnapshotStore(directory).storageBytes(),
        )
    }

    @Test fun theByteBudgetBoundsWhatIsActuallyOnDiskForANonAsciiDocument() = withDirectory { directory ->
        val text = "日本語のメモ 🗒️"
        val one = RecoverySnapshotCodec.encode(record("probe", createdAt = 1L, text = text))
            .toByteArray(Charsets.UTF_8).size.toLong()
        // Room for two records and no more, expressed in the same unit the policy is documented in.
        val budget = one * 2
        val store = FileRecoverySnapshotStore(
            directory,
            RecoveryRetentionPolicy(maxAutomatic = 10, maxManual = 10, maxTotalBytes = budget),
        )
        (1..5).forEach { store.put(record("auto-$it", createdAt = 1_000L + it, text = text)) }

        val actual = directory.listFiles().orEmpty()
            .filter { it.name.endsWith(FileRecoverySnapshotStore.RECORD_SUFFIX) }
            .sumOf { it.length() }
        assertTrue("$actual bytes on disk must stay inside the $budget-byte budget", actual <= budget)
        assertEquals("and the reported figure agrees with the filesystem", actual, store.storageBytes())
    }

    /**
     * A replacement that cannot be written must leave the record it was replacing intact.
     *
     * Storage filling up or a directory losing write permission is the reachable version of a failed replace,
     * and the wrong response to it is a lost restore point: the user still has the older one, and it is better
     * than nothing. So the write is refused as a whole rather than half-applied.
     *
     * What this does **not** prove is atomicity against process death. A kill between the temp write and the
     * rename cannot be staged from a unit test, so the ordering that closes that window — rename first,
     * delete only as a fallback — is argued in [FileRecoverySnapshotStore] rather than asserted here. Naming
     * that limit is the point; a test called "atomic" that only ever fails before the rename would read like
     * the window had been covered.
     */
    @Test fun aReplacementThatCannotBeWrittenLeavesThePreviousRecordReadable() = withDirectory { directory ->
        val store = FileRecoverySnapshotStore(directory)
        store.put(record("snap-1", createdAt = 1_000L, text = "the original"))
        val file = File(directory, "snap-1.snapshot")
        val before = file.readText(Charsets.UTF_8)

        assertTrue("the fixture needs the directory to refuse writes", directory.setWritable(false))
        try {
            store.put(record("snap-1", createdAt = 2_000L, text = "the replacement"))
        } finally {
            directory.setWritable(true)
        }

        assertTrue("the record it was replacing must still be there", file.isFile)
        assertEquals("and must still be the whole previous record", before, file.readText(Charsets.UTF_8))
        assertEquals(
            "so a reader still gets the old content rather than nothing",
            "the original",
            (FileRecoverySnapshotStore(directory).read("snap-1") as RecoveryDecodeResult.Success)
                .snapshot.documents.values.single().let(BlockDocumentJson::decodeDocumentPayload).plainText(),
        )
    }
}
