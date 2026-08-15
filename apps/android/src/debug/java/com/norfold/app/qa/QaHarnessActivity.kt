package com.norfold.app.qa

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.norfold.app.domain.AppSettings
import com.norfold.app.domain.Destination
import com.norfold.app.domain.DocumentArtifactKind
import com.norfold.app.domain.EditorFontFamily
import com.norfold.app.domain.EditorLineWidth
import com.norfold.app.domain.ThemeMode
import com.norfold.app.domain.ThemeProfile
import com.norfold.app.domain.WorkspaceObject
import com.norfold.app.ui.DocsViewModel
import com.norfold.app.ui.NorfoldRoot
import com.norfold.app.ui.NorfoldTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.math.abs

/** Debug-variant-only launcher for deterministic, account-free integrated QA. */
class QaHarnessActivity : FragmentActivity() {
    private var status by mutableStateOf("Choose a deterministic local scenario")
    private var busy by mutableStateOf(false)
    private var integrationSeamConfigured by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        integrationSeamConfigured = QaIntegrationCredentials.from(intent.extras) != null
        QaEntry.fromRoute(intent.getStringExtra(QaEntry.RouteExtra))?.let(::runEntry) ?: showMenu()
    }

    private fun showMenu() {
        setContent {
            NorfoldTheme(
                settings = AppSettings(
                    themeMode = ThemeMode.System,
                    themeProfile = ThemeProfile.Norfold,
                    accentColor = 0xFF3D7C45,
                    backupFolderUri = null,
                    vaultLockEnabled = false,
                    vaultSecretHash = null,
                    onboardingComplete = true,
                ),
            ) {
                QaHarnessMenu(
                    status = status,
                    busy = busy,
                    integrationSeamConfigured = integrationSeamConfigured,
                    onEntry = ::runEntry,
                )
            }
        }
    }

    private fun runEntry(entry: QaEntry) {
        if (busy) return
        busy = true
        status = "Preparing ${entry.title}"
        lifecycleScope.launch {
            runCatching {
                // Parsed inside the catch, so a malformed extra from a capture runner shows up as a failed
                // launch with the offending key named, rather than as a shot taken at the wrong scale.
                val preferences = QaVisualPreferences.from(intent.extras)
                // The scenario is authored on a background thread through the production runtimes and the
                // command registry, and it reports back the workspace id the app itself chose. The harness
                // no longer asserts a hardcoded id, because it no longer mints one.
                val workspaceId = withContext(Dispatchers.IO) {
                    QaScenarioBuilders.prepare(applicationContext, entry.scenario)
                }
                if (entry == QaEntry.ResetEmpty) {
                    status = "Workspace reset to empty — no user content remains"
                    busy = false
                    showMenu()
                    return@launch
                }
                val viewModel = ViewModelProvider(this@QaHarnessActivity)[DocsViewModel::class.java]
                withTimeout(15_000) { viewModel.state.first { it.settings.activeWorkspaceId == workspaceId } }
                // Before the surface opens, not after: a preference that lands on a visible editor reflows it,
                // and a capture cannot tell a reflow that settled from one still in flight.
                preferences.applyTo(viewModel)
                when (entry.surface) {
                    QaSurface.Workspace -> viewModel.go(Destination.WorkspaceHub)
                    QaSurface.Onboarding -> Unit
                    QaSurface.Flow -> openArtifact(viewModel, DocumentArtifactKind.Flow, entry.noteTitle)
                    QaSurface.Document -> openArtifact(viewModel, DocumentArtifactKind.Document, entry.noteTitle)
                    QaSurface.Spreadsheet -> openArtifact(viewModel, DocumentArtifactKind.Spreadsheet, entry.noteTitle)
                    QaSurface.Review -> viewModel.openWorkspaceObject(awaitWorkspaceObject(viewModel, QaTitles.Document))
                    QaSurface.History -> viewModel.go(Destination.Activity)
                    QaSurface.Interchange -> openArtifact(viewModel, DocumentArtifactKind.Document, QaTitles.Document)
                }
                setContent { NorfoldRoot(viewModel) }
            }.onFailure { error ->
                status = "QA setup failed: ${error.message ?: error::class.java.simpleName}"
                busy = false
                showMenu()
            }
        }
    }

    /** Resolves a workspace object by title, because the builders let the repository allocate its id. */
    private suspend fun awaitWorkspaceObject(viewModel: DocsViewModel, title: String): WorkspaceObject =
        withTimeout(15_000) {
            viewModel.state.first { state -> state.workspaceObjects.any { it.title == title } }
        }.workspaceObjects.first { it.title == title }

    private suspend fun openArtifact(
        viewModel: DocsViewModel,
        kind: DocumentArtifactKind,
        title: String?,
    ) {
        val state = withTimeout(15_000) {
            viewModel.state.first { current ->
                current.notes.any { note -> note.artifactKind == kind && (title == null || note.title == title) }
            }
        }
        viewModel.select(state.notes.first { it.artifactKind == kind && (title == null || it.title == title) })
    }
}

private enum class QaSurface { Workspace, Onboarding, Flow, Document, Spreadsheet, Review, History, Interchange }

/**
 * The harness menu.
 *
 * Every entry's detail says what the scenario actually contains. Where a block type has no command path —
 * images need a real Android URI from the picker; file, embed, and shape blocks have no Flow insert command;
 * bounded documents can insert only text, shapes, and charts — the entry says so instead of implying coverage
 * that does not exist. Content the commands cannot produce is not smuggled in behind them.
 */
private enum class QaEntry(
    val title: String,
    val detail: String,
    val scenario: QaScenario,
    val surface: QaSurface,
    val noteTitle: String? = null,
    val route: String? = null,
) {
    Demo("Enter Demo Workspace", "Command-authored local corpus; no account, cloud, or network", QaScenario.Demo, QaSurface.Workspace),
    Empty("Fresh / Empty Workspace", "A completed setup with no user content", QaScenario.Empty, QaSurface.Workspace),
    Onboarding("Test Onboarding", "First-run setup from a deterministic blank database", QaScenario.Onboarding, QaSurface.Onboarding),
    Offline("Offline Workspace", "Local content with sync disabled and explicit offline status", QaScenario.Offline, QaSurface.Workspace),
    Legacy(
        "Legacy Compatibility",
        "A positioned pre-cutover row, saved the way restore and import supply one",
        QaScenario.Demo,
        QaSurface.Document,
        QaTitles.Legacy,
    ),
    ResetEmpty("Reset To Empty", "Erase all content and stop at an empty workspace", QaScenario.Empty, QaSurface.Workspace),
    Flow(
        "Open Flow",
        "Every insertable block kind, inserted by command. No image: the picker needs a device URI",
        QaScenario.Demo,
        QaSurface.Flow,
        QaTitles.FlowFieldNotes,
        "flow-phone-complete-set",
    ),
    FlowResponsiveNested(
        "Flow · Responsive Nested",
        "Phone golden: nested columns stack into one writing-first stream",
        QaScenario.Demo,
        QaSurface.Flow,
        QaTitles.FlowDailyLog,
        "flow-phone-responsive-nested",
    ),
    FlowMermaidPreview(
        "Flow · Mermaid Preview",
        "Phone golden: one source-or-preview representation area",
        QaScenario.Demo,
        QaSurface.Flow,
        QaTitles.FlowArchitecture,
        "flow-phone-mermaid-preview",
    ),
    FlowReadingMode(
        "Flow · Reading Mode",
        "Phone golden: content only until an explicit edit interaction",
        QaScenario.Demo,
        QaSurface.Flow,
        QaTitles.FlowReading,
        "flow-phone-reading-mode",
    ),
    FlowCalloutTones(
        "Flow · Callout Tones",
        "Phone golden: five callout tones, each stepped there through the tone control",
        QaScenario.Demo,
        QaSurface.Flow,
        QaTitles.FlowCallouts,
        "flow-phone-callout-tones",
    ),
    Document(
        "Open Document",
        "Two pages of text, chart, shape, and layered overlap. Tables and images have no insert command here",
        QaScenario.Demo,
        QaSurface.Document,
        QaTitles.Document,
        // Routed so a capture can show the page host beside Flow: paper keeps its own measure and type scale,
        // and the pair is the only evidence that the reading preference is opt-in per renderer rather than global.
        "document-phone-page-host",
    ),
    Spreadsheet(
        "Open Spreadsheet",
        "Two sheets, formulas, freeze, named range, list validation, chart binding",
        QaScenario.Demo,
        QaSurface.Spreadsheet,
        QaTitles.Spreadsheet,
    ),
    Review("Open Review", "Workspace discussion on the brief, plus in-editor threads on Field Notes", QaScenario.Demo, QaSurface.Review),
    History("Open History", "The activity and version trail the writers recorded while authoring", QaScenario.Demo, QaSurface.History),
    Interchange(
        "Open Interchange",
        "Export and raw-source controls. Import fidelity needs a real file chosen on the device",
        QaScenario.Demo,
        QaSurface.Interchange,
    );

    companion object {
        const val RouteExtra = "com.norfold.qa.ROUTE"

        fun fromRoute(route: String?): QaEntry? = entries.firstOrNull { it.route == route }
    }
}

@Composable
private fun QaHarnessMenu(
    status: String,
    busy: Boolean,
    integrationSeamConfigured: Boolean,
    onEntry: (QaEntry) -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp),
            contentPadding = PaddingValues(top = 40.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Norfold QA", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                    Text("Debug-only deterministic test harness", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        if (integrationSeamConfigured) "Optional integration seam: configured for this launch"
                        else "Optional integration seam: waiting for externally injected QA credentials",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        status,
                        modifier = Modifier.semantics { stateDescription = status },
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (busy) CircularProgressIndicator(Modifier.testTag("qa_loading"))
                }
            }
            items(QaEntry.entries, key = QaEntry::name) { entry ->
                Button(
                    onClick = { onEntry(entry) },
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .testTag("qa_entry_${entry.name.lowercase()}"),
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(entry.title, fontWeight = FontWeight.SemiBold)
                        Text(entry.detail, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/** Ephemeral debug-only seam. Values are never persisted, logged, or rendered. */
private data class QaIntegrationCredentials(val email: String, val password: String) {
    companion object {
        fun from(extras: Bundle?): QaIntegrationCredentials? {
            val email = extras?.getString("com.norfold.qa.EMAIL")?.trim().orEmpty()
            val password = extras?.getString("com.norfold.qa.PASSWORD").orEmpty()
            return if (email.contains('@') && password.isNotBlank()) QaIntegrationCredentials(email, password) else null
        }
    }
}

/**
 * The visual configuration a shot is meant to be taken in, supplied by the capture runner.
 *
 * Presentation only. Content still comes from [QaScenarioBuilders]: nothing here seeds, edits, or reaches a
 * document, so the rule that scenario content is authored by real commands is untouched.
 *
 * Every field is applied through the same [DocsViewModel] call Settings uses. A shot therefore cannot show a
 * configuration the app itself cannot reach, and a broken preference path fails the launch instead of being
 * bypassed by the harness writing the settings row directly. Absent extras change nothing, so an existing
 * route without them behaves exactly as before.
 *
 * All five arrive as strings, because `am start` types are easy to get wrong from a shell and one parser that
 * rejects what it does not understand is worth more than five that quietly accept a default.
 */
private data class QaVisualPreferences(
    val theme: ThemeMode?,
    val reading: Float?,
    val measure: EditorLineWidth?,
    val face: EditorFontFamily?,
    val reduceMotion: Boolean?,
) {
    /**
     * Applies each preference in turn, waiting for it to land before starting the next.
     *
     * Sequential on purpose. Every setter is a read-modify-write of the whole settings row against
     * `state.value`, so two of them launched together race: the second reads the row as it was before the
     * first landed and writes that stale copy back, silently discarding it. Awaiting each one is the
     * difference between four applied preferences and one.
     */
    suspend fun applyTo(viewModel: DocsViewModel) {
        suspend fun step(apply: () -> Unit, reached: (AppSettings) -> Boolean) {
            apply()
            withTimeout(15_000) { viewModel.state.first { reached(it.settings) } }
        }
        theme?.let { value -> step({ viewModel.setTheme(value) }) { it.themeMode == value } }
        measure?.let { value -> step({ viewModel.setEditorLineWidth(value) }) { it.editorLineWidth == value } }
        face?.let { value -> step({ viewModel.setEditorFontFamily(value) }) { it.editorFontFamily == value } }
        reduceMotion?.let { value -> step({ viewModel.setPrivacyOption(reduceMotion = value) }) { it.reduceMotion == value } }
        // The reading fraction has no named setter of its own — Settings patches the field, so the harness
        // patches the same field rather than inventing a second route to it.
        reading?.let { value ->
            step({ viewModel.patchSettings { current -> current.copy(editorFontSize = value) } }) {
                abs(it.editorFontSize - value) < 0.0005f
            }
        }
    }

    companion object {
        private const val ThemeExtra = "com.norfold.qa.THEME"
        private const val ReadingExtra = "com.norfold.qa.READING"
        private const val MeasureExtra = "com.norfold.qa.MEASURE"
        private const val FaceExtra = "com.norfold.qa.FACE"
        private const val ReduceMotionExtra = "com.norfold.qa.REDUCE_MOTION"

        fun from(extras: Bundle?): QaVisualPreferences = QaVisualPreferences(
            theme = extras.named<ThemeMode>(ThemeExtra),
            reading = extras?.getString(ReadingExtra)?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
                val value = requireNotNull(raw.toFloatOrNull()) { "$ReadingExtra is not a number: $raw" }
                require(value in 0f..1f) { "$ReadingExtra must be between 0 and 1, was $value" }
                value
            },
            measure = extras.named<EditorLineWidth>(MeasureExtra),
            face = extras.named<EditorFontFamily>(FaceExtra),
            reduceMotion = extras?.getString(ReduceMotionExtra)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let {
                when (it) {
                    "true", "1", "on", "yes" -> true
                    "false", "0", "off", "no" -> false
                    else -> throw IllegalArgumentException("$ReduceMotionExtra is not a boolean: $it")
                }
            },
        )

        /**
         * Reads an enum by name, case-insensitively, and refuses a name that is not one of its constants.
         *
         * Refusing matters more than parsing here: `--es …FACE srif` silently falling back to the shipped face
         * would produce a screenshot labelled "serif" showing sans, which is worse than no screenshot.
         */
        private inline fun <reified T : Enum<T>> Bundle?.named(key: String): T? {
            val raw = this?.getString(key)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return enumValues<T>().firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: throw IllegalArgumentException(
                    "$key must be one of ${enumValues<T>().joinToString { it.name }}, was $raw",
                )
        }
    }
}
