package com.norfold.app.editor.history

import com.norfold.app.editor.flags.EditorFeatureFlag
import com.norfold.app.editor.flags.EditorFeatureFlags
import java.io.File
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase-10 boundaries (§37 items 77–81).
 *
 * These are the tests that fail if a later change *adds* something Phase 10 was not allowed to add. Every
 * other suite proves the feature works; this one proves the feature did not cost anything it was not
 * supposed to cost — a second undo stack, a network call, a collaboration edit, a flag flipped on by
 * default, or Phase-11 certification work smuggled in early.
 *
 * Structural claims are made by scanning the real source directories. Each scan asserts the directory exists
 * and that a plausible number of files were read before it asserts an absence: without those two guards a
 * renamed package would make every `!in` check trivially true and this file would pass while reading nothing.
 */
class HistoryBoundaryPhase10Test {

    private fun sources(path: String, minimumFiles: Int): String {
        val dir = File(path)
        assertTrue("$path must be where this scan looks", dir.isDirectory)
        val files = dir.walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue("the scan must actually read $path (found ${files.size})", files.size >= minimumFiles)
        return files.joinToString("\n") { it.readText() }
    }

    private fun phase10Sources(): String =
        sources("src/main/java/com/norfold/app/editor/history", 6) + "\n" +
            sources("src/main/java/com/norfold/app/editor/recovery", 6) + "\n" +
            sources("src/main/java/com/norfold/app/ui/editor/history", 1) + "\n" +
            sources("src/main/java/com/norfold/app/ui/editor/recovery", 1)

    private fun editorSources(): String = sources("src/main/java/com/norfold/app/editor", 60)

    /**
     * The same text with comments removed.
     *
     * Phase 0 marks the §14.7 collaboration boundary in prose — `EditOperation` says it is *not* asking a
     * merge engine for an operation, `EditTransaction` says its origin tag does not bind to
     * `SemanticOperation`. Naming a boundary is how a boundary stays visible, so a scan that cannot tell a
     * comment from a dependency would force those comments to be deleted to stay green. What must be absent
     * is the machinery.
     */
    private fun codeOnly(source: String): String = source
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//[^\n]*"""), "")

    // ── 77. No second authoritative history ─────────────────────────────────────────────────────────

    @Test
    fun noCompetingUndoStackTypeWasIntroducedAnywhereInTheEditor() {
        val editor = editorSources() + "\n" + sources("src/main/java/com/norfold/app/ui/editor", 20)
        // The exact names the Phase-10 brief forbids. Each would be a second place a transaction could be
        // reversed, and two authorities over one document is how undo starts disagreeing with the document.
        listOf(
            "FlowUndoStack",
            "DocumentUndoStack",
            "SpreadsheetUndoStack",
            "ObjectUndoStack",
            "FilteredUndoStack",
            "ContextUndoStack",
        ).forEach { forbidden ->
            assertFalse("$forbidden must not exist: HistoryEngine is the one authority", forbidden in editor)
        }
    }

    @Test
    fun onlyTheHistoryEngineOwnsUndoAndRedoStacks() {
        val editor = editorSources()
        // The engine is allowed exactly one pair. Anything else declaring its own is the defect.
        val undoStackDeclarations = Regex("""val\s+undoStack""").findAll(editor).count()
        val redoStackDeclarations = Regex("""val\s+redoStack""").findAll(editor).count()
        assertEquals("exactly one undo stack may be declared", 1, undoStackDeclarations)
        assertEquals("exactly one redo stack may be declared", 1, redoStackDeclarations)
        assertTrue(
            "and it must be the engine's",
            Regex("""val\s+undoStack""") in
                File("src/main/java/com/norfold/app/editor/history/HistoryEngine.kt").readText(),
        )
    }

    @Test
    fun theProjectionLayerHasNoWayToMutateTheCanonicalStack() {
        val projection = File("src/main/java/com/norfold/app/editor/history/HistoryProjection.kt").readText()
        val filter = File("src/main/java/com/norfold/app/editor/history/HistoryFilter.kt").readText()
        // A projection that could commit, clear or pop would let "filtering" quietly delete history. It reads
        // transactions and returns entries; that is the entire contract.
        listOf(".commit(", ".clear()", "removeAt(", "removeLast(", "undoStack", "redoStack").forEach { token ->
            assertFalse("HistoryProjection must not reach $token", token in projection)
            assertFalse("HistoryFilter must not reach $token", token in filter)
        }
    }

    // ── 78. No network dependency ───────────────────────────────────────────────────────────────────

    @Test
    fun historyAndRecoveryLinkAgainstNoNetworkOrRemoteService() {
        val phase10 = phase10Sources()
        listOf(
            "java.net",
            "okhttp",
            "retrofit",
            "supabase",
            "firebase",
            "ably",
            "cloudflare",
            "HttpURL",
            "WebSocket",
            "io.ktor",
        ).forEach { token ->
            assertFalse(
                "History and recovery are local-first and offline; found \"$token\"",
                token.lowercase() in phase10.lowercase(),
            )
        }
    }

    @Test
    fun recoveryPayloadsAreNeverLoggedAndNeverLeaveAppPrivateStorage() {
        val recovery = sources("src/main/java/com/norfold/app/editor/recovery", 6) + "\n" +
            File("src/main/java/com/norfold/app/data/RecoveryStorageProvider.kt").readText()
        // Logging a payload would put document text in logcat, which is readable well outside the sandbox.
        listOf("android.util.Log", "println(", "System.out", "printStackTrace").forEach { token ->
            assertFalse("recovery must not log; found \"$token\"", token in recovery)
        }
        // And shared storage would put snapshots where any app with the permission can read them.
        listOf(
            "getExternalStorageDirectory",
            "getExternalFilesDir",
            "Environment.DIRECTORY",
            "MediaStore",
            "/sdcard",
        ).forEach { token ->
            assertFalse("snapshots must stay app-private; found \"$token\"", token in recovery)
        }
        assertTrue(
            "the store's directory comes from app-private filesDir",
            "filesDir" in File("src/main/java/com/norfold/app/data/RecoveryStorageProvider.kt").readText(),
        )
    }

    @Test
    fun noCryptoOrDatabaseDependencyWasAddedForDurability() {
        val phase10 = phase10Sources()
        // SHA-256 over a payload is an integrity check and is used as one. Anything that encrypts, derives a
        // key, or opens a database would be a new security architecture, which Phase 10 was told not to build.
        listOf(
            "javax.crypto",
            "Cipher",
            "SecretKey",
            "KeyGenerator",
            "PBKDF2",
            "SQLCipher",
            "SQLiteDatabase",
            "androidx.room",
            "net.sqlcipher",
        ).forEach { token ->
            assertFalse("no crypto or database may be introduced; found \"$token\"", token in phase10)
        }
        assertTrue(
            "integrity is a digest, and is named as one",
            "MessageDigest" in File("src/main/java/com/norfold/app/editor/recovery/RecoverySnapshotCodec.kt").readText(),
        )
    }

    // ── 79. No collaboration change ─────────────────────────────────────────────────────────────────

    @Test
    fun phase10TouchesNoCollaborationMachinery() {
        val phase10 = codeOnly(phase10Sources())
        listOf(
            "Crdt",
            "CRDT",
            "SemanticOperation",
            "MergeSelection",
            "RemoteUndo",
            "CollaborationSession",
            "Presence",
        ).forEach { token ->
            assertFalse("Phase 12 owns collaboration; found \"$token\"", token in phase10)
        }
        // And the boundary is still written down where Phase 0 wrote it, because the check above only proves
        // the code is clean — it would also pass if someone deleted the comments that say why.
        assertTrue(
            "the §14.7 boundary comment must survive",
            "CRDT" in File("src/main/java/com/norfold/app/editor/history/HistoryEngine.kt").readText(),
        )
    }

    @Test
    fun theOnlyMineFilterUsesTransactionOriginRatherThanACollaborationConcept() {
        // §13.6 wants a "my changes" lens. Offline, provenance is already on the transaction, so the lens is
        // a filter over an existing tag — not a new identity or session concept that would prejudge Phase 12.
        val filter = File("src/main/java/com/norfold/app/editor/history/HistoryFilter.kt").readText()
        assertTrue("the lens filters on origin", "TransactionOrigin" in filter)
        assertFalse("and introduces no author identity", "userId" in filter)
        assertFalse("and no remote peer concept", "peer" in filter.lowercase())

        val local = HistoryFilter(onlyMine = true)
        assertFalse("asking for only-mine is not the default view", local.isDefault)
    }

    // ── 80. Flag defaults are unchanged ─────────────────────────────────────────────────────────────

    @Test
    fun everyEditorFeatureFlagStillDefaultsOff() {
        val enabledByDefault = EditorFeatureFlag.entries.filter { it.defaultEnabled }
        assertEquals("no editor flag may ship enabled", emptyList<EditorFeatureFlag>(), enabledByDefault)
    }

    @Test
    fun thePhase10FlagIsNarrowlyScopedAndOffAndDoesNotDisturbTheEngineFlags() {
        assertEquals("editor.domain.history.ui", EditorFeatureFlag.HISTORY_UI.key)
        assertFalse(EditorFeatureFlag.HISTORY_UI.defaultEnabled)

        // The two flags Phase 10 depends on but must not change.
        assertEquals("editor.engine.history", EditorFeatureFlag.HISTORY_ENGINE.key)
        assertFalse(EditorFeatureFlag.HISTORY_ENGINE.defaultEnabled)
        assertEquals("editor.engine.recovery", EditorFeatureFlag.RECOVERY_SNAPSHOTS.key)
        assertFalse(EditorFeatureFlag.RECOVERY_SNAPSHOTS.defaultEnabled)

        // Keys are the persisted identity of a flag; a duplicate would silently alias two features together.
        val keys = EditorFeatureFlag.entries.map { it.key }
        assertEquals("flag keys must be unique", keys.size, keys.distinct().size)
    }

    @Test
    fun aDefaultFlagSetEnablesNothingSoNoSurfaceAppearsUnasked() {
        val flags = EditorFeatureFlags.productionDefaults()
        EditorFeatureFlag.entries.forEach { flag ->
            assertFalse("${flag.name} must be off in a default set", flags.isEnabled(flag))
        }
        assertTrue("the shipped configuration overrides nothing", flags.isDefaultConfiguration())
        assertTrue("and so no Phase-10 surface can appear unasked", flags.allDisabled())
    }

    // ── 81. Phase 11 is untouched ───────────────────────────────────────────────────────────────────

    @Test
    fun noPhase11CertificationWorkWasStarted() {
        val phase10 = phase10Sources()
        // Phase 11 owns benchmark certification, the accessibility certification matrix and device
        // profiling. Phase 10's own counters are content-free metrics, which is a different thing.
        listOf(
            "Benchmark",
            "CertificationMatrix",
            "DeviceProfile",
            "TraceCompat",
            "androidx.benchmark",
            "systrace",
        ).forEach { token ->
            assertFalse("Phase 11 owns certification; found \"$token\"", token in phase10)
        }
    }

    @Test
    fun phase10MetricsCountEventsAndCarryNoDocumentContent() {
        val metrics = HistoryMetrics(
            projectionsBuilt = 1,
            filterChanges = 1,
            plansComputed = 1,
            stepsExecuted = 1,
            entriesMoved = 1,
            stalePlansRejected = 0,
            focusRoutedSteps = 0,
            focusBlockedSteps = 0,
        )
        // Every field is a count. A String field here would be the seam through which a label — and so
        // document text — could reach a metrics sink.
        //
        // Statics are excluded because the Compose compiler adds a `$stable` int to every class in this
        // module; the claim is about the state an instance carries.
        val counters = HistoryMetrics::class.java.declaredFields
            .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
        assertEquals("every declared counter must be checked", 8, counters.size)
        counters.forEach { field ->
            assertEquals("${field.name} must be a plain counter", Long::class.javaPrimitiveType, field.type)
        }
        assertEquals(1L, metrics.projectionsBuilt)
    }
}
