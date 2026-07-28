package com.norfold.app.editor.selection

/**
 * One selectable thing (spec §4.5–§4.6). A target names its [granularity] (which fixes its
 * [SelectionDomain]) and a stable [id]. [locked] and [hidden] carry the visibility/lock policy from
 * §4.13; both stay selectable/inspectable per spec (hidden objects are reachable via Layers/reveal),
 * so the engine never silently drops them — commands explain the constraint instead.
 */
data class SelectionTarget(
    val id: String,
    val granularity: SelectionGranularity,
    val locked: LockScope = LockScope.NONE,
    val hidden: Boolean = false,
) {
    val domain: SelectionDomain get() = granularity.domain
}

/**
 * The kinds of selection state that coexist (spec §4.5). Each is a *distinct* piece of state; notably a
 * text caret/range is not the same object as a structural selection, and — critically — remote collaborator
 * selection never shares state with local selection (§4.5, enforced by [SelectionEngine] holding remote
 * selections in a separate map).
 */
enum class SelectionKind {
    PRIMARY,
    SECONDARY,
    TEXT_CARET,
    TEXT_RANGE,
    STRUCTURAL,
    HOVER_PREVIEW,
    REMOTE,
}

/**
 * How a command resolves against the current selection given its declared applicability policy
 * (spec §4.10). Mirrors [com.norfold.app.editor.command.ApplicabilityPolicy] outcomes but is computed by
 * the Selection Engine because only it knows the concrete targets and their capabilities.
 *
 * Silent partial application is rejected: [EligibleSubset] always carries the affected/total counts so the
 * UI can state "affects N of M", and a policy that can satisfy nothing resolves to [NotApplicable].
 */
sealed interface ApplicabilityResolution {
    /** Every target is eligible; the command applies to all of them. */
    data class AllTargets(val count: Int) : ApplicabilityResolution

    /** Only some targets are eligible; [affected] of [total] will change. */
    data class EligibleSubset(val affected: Int, val total: Int) : ApplicabilityResolution

    /** Applies to the single primary target. */
    data class PrimaryOnly(val targetId: String) : ApplicabilityResolution

    /** The whole selection is treated as one composite unit. */
    data class AsUnit(val count: Int) : ApplicabilityResolution

    /** No eligible target; the command cannot apply. */
    data object NotApplicable : ApplicabilityResolution
}
