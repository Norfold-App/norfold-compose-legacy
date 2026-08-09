package com.norfold.app.editor.flags

import java.util.concurrent.ConcurrentHashMap

/**
 * Phase-0 feature-flag substrate for the editor re-hosting effort.
 *
 * Every flag defaults **OFF**. While a flag is off, none of the new editor engines are reachable
 * from product code, so Phase-0 work introduces no user-visible behavior change and the legacy
 * [com.norfold.app.ui.screens] `StructuredBlockEditorHost` remains the only live editor host.
 *
 * This registry is deliberately:
 *  - pure-JVM (no Android framework, no `BuildConfig` dependency) so it is unit-testable and cheap;
 *  - process-local and thread-safe (a [ConcurrentHashMap] of overrides over immutable defaults);
 *  - free of any collaboration/CRDT/room concern (§14.7 boundary — not touched in Phase 0).
 *
 * There is intentionally no persistence and no UI toggle in Phase 0. Overrides exist so that
 * tests and, later, gated internal builds can flip a flag without a global default change. The
 * default map is the source of truth for "what ships to users": all false.
 */
enum class EditorFeatureFlag(
    /** Stable string key for logging / future remote-config plumbing (not wired in Phase 0). */
    val key: String,
    /** Ships-to-users value. Phase 0 requires every flag to default to `false`. */
    val defaultEnabled: Boolean,
    val description: String,
) {
    // ── Parallel-host flags (product identity is a host, never a layout flag; §2, §17.2) ──
    FLOW_HOST(
        key = "editor.host.flow",
        defaultEnabled = false,
        description = "Route Notes through the new FlowHost instead of the legacy StructuredBlockEditorHost (Phase 1).",
    ),
    DOCUMENT_HOST(
        key = "editor.host.document",
        defaultEnabled = false,
        description = "Route bounded documents through the new DocumentHost (Phase 2).",
    ),
    SPREADSHEET_HOST(
        key = "editor.host.spreadsheet",
        defaultEnabled = false,
        description = "Expose the SpreadsheetHost as a distinct workspace (Phase 4).",
    ),

    // ── Shared-engine adoption flags (engines exist in Phase 0; adoption is gated per phase) ──
    HISTORY_ENGINE(
        key = "editor.engine.history",
        defaultEnabled = false,
        description = "Use the canonical History Engine for undo/redo instead of the per-session snapshot deque.",
    ),
    RECOVERY_SNAPSHOTS(
        key = "editor.engine.recovery",
        defaultEnabled = false,
        description = "Auto-create recovery snapshots before risky ops and expose restore (gates all content migration).",
    ),
    COMMAND_REGISTRY(
        key = "editor.engine.commands",
        defaultEnabled = false,
        description = "Invoke edits through the unified semantic Command Registry (§4).",
    ),
    SELECTION_ENGINE(
        key = "editor.engine.selection",
        defaultEnabled = false,
        description = "Track selection through the unified Selection Engine across all domains (§4.5–§4.14).",
    ),
    SURFACE_COORDINATOR(
        key = "editor.engine.surfaces",
        defaultEnabled = false,
        description = "Model tool surfaces and dismissal policies through the Surface Coordinator (§5.1–§5.6).",
    ),
    ADAPTIVE_PRESENTATION(
        key = "editor.engine.adaptive",
        defaultEnabled = false,
        description = "Adapt one tool state across form factors via the Adaptive Presentation runtime (§2.6, §3).",
    ),
    INTERACTION_MEMORY(
        key = "editor.engine.memory",
        defaultEnabled = false,
        description = "Scope interaction state through the six-scope Interaction Memory runtime (§5.7, §18.7).",
    ),

    // ── Domain flags (later phases; declared now so the substrate is complete) ──
    COLOR_STUDIO(key = "editor.domain.color", defaultEnabled = false, description = "Color Studio / canonical ColorValue (Phase 3)."),
    NAMED_STYLES(key = "editor.domain.styles", defaultEnabled = false, description = "Named styles & tokens (Phase 3)."),
    OFFLINE_FONTS(key = "editor.domain.fonts", defaultEnabled = false, description = "Bundled/offline guaranteed font families (Phase 3)."),
    CALC_ENGINE(key = "editor.domain.calc", defaultEnabled = false, description = "Shared calculation/data engine (Phase 4)."),
    CHART_STUDIO(key = "editor.domain.chart", defaultEnabled = false, description = "ChartDocument model + Chart Studio (Phase 5)."),
    IMAGE_FOCUS(key = "editor.domain.image", defaultEnabled = false, description = "Image Focus workspace (Phase 6)."),
    RELATIONSHIPS(key = "editor.domain.relationships", defaultEnabled = false, description = "Relationship engine + Connect mode + comments/review (Phase 7)."),
    IMPORT_EXPORT_RAW(key = "editor.domain.io", defaultEnabled = false, description = "Import/Export/Fidelity/Raw Source (Phase 8)."),
    GUIDE(key = "editor.domain.guide", defaultEnabled = false, description = "Guide / progressive discovery (Phase 9)."),
    HISTORY_UI(
        key = "editor.domain.history.ui",
        defaultEnabled = false,
        description = "History browser, filtered/contextual undo presentation, named snapshots and the recovery browser (Phase 10).",
    ),
    ;

    companion object {
        fun byKey(key: String): EditorFeatureFlag? = entries.firstOrNull { it.key == key }
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

    /** Sets a process-local override. Intended for tests and gated internal builds only. */
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

    /** True iff every flag currently resolves to disabled. */
    fun allDisabled(): Boolean = EditorFeatureFlag.entries.none { isEnabled(it) }

    companion object {
        /** The ships-to-users configuration: no overrides, every flag at its default (all false). */
        fun productionDefaults(): EditorFeatureFlags = EditorFeatureFlags(ConcurrentHashMap())

        /** A configuration seeded with the given overrides. For tests exercising gated paths. */
        fun withOverrides(vararg pairs: Pair<EditorFeatureFlag, Boolean>): EditorFeatureFlags =
            EditorFeatureFlags(ConcurrentHashMap(pairs.toMap()))
    }
}
