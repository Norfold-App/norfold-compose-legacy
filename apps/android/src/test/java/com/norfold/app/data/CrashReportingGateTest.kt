package com.norfold.app.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of the crash-reporting consent gate that only a device could otherwise catch.
 *
 * Nothing here can call Crashlytics — the SDK needs a `FirebaseApp`, which needs an Android context and a
 * `google-services.json`, so a JVM unit test cannot observe collection actually starting or stopping. What
 * it *can* do is assert the three things whose failure mode is silent shipping: a manifest flag that lets
 * the SDK collect before consent, a call site removed from `Application.onCreate`, and content reaching
 * `setCustomKey`. Each of those looks fine in code review, passes every other test, and is a privacy
 * regression on a real device.
 *
 * Reading files as text is the same approach `TaskReminderManifestTest` takes for the same reason, and it
 * is deliberate rather than lazy: the manifest and the ProGuard rules are build inputs that no compiled
 * test can see at all.
 */
class CrashReportingGateTest {
    private fun read(vararg candidates: String): String {
        val paths = candidates.flatMap { listOf(File(it), File("apps/android/$it")) }
        return (paths.firstOrNull(File::isFile) ?: error("not found: ${candidates.joinToString()}")).readText()
    }

    /** The same two-candidate resolution as [read], for a directory rather than a file. */
    private fun dir(vararg candidates: String): File =
        candidates.flatMap { listOf(File(it), File("apps/android/$it")) }.firstOrNull(File::isDirectory)
            ?: error("not found: ${candidates.joinToString()}")

    private val manifest: String by lazy { read("src/main/AndroidManifest.xml") }
    private val proguard: String by lazy { read("proguard-rules.pro") }
    private val gate: String by lazy { read("src/main/java/com/norfold/app/data/CrashReportingGate.kt") }
    private val application: String by lazy { read("src/main/java/com/norfold/app/NorfoldApplication.kt") }
    private val buildFile: String by lazy { read("build.gradle.kts") }
    private val settings: String by lazy { read("src/main/java/com/norfold/app/ui/screens/SettingsScreen.kt") }

    @Test
    fun manifestHoldsCollectionOffByDefault() {
        // The single most important line in this change. Crashlytics installs its handler from a
        // ContentProvider the manifest merger injects, which runs before Application.onCreate — so if
        // this flag is absent or true, a first-launch startup crash uploads before any consent exists and
        // no amount of Kotlin can intervene. Matched loosely on whitespace because it is XML across
        // several lines, and asserted on the value because `true` here would be silently catastrophic.
        val declaration = Regex(
            """<meta-data\s+android:name="firebase_crashlytics_collection_enabled"\s+android:value="(\w+)"""",
        ).find(manifest) ?: error(
            "firebase_crashlytics_collection_enabled is not declared in the manifest — without it " +
                "Crashlytics collects on first launch before the user has been asked",
        )
        assertEquals(
            "firebase_crashlytics_collection_enabled must be false; CrashReportingGate opens it after " +
                "reading the stored preference",
            "false",
            declaration.groupValues[1],
        )
    }

    @Test
    fun theGateIsAppliedFromApplicationOnCreate() {
        // A gate nothing calls is a gate that is always shut, which sounds safe and is not: a user who
        // turned diagnostics on would silently get no crash reporting, and the manifest flag would be
        // read as the final answer rather than the starting one.
        assertTrue(
            "NorfoldApplication must call CrashReportingGate.apply(this) so the stored preference is " +
                "honoured on every start",
            application.contains("CrashReportingGate.apply(this)"),
        )
    }

    @Test
    fun withdrawingConsentDiscardsWhatIsAlreadyQueued() {
        // "Off" has to mean the crash from ten minutes ago too. A queued report that uploads on the next
        // launch would make the switch a promise about the future only, which is not what the Settings
        // copy says it is.
        val disabled = gate.substringAfter("fun setEnabled").substringBefore("fun recordHandled")
        assertTrue(
            "setEnabled must call deleteUnsentReports() when disabling, or reports written before the " +
                "user said no still upload",
            disabled.contains("deleteUnsentReports()"),
        )
    }

    @Test
    fun noUserContentIsAttachedToReports() {
        // The rule that erodes one convenient call at a time. setCustomKey and setUserId are the two APIs
        // that attach arbitrary strings to a report, and the whole privacy claim in Settings rests on
        // neither of them carrying document text, a title, a workspace name or an account identifier.
        // Asserted across the whole main sourceset, not just the gate, because the temptation appears
        // wherever someone is debugging — a ViewModel, an importer, a sync path.
        val offenders = dir("src/main/java").let { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { file ->
                    file.readText().lineSequence().withIndex()
                        .filter { (_, line) ->
                            (line.contains("setCustomKey") || line.contains("setUserId")) &&
                                !line.trimStart().startsWith("*") &&
                                !line.trimStart().startsWith("//")
                        }
                        .map { (index, line) -> "${file.path}:${index + 1} ${line.trim()}" }
                }
                .toList()
        }
        assertEquals(
            "No call site may attach custom keys or a user id to a crash report: the Settings copy " +
                "promises reports carry the error, the app version and the device model and nothing else. " +
                "If a report genuinely needs more context, add a fixed non-content string through " +
                "CrashReportingGate.recordHandled instead",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun crashReportingIsNotDisclosedAsAbsent() {
        // The old Settings copy read "No remote analytics service is used". Shipping automatic upload
        // under that sentence would be the worst version of this change: a user reads it, believes it,
        // and stops looking. The claim must not come back, and the replacement must name the service, say
        // that sending is automatic, and state what is never sent — the promise the custom-key test above
        // is what actually enforces.
        //
        // Matched on phrases specific enough to be load-bearing rather than on single words: "Crashlytics"
        // alone would survive a rewrite that dropped "automatically", and "automatically" alone already
        // appears elsewhere in this file about local backup encryption. Each phrase below sits inside one
        // string literal, because Kotlin concatenation means a phrase spanning a `" +` boundary is not a
        // substring of the source.
        assertFalse(
            "Settings must not claim no remote service is used while crash reports upload automatically",
            settings.contains("No remote analytics service is used"),
        )
        listOf(
            "automatically to Google Firebase Crashlytics",
            "never your notes",
        ).forEach {
            assertTrue(
                "The diagnostics copy must name what leaves the device and what does not; missing \"$it\"",
                settings.contains(it),
            )
        }
    }

    @Test
    fun stackTraceAttributesSurviveMinification() {
        // Invisible to every other test, because unit tests never run R8. Without these two the mapping
        // file still deobfuscates class and method names and every line number is gone, which turns a
        // report into "somewhere in DocsViewModel".
        assertTrue(
            "-keepattributes SourceFile,LineNumberTable is required or release crash reports carry no " +
                "line numbers",
            Regex("""-keepattributes[^\n]*\bSourceFile\b[^\n]*\bLineNumberTable\b""").containsMatchIn(proguard),
        )
        assertTrue(
            "-renamesourcefileattribute is Google's documented companion to keeping SourceFile, so the " +
                "attribute is kept without publishing real file names",
            proguard.contains("-renamesourcefileattribute"),
        )
    }

    @Test
    fun bothFirebasePluginsShareOneConditionOnTheConfigFile() {
        // google-services.json is gitignored, so a fresh clone does not have one and an unconditional
        // apply fails such a clone at configuration time. Both plugins must sit behind the same check —
        // if they ever disagree about whether Firebase is configured, one of them fails the build for a
        // reason the other one's presence contradicts.
        val guarded = buildFile.substringAfter("""if (file("google-services.json").isFile) {""", "")
            .substringBefore("}")
        assertTrue(
            "google-services must be applied only when google-services.json exists",
            guarded.contains("com.google.gms.google-services"),
        )
        assertTrue(
            "The Crashlytics plugin must sit behind the same google-services.json check; it uploads the " +
                "R8 mapping file to a Firebase project and has nothing to upload to without one",
            guarded.contains("com.google.firebase.crashlytics"),
        )
    }

    @Test
    fun theCrashlyticsVersionComesFromTheBom() {
        // A hard-coded version beside a BOM is how a graph silently splits: the BOM governs messaging,
        // the literal governs crashlytics, and the two drift until something fails at runtime on a
        // device. The BOM is already declared for messaging; crashlytics rides it.
        // Anchored on the closing quote via `:?[^":]*` rather than `[^"]*` so this cannot be satisfied by
        // a future `firebase-crashlytics-ndk` line while the un-suffixed artifact carries a hard-coded
        // version — the artifact name must end right where the optional `:version` begins.
        val declaration = Regex("""implementation\("com\.google\.firebase:firebase-crashlytics(:[^"]*)?"\)""")
            .find(buildFile)
            ?: error("firebase-crashlytics is not declared in the app module without a version suffix")
        assertEquals(
            "firebase-crashlytics must not carry its own version — firebase-bom pins it",
            "",
            declaration.groupValues[1],
        )
        assertTrue(
            "The Firebase BOM must be declared as a platform for that to work",
            buildFile.contains("""platform("com.google.firebase:firebase-bom:"""),
        )
    }
}
