package com.norfold.app.editor.flags

import java.util.concurrent.ConcurrentHashMap

/**
 * Feature-flag substrate for the editor architecture.
 *
 * The three host flags and the engines and domains they depend on now default **ON**: FlowHost,
 * DocumentHost and SpreadsheetHost are the product's only editor hosts, so their defaults describe
 * what ships rather than what is being trialled. A default of `false` survives only where the flag
 * gates something this build genuinely cannot reach — see [COLLABORATION].
 *
 * This registry is deliberately:
 *  - pure-JVM (no Android framework, no `BuildConfig` dependency) so it is unit-testable and cheap;
 *  - process-local and thread-safe (a [ConcurrentHashMap] of overrides over immutable defaults);
 *  - free of any collaboration/CRDT/room concern (§14.7 boundary).
 *
 * There is intentionally no persistence and no UI toggle: a person cannot select an editor
 * architecture, because product identity is a host, not a preference. Overrides exist so that tests
 * can pin a configuration without a global default change. The default map is the source of truth
 * for "what ships to users".
 */
enum class EditorFeatureFlag(
    /** Stable string key for logging / future remote-config plumbing (not wired). */
    val key: String,
    /** Ships-to-users value. */
    val defaultEnabled: Boolean,
    val description: String,
) {
    // ── Parallel-host flags (product identity is a host, never a layout flag; §2, §17.2) ──
    FLOW_HOST(
        key = "editor.host.flow",
        defaultEnabled = true,
        description = "Route Notes through FlowHost. The only Note host.",
    ),
    DOCUMENT_HOST(
        key = "editor.host.document",
        defaultEnabled = true,
        description = "Route bounded documents through DocumentHost. The only Document host.",
    ),
    SPREADSHEET_HOST(
        key = "editor.host.spreadsheet",
        defaultEnabled = true,
        description = "Expose SpreadsheetHost as a distinct workspace. The only Spreadsheet host.",
    ),

    // ── Shared-engine flags. The final hosts construct these engines unconditionally, so each of
    // these is a statement about the shipped architecture rather than a runtime branch. Nothing in
    // `src/main` reads them; a test that pins one is pinning a description, not a code path.
    HISTORY_ENGINE(
        key = "editor.engine.history",
        defaultEnabled = true,
        description = "Undo/redo runs on the canonical History Engine, not a per-session snapshot deque.",
    ),
    RECOVERY_SNAPSHOTS(
        key = "editor.engine.recovery",
        defaultEnabled = true,
        description = "Recovery snapshots are taken before risky ops, including every content migration.",
    ),
    COMMAND_REGISTRY(
        key = "editor.engine.commands",
        defaultEnabled = true,
        description = "Edits are invoked through the unified semantic Command Registry (§4).",
    ),
    SELECTION_ENGINE(
        key = "editor.engine.selection",
        defaultEnabled = true,
        description = "Selection is tracked through the unified Selection Engine across all domains (§4.5–§4.14).",
    ),
    SURFACE_COORDINATOR(
        key = "editor.engine.surfaces",
        defaultEnabled = true,
        description = "Tool surfaces and dismissal policies run through the Surface Coordinator (§5.1–§5.6).",
    ),
    ADAPTIVE_PRESENTATION(
        key = "editor.engine.adaptive",
        defaultEnabled = true,
        description = "One tool state adapts across form factors via the Adaptive Presentation runtime (§2.6, §3).",
    ),
    INTERACTION_MEMORY(
        key = "editor.engine.memory",
        defaultEnabled = true,
        description = "Interaction state is scoped through the six-scope Interaction Memory runtime (§5.7, §18.7).",
    ),

    // ── Domain flags. Each gates a finished, offline-capable domain of the shipped editor. ──
    COLOR_STUDIO(key = "editor.domain.color", defaultEnabled = true, description = "Color Studio / canonical ColorValue."),
    NAMED_STYLES(key = "editor.domain.styles", defaultEnabled = true, description = "Named styles & tokens."),
    OFFLINE_FONTS(key = "editor.domain.fonts", defaultEnabled = true, description = "Bundled/offline guaranteed font families."),
    CALC_ENGINE(key = "editor.domain.calc", defaultEnabled = true, description = "Shared calculation/data engine."),
    CHART_STUDIO(key = "editor.domain.chart", defaultEnabled = true, description = "ChartDocument model + Chart Studio."),
    IMAGE_FOCUS(key = "editor.domain.image", defaultEnabled = true, description = "Image Focus workspace."),
    SHAPE_BUILDER(key = "editor.domain.shape", defaultEnabled = true, description = "ShapeDocument model + Shape Builder."),
    RELATIONSHIPS(key = "editor.domain.relationships", defaultEnabled = true, description = "Relationship engine + Connect mode + comments/review."),
    IMPORT_EXPORT_RAW(key = "editor.domain.io", defaultEnabled = true, description = "Import/Export/Fidelity/Raw Source."),
    GUIDE(key = "editor.domain.guide", defaultEnabled = true, description = "Guide / progressive discovery."),
    HISTORY_UI(
        key = "editor.domain.history.ui",
        defaultEnabled = true,
        description = "History browser, filtered/contextual undo presentation, named snapshots and the recovery browser.",
    ),

    /**
     * The one flag still defaulting **off**, and the reason is not caution about the code.
     *
     * `EditorCollaborationRuntime` reads this to decide whether to open live network transport. No
     * collaboration backend is deployed for this build, so defaulting it on would mean every editor
     * session attempts a connection that cannot succeed. It flips when a backend exists to connect
     * to — a deployment decision, not an editor one. Local durable history and the merge engine are
     * unaffected: those are not gated here.
     */
    COLLABORATION(
        key = "editor.integration.collaboration",
        defaultEnabled = false,
        description = "Bind finalized editor domains to authenticated durable collaboration (requires a deployed backend).",
    ),
    ;

    companion object {
        fun byKey(key: String): EditorFeatureFlag? = entries.firstOrNull { it.key == key }

        /** Flags that must resolve to enabled in any shipped configuration. */
        val SHIPPED: Set<EditorFeatureFlag> get() = entries.filter { it.defaultEnabled }.toSet()
    }
}

/**
 * Resolves [EditorFeatureFlag] values against immutable defaults, with an optional process-local
 * override layer. Construct once and share; safe for concurrent reads and writes.
 */
class EditorFeatureFlags private constructor(
    private val overrides: MutableMap<EditorFeatureFlag, Boolean>,
) {
    /** True iff the flag is enabled, honoring any override on top of [EditorFeatureFlag.defaultEnabled]. */
    fun isEnabled(flag: EditorFeatureFlag): Boolean = overrides[flag] ?: flag.defaultEnabled

    /** Sets a process-local override. Intended for tests only; nothing in product code calls it. */
    fun setOverride(flag: EditorFeatureFlag, enabled: Boolean) {
        overrides[flag] = enabled
    }

    /** Removes an override, restoring the ships-to-users default. */
    fun clearOverride(flag: EditorFeatureFlag) {
        overrides.remove(flag)
    }

    /** Removes all overrides. */
    fun clearAllOverrides() {
        overrides.clear()
    }

    /** True iff no flag deviates from its default — i.e. the exact configuration shipped to users. */
    fun isDefaultConfiguration(): Boolean =
        EditorFeatureFlag.entries.all { isEnabled(it) == it.defaultEnabled }

    /** True iff every flag currently resolves to disabled. Only a test can produce this state. */
    fun allDisabled(): Boolean = EditorFeatureFlag.entries.none { isEnabled(it) }

    /**
     * True iff every host flag resolves to enabled. The three hosts stand or fall together: a build
     * that reached one of them but not the others would be routing part of the app to a host that
     * no longer exists.
     */
    fun allHostsEnabled(): Boolean =
        isEnabled(EditorFeatureFlag.FLOW_HOST) &&
            isEnabled(EditorFeatureFlag.DOCUMENT_HOST) &&
            isEnabled(EditorFeatureFlag.SPREADSHEET_HOST)

    companion object {
        /** The ships-to-users configuration: no overrides, every flag at its declared default. */
        fun productionDefaults(): EditorFeatureFlags = EditorFeatureFlags(ConcurrentHashMap())

        /** A configuration seeded with the given overrides. For tests pinning a configuration. */
        fun withOverrides(vararg pairs: Pair<EditorFeatureFlag, Boolean>): EditorFeatureFlags =
            EditorFeatureFlags(ConcurrentHashMap(pairs.toMap()))
    }
}
