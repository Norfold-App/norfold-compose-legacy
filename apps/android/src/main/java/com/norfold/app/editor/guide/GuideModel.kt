package com.norfold.app.editor.guide

import com.norfold.app.editor.command.Capability
import com.norfold.app.editor.command.CommandSurface
import com.norfold.app.editor.flags.EditorFeatureFlag

/** Stable, non-localized identity. Visible English is never a persistence key. */
@JvmInline
value class GuideTopicId(val value: String) {
    init { require(value.matches(Regex("[a-z0-9]+(?:[._-][a-z0-9]+)*"))) { "Invalid Guide topic id '$value'" } }
    override fun toString(): String = value
}

enum class GuideHost { FLOW, DOCUMENT, SPREADSHEET }

enum class GuideDomain {
    FLOW,
    DOCUMENT,
    STYLES,
    SPREADSHEET,
    CHART,
    IMAGE,
    RELATIONSHIPS,
    INTERCHANGE,
}

enum class GuideInputModality { TOUCH, KEYBOARD, POINTER, ACCESSIBILITY }

enum class GuideBlocker { DRAG, TEXT_COMPOSITION, CROP, CONFLICT, PERMISSION, RECOVERY }

/** Canonical specification §6.2. Manual replay is separate and never destroys this state. */
enum class DiscoveryState { UNSEEN, ELIGIBLE, SHOWN, LEARNED, LATER, SNOOZED, AUTO_DISABLED }

enum class GuideSignal {
    FLOW_MULTI_SELECT,
    DOCUMENT_REPRESENTATION_CHANGED,
    DOCUMENT_INTENT_CHANGED,
    PAGE_CONTROL_OPENED,
    SCENE_OBJECT_SELECTED,
    SCENE_OBJECT_TRANSFORMED,
    SNAP_GUIDE_USED,
    FORMULA_COMMITTED,
    RANGE_SELECTED,
    NAMED_RANGE_USED,
    VALIDATION_USED,
    CONDITIONAL_FORMATTING_USED,
    CHART_STUDIO_OPENED,
    CHART_DATA_BOUND,
    CHART_DIRECT_MANIPULATION,
    IMAGE_FOCUS_OPENED,
    IMAGE_FOCUS_COMMITTED,
    RELATIONSHIP_MANAGER_OPENED,
    FIDELITY_CONTEXT_OPENED,
    RAW_SOURCE_OPENED,
}

sealed interface GuideTarget {
    val fallbackDescription: String

    data class Command(
        val commandId: String,
        override val fallbackDescription: String,
    ) : GuideTarget

    data class CapabilityTarget(
        val capability: Capability,
        override val fallbackDescription: String,
    ) : GuideTarget

    data class ContextTarget(
        val stableContextId: String,
        override val fallbackDescription: String,
    ) : GuideTarget
}

sealed interface GuideCompletionRule {
    data object Manual : GuideCompletionRule

    data class Commands(
        val exactIds: Set<String> = emptySet(),
        val idPrefixes: Set<String> = emptySet(),
        val requiredSurface: CommandSurface? = null,
    ) : GuideCompletionRule {
        fun matches(commandId: String, surface: CommandSurface): Boolean =
            (requiredSurface == null || requiredSurface == surface) &&
                (commandId in exactIds || idPrefixes.any(commandId::startsWith))
    }

    data class Signals(val values: Set<GuideSignal>) : GuideCompletionRule
}

data class GuideStep(
    val id: String,
    val title: String,
    val instruction: String,
    val accessibilityDescription: String = instruction,
    val target: GuideTarget? = null,
) {
    init {
        require(id.isNotBlank())
        require(title.isNotBlank())
        require(instruction.isNotBlank())
        require(accessibilityDescription.isNotBlank())
    }
}

data class GuideTopic(
    val id: GuideTopicId,
    val title: String,
    val explanation: String,
    val domain: GuideDomain,
    val hosts: Set<GuideHost>,
    val target: GuideTarget? = null,
    val steps: List<GuideStep> = emptyList(),
    val prerequisites: Set<GuideTopicId> = emptySet(),
    val requiredFeatures: Set<EditorFeatureFlag> = emptySet(),
    val requiredCapabilities: Set<Capability> = emptySet(),
    val completion: GuideCompletionRule = GuideCompletionRule.Manual,
    val revision: Int = 1,
    val accessibilityDescription: String = explanation,
    val priority: Int = 100,
) {
    init {
        require(title.isNotBlank())
        require(explanation.isNotBlank())
        require(hosts.isNotEmpty())
        require(revision > 0)
        require(accessibilityDescription.isNotBlank())
        require(steps.map { it.id }.distinct().size == steps.size) { "Duplicate step id in ${id.value}" }
    }
}

data class GuideContext(
    val host: GuideHost,
    val domains: Set<GuideDomain> = setOf(host.defaultDomain()),
    val capabilities: Set<Capability> = emptySet(),
    val blockers: Set<GuideBlocker> = emptySet(),
    val modality: GuideInputModality = GuideInputModality.TOUCH,
    val unsolicitedTipsEnabled: Boolean = true,
) {
    val canInterrupt: Boolean get() = unsolicitedTipsEnabled && blockers.isEmpty()
}

private fun GuideHost.defaultDomain(): GuideDomain = when (this) {
    GuideHost.FLOW -> GuideDomain.FLOW
    GuideHost.DOCUMENT -> GuideDomain.DOCUMENT
    GuideHost.SPREADSHEET -> GuideDomain.SPREADSHEET
}

data class GuideProgress(
    val topicId: GuideTopicId,
    val modality: GuideInputModality,
    val topicRevision: Int,
    val state: DiscoveryState,
)

data class GuideBrowserState(
    val open: Boolean = false,
    val query: String = "",
    val category: GuideDomain? = null,
    val topicId: GuideTopicId? = null,
    val stepIndex: Int = 0,
)

data class GuideTipState(
    val topicId: GuideTopicId,
    val stepIndex: Int = 0,
)
