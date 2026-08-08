package com.norfold.app.editor.memory

/**
 * The six interaction-memory scopes (spec §5.7). Each has a distinct lifetime and keying discipline; they
 * are explicitly **not** one global store (spec §18 rejection #7). The scope determines when a value is
 * cleared and how it is keyed, which is what keeps a transient drag value from ever being read back as a
 * durable user preference.
 */
enum class MemoryScope {
    /** Ephemeral interaction state (hover target, in-flight drag). Cleared when the interaction ends. */
    TRANSIENT,

    /** State for one open tool session (a font browser's category + current-session search). Cleared on tool close. */
    TOOL_SESSION,

    /** Per-artifact UI state (Outline tab, Inspector width, selected chart panel). Keyed by artifact id. */
    ARTIFACT_UI,

    /** A remembered choice for a context (document fill prefers Wheel; chart palette prefers Swatches). Keyed by context. */
    CONTEXT_PREFERENCE,

    /** Durable user-wide preferences (reduced motion, measurement units). Persist across sessions. */
    USER_PREFERENCE,

    /** Guide / progressive-discovery state (what the user has been shown or dismissed). */
    GUIDE_DISCOVERY,

    ;

    /** Scopes whose contract survives an editor/tool session when a persistence backend is supplied. */
    val isDurable: Boolean
        get() = this == CONTEXT_PREFERENCE || this == USER_PREFERENCE || this == GUIDE_DISCOVERY
}
