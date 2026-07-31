package com.norfold.app.domain.style

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The five semantic color domains the canonical spec locks (roadmap Phase 3 §3, spec §8.2).
 *
 * These are not palettes and not themes — they are *meanings*. Two colors can be the same
 * `#2E7D32` and still belong to different domains, and the domain is what decides whether editing one
 * may touch the other. Without this distinction, the Color Studio has no way to refuse the failure the
 * spec names explicitly: a document token being overwritten by an app-chrome token "merely because both
 * are green".
 *
 * A domain therefore travels with every color *target*, not with the [ColorValue] itself. The value is a
 * pure number and must stay reusable everywhere; it is the target that has a meaning.
 */
@Serializable
enum class ColorDomain(val label: String, val description: String) {
    /** Product UI: theme, surfaces, navigation. Owned by the app's appearance settings, not by a document. */
    @SerialName("chrome")
    APP_CHROME("App chrome", "Product interface theme and surfaces"),

    /** Authored content: text, fills, strokes, page backgrounds. What a document token may address. */
    @SerialName("content")
    CONTENT("Content", "Text, object fill and stroke, page background"),

    /** Chart and series palettes. Modelled here so Phase 5 has a seam; chart *editing* is not built. */
    @SerialName("dataviz")
    DATA_VISUALIZATION("Data visualization", "Chart series and categorical palettes"),

    /** Per-collaborator identity colors. Assigned, never authored, and never a document token. */
    @SerialName("collaborator")
    COLLABORATOR_IDENTITY("Collaborator", "Per-person presence and attribution colors"),

    /** Error / warning / success / info. Carries meaning that a user recolouring must not silently break. */
    @SerialName("signal")
    SEMANTIC_SIGNAL("Signal", "Error, warning, success and status colors"),
    ;

    /**
     * Whether a color in this domain may be stored as a **document** color token.
     *
     * Only content and data-visualization colors describe the artifact. Chrome belongs to the app,
     * collaborator identity belongs to a person, and a signal color belongs to a meaning — persisting any
     * of them as a document token would make "the colors in this document" a lie and would let a theme
     * edit reach into content it never authored.
     */
    val tokenizable: Boolean
        get() = this == CONTENT || this == DATA_VISUALIZATION

    /** Whether user recolouring is offered at all. Collaborator identity is assigned by the system. */
    val userEditable: Boolean
        get() = this != COLLABORATOR_IDENTITY
}

/**
 * What a color edit is actually pointed at. The Color Studio is a shared tool, so it must be told the
 * target rather than inferring it — and the target must be expressive enough that Flow and Document can
 * both describe themselves without either host inventing its own color type (§5).
 *
 * [domain] is the semantic domain; [role] is the specific property inside it (`"text"`, `"fill"`,
 * `"stroke"`, `"page.background"`, `"highlight"`); [targetIds] are the content ids the edit applies to;
 * [host] is which editor asked, used only for labels and telemetry, never for behavior.
 */
@Serializable
data class ColorTarget(
    val domain: ColorDomain,
    val role: ColorRole,
    val targetIds: List<String> = emptyList(),
    val host: String = "",
    /** A token id when this target is bound to a document token rather than a literal value. */
    val boundTokenId: String? = null,
    /** Whether this target can accept a gradient, not merely a flat color. */
    val supportsGradient: Boolean = false,
    /** Gradient kinds this target can render, when [supportsGradient]. */
    val gradientKinds: Set<GradientKind> = emptySet(),
) {
    val isMultiTarget: Boolean get() = targetIds.size > 1
    val isBoundToToken: Boolean get() = boundTokenId != null

    /** A description for accessibility and the tool header — never color alone (§31). */
    fun describe(): String = buildString {
        append(role.label)
        if (targetIds.size > 1) append(" · ${targetIds.size} items")
        if (boundTokenId != null) append(" · token")
    }

    fun accepts(kind: GradientKind): Boolean = supportsGradient && kind in gradientKinds
}

/**
 * The specific property a [ColorTarget] addresses. An enum rather than a free string so a typo cannot
 * quietly create a sixth kind of color, and so the Studio can reason about which tools to offer (a
 * stroke has no gradient in Phase 3; a page background has no contrast partner).
 */
@Serializable
enum class ColorRole(val label: String, val domain: ColorDomain) {
    @SerialName("text") TEXT("Text color", ColorDomain.CONTENT),
    @SerialName("highlight") HIGHLIGHT("Highlight", ColorDomain.CONTENT),
    @SerialName("fill") FILL("Fill", ColorDomain.CONTENT),
    @SerialName("stroke") STROKE("Stroke", ColorDomain.CONTENT),
    @SerialName("page_background") PAGE_BACKGROUND("Page background", ColorDomain.CONTENT),
    @SerialName("page_border") PAGE_BORDER("Page border", ColorDomain.CONTENT),
    @SerialName("series") CHART_SERIES("Chart series", ColorDomain.DATA_VISUALIZATION),
    @SerialName("chrome_surface") CHROME_SURFACE("Interface surface", ColorDomain.APP_CHROME),
    @SerialName("chrome_accent") CHROME_ACCENT("Interface accent", ColorDomain.APP_CHROME),
    @SerialName("collaborator") COLLABORATOR("Collaborator color", ColorDomain.COLLABORATOR_IDENTITY),
    @SerialName("signal") SIGNAL("Status color", ColorDomain.SEMANTIC_SIGNAL),
    ;

    /** Whether this role can be a gradient in Phase 3. Text and stroke stay flat; fills and pages do not. */
    val gradientCapable: Boolean
        get() = this == FILL || this == PAGE_BACKGROUND
}
