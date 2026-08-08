package com.norfold.app.editor.guide

import com.norfold.app.editor.command.CommandContext
import com.norfold.app.editor.command.CommandRegistry
import com.norfold.app.editor.command.CommandResult
import com.norfold.app.editor.command.CommandStatus
import com.norfold.app.editor.command.CommandSurface
import com.norfold.app.editor.flags.EditorFeatureFlag
import com.norfold.app.editor.memory.InteractionMemory
import com.norfold.app.editor.memory.MemoryScope
import com.norfold.app.editor.presentation.AdaptivePresentationRuntime
import com.norfold.app.editor.presentation.CompactPreference
import com.norfold.app.editor.presentation.FormFactor
import com.norfold.app.editor.presentation.PresentationMode
import com.norfold.app.editor.presentation.ToolPresentationState
import com.norfold.app.editor.surface.DismissTrigger
import com.norfold.app.editor.surface.DismissalPolicy
import com.norfold.app.editor.surface.SurfaceClass
import com.norfold.app.editor.surface.SurfaceCoordinator
import com.norfold.app.editor.surface.ToolSurface

data class GuideRouteAvailability(val available: Boolean, val reason: String? = null)

interface GuideCommandRouter {
    fun availability(commandId: String): GuideRouteAvailability
    fun execute(commandId: String): CommandResult
}

/** Verifies the target against the real registry, then uses the host's normal invocation seam. */
class RegistryGuideCommandRouter(
    private val registry: CommandRegistry,
    private val context: () -> CommandContext,
    private val invoke: (String) -> CommandResult = { id -> registry.execute(id, context()) },
) : GuideCommandRouter {
    override fun availability(commandId: String): GuideRouteAvailability {
        val command = registry.get(commandId) ?: return GuideRouteAvailability(false, "This action is not registered here.")
        val status: CommandStatus = command.status(context())
        return GuideRouteAvailability(status.isInvokable, status.reason ?: if (!status.isVisible) "This action is not relevant here." else null)
    }

    override fun execute(commandId: String): CommandResult =
        if (registry.get(commandId) == null) CommandResult.Failure("This action is not registered here.") else invoke(commandId)
}

sealed interface GuideShowMeResult {
    data class Invoked(val result: CommandResult) : GuideShowMeResult
    data class Unavailable(val reason: String) : GuideShowMeResult
    data class Explained(val text: String) : GuideShowMeResult
}

/** Content-free counters for Phase-9 performance evidence. */
data class GuideMetrics(
    val eligibilityEvaluations: Long,
    val tipsShown: Long,
    val completionEvents: Long,
    val catalogSearches: Long,
    val activeTargetResolutions: Long,
)

private class MutableGuideMetrics {
    var eligibilityEvaluations = 0L
    var tipsShown = 0L
    var completionEvents = 0L
    var catalogSearches = 0L
    var activeTargetResolutions = 0L
    fun snapshot() = GuideMetrics(eligibilityEvaluations, tipsShown, completionEvents, catalogSearches, activeTargetResolutions)
}

/** GUIDE_DISCOVERY-only persistence adapter with revision and modality isolation. */
class GuideProgressStore(private val memory: InteractionMemory) {
    private fun key(topic: GuideTopic, modality: GuideInputModality) =
        "guide.v1.${topic.id.value}.${modality.name.lowercase()}"

    fun progress(topic: GuideTopic, modality: GuideInputModality): GuideProgress {
        val encoded = memory.get<String>(MemoryScope.GUIDE_DISCOVERY, key(topic, modality))
        val parts = encoded?.split(':', limit = 2).orEmpty()
        val revision = parts.getOrNull(0)?.toIntOrNull()
        val state = parts.getOrNull(1)?.let { runCatching { DiscoveryState.valueOf(it) }.getOrNull() }
        return GuideProgress(
            topic.id,
            modality,
            topic.revision,
            if (revision == topic.revision) state ?: DiscoveryState.UNSEEN else DiscoveryState.UNSEEN,
        )
    }

    fun set(topic: GuideTopic, modality: GuideInputModality, state: DiscoveryState): GuideProgress {
        memory.put(MemoryScope.GUIDE_DISCOVERY, key(topic, modality), "${topic.revision}:${state.name}")
        return GuideProgress(topic.id, modality, topic.revision, state)
    }

    fun reset() = memory.clearScope(MemoryScope.GUIDE_DISCOVERY)
}

/**
 * One shared, offline Guide/progressive-discovery runtime over the existing Phase-0 engines.
 *
 * It has no document controller and therefore cannot create History entries. Context eligibility is driven
 * by semantic host/domain/capability state, not coordinates or document scanning. When GUIDE is off callers
 * do not construct this class at all through [GuideIntegration].
 */
class GuideRuntime(
    val catalog: GuideCatalog,
    private val progressStore: GuideProgressStore,
    private val enabledFeatures: Set<EditorFeatureFlag>,
    private val commandRouter: GuideCommandRouter,
    private val surfaces: SurfaceCoordinator,
    private val presentation: AdaptivePresentationRuntime,
) {
    private val metrics = MutableGuideMetrics()
    private var context: GuideContext? = null
    private var browser = GuideBrowserState()
    private var tip: GuideTipState? = null
    private var unsolicitedUsedThisPeriod = false

    fun context(): GuideContext? = context
    fun browserState(): GuideBrowserState = browser
    fun tipState(): GuideTipState? = tip
    fun metrics(): GuideMetrics = metrics.snapshot()
    fun presentationMode(): PresentationMode? = presentation.stateOf(BROWSER_SURFACE)?.let {
        presentation.modeFor(BROWSER_SURFACE, CompactPreference.SHEET)
    }

    fun isFeatureEnabled(feature: EditorFeatureFlag): Boolean = feature in enabledFeatures

    fun state(topicId: GuideTopicId, modality: GuideInputModality = context?.modality ?: GuideInputModality.TOUCH): DiscoveryState? =
        catalog.topic(topicId)?.let { progressStore.progress(it, modality).state }

    fun updateContext(next: GuideContext): GuideTipState? {
        context = next
        evaluateEligibility(next)
        return maybeShowTip(next)
    }

    /** A meaningful period is host/session driven, never a timer. */
    fun beginMeaningfulPeriod() {
        unsolicitedUsedThisPeriod = false
    }

    fun openBrowser(topicId: GuideTopicId? = null): Boolean {
        val current = context ?: return false
        // A manual request replaces an unsolicited tip; Guide never stacks two announcements.
        if (tip != null) dismissTipTransiently()
        val safeTopic = topicId?.takeIf { id -> availableTopics(current.host).any { it.id == id } }
        browser = browser.copy(open = true, topicId = safeTopic ?: browser.topicId, stepIndex = 0)
        openGuideSurface(BROWSER_SURFACE)
        presentation.present(
            ToolPresentationState(
                toolId = BROWSER_SURFACE,
                selectedTab = browser.category?.name,
                query = browser.query,
                values = mapOf("topic" to browser.topicId?.value.orEmpty(), "step" to browser.stepIndex.toString()),
            ),
            CompactPreference.SHEET,
        )
        return true
    }

    fun closeBrowser() {
        browser = browser.copy(open = false)
        closeGuideSurface(BROWSER_SURFACE)
        presentation.close(BROWSER_SURFACE)
    }

    fun setQuery(query: String) {
        browser = browser.copy(query = query.take(MAX_QUERY_LENGTH))
        updatePresentationState()
    }

    fun setCategory(category: GuideDomain?) {
        browser = browser.copy(category = category, topicId = null, stepIndex = 0)
        updatePresentationState()
    }

    fun selectTopic(topicId: GuideTopicId): Boolean {
        val host = context?.host ?: return false
        if (availableTopics(host).none { it.id == topicId }) return false
        browser = browser.copy(topicId = topicId, stepIndex = 0)
        updatePresentationState()
        return true
    }

    fun nextStep(): Boolean = moveStep(1)
    fun previousStep(): Boolean = moveStep(-1)

    private fun moveStep(delta: Int): Boolean {
        val topic = browser.topicId?.let(catalog::topic) ?: return false
        val last = (topic.steps.size - 1).coerceAtLeast(0)
        val next = (browser.stepIndex + delta).coerceIn(0, last)
        if (next == browser.stepIndex) return false
        browser = browser.copy(stepIndex = next)
        updatePresentationState()
        return true
    }

    fun searchResults(): List<GuideTopic> {
        val host = context?.host ?: return emptyList()
        metrics.catalogSearches++
        return catalog.search(browser.query, host, browser.category).filter(::featureAvailable)
    }

    fun availableTopics(host: GuideHost? = context?.host): List<GuideTopic> =
        host?.let { catalog.topics(it).filter(::featureAvailable) }.orEmpty()

    fun dismissTipTransiently() {
        tip = null
        closeGuideSurface(TIP_SURFACE)
    }

    fun later(topicId: GuideTopicId? = tip?.topicId): Boolean =
        topicId?.let { transitionFromShown(it, DiscoveryState.LATER) } ?: false

    fun snooze(topicId: GuideTopicId? = tip?.topicId): Boolean =
        topicId?.let { transitionFromShown(it, DiscoveryState.SNOOZED) } ?: false

    fun complete(topicId: GuideTopicId? = tip?.topicId): Boolean {
        topicId ?: return false
        val topic = catalog.topic(topicId) ?: return false
        val modality = context?.modality ?: GuideInputModality.TOUCH
        if (progressStore.progress(topic, modality).state == DiscoveryState.LEARNED) return false
        progressStore.set(topic, modality, DiscoveryState.LEARNED)
        metrics.completionEvents++
        if (tip?.topicId == topicId) dismissTipTransiently()
        return true
    }

    fun autoDisable(topicId: GuideTopicId): Boolean {
        val topic = catalog.topic(topicId) ?: return false
        progressStore.set(topic, context?.modality ?: GuideInputModality.TOUCH, DiscoveryState.AUTO_DISABLED)
        if (tip?.topicId == topicId) dismissTipTransiently()
        return true
    }

    fun resetDiscovery() {
        progressStore.reset()
        tip = null
        closeGuideSurface(TIP_SURFACE)
        unsolicitedUsedThisPeriod = false
    }

    fun onCommandSucceeded(commandId: String, surface: CommandSurface) {
        val current = context ?: return
        availableTopics(current.host).forEach { topic ->
            val rule = topic.completion as? GuideCompletionRule.Commands ?: return@forEach
            if (rule.matches(commandId, surface)) complete(topic.id)
        }
    }

    fun onSignal(signal: GuideSignal) {
        val current = context ?: return
        availableTopics(current.host).forEach { topic ->
            val rule = topic.completion as? GuideCompletionRule.Signals ?: return@forEach
            if (signal in rule.values) complete(topic.id)
        }
    }

    fun showMe(topicId: GuideTopicId? = browser.topicId ?: tip?.topicId): GuideShowMeResult {
        topicId ?: return GuideShowMeResult.Unavailable("Choose a Guide topic first.")
        val topic = catalog.topic(topicId) ?: return GuideShowMeResult.Unavailable("That Guide topic is unavailable.")
        if (!featureAvailable(topic)) return GuideShowMeResult.Unavailable("The required feature is disabled.")
        val target = topic.steps.getOrNull(browser.stepIndex)?.target ?: topic.target
            ?: return GuideShowMeResult.Explained(topic.accessibilityDescription)
        metrics.activeTargetResolutions++
        return when (target) {
            is GuideTarget.Command -> {
                val availability = commandRouter.availability(target.commandId)
                if (!availability.available) GuideShowMeResult.Unavailable(availability.reason ?: target.fallbackDescription)
                else GuideShowMeResult.Invoked(commandRouter.execute(target.commandId))
            }
            is GuideTarget.CapabilityTarget -> {
                if (target.capability in (context?.capabilities ?: emptySet())) GuideShowMeResult.Explained(target.fallbackDescription)
                else GuideShowMeResult.Unavailable("That target is not available in the current selection. ${target.fallbackDescription}")
            }
            is GuideTarget.ContextTarget -> GuideShowMeResult.Explained(target.fallbackDescription)
        }
    }

    fun onFormFactorChanged(next: FormFactor): PresentationMode? {
        val represented = presentation.onFormFactorChanged(next) { if (it == BROWSER_SURFACE) CompactPreference.SHEET else CompactPreference.POPOVER }
        return represented[BROWSER_SURFACE]?.mode
    }

    fun back(): Boolean = when {
        browser.open -> { closeBrowser(); true }
        tip != null -> { dismissTipTransiently(); true }
        else -> false
    }

    private fun evaluateEligibility(current: GuideContext) {
        catalog.topics(current.host).forEach { topic ->
            metrics.eligibilityEvaluations++
            if (!featureAvailable(topic) || topic.domain !in current.domains || !current.capabilities.containsAll(topic.requiredCapabilities)) return@forEach
            val progress = progressStore.progress(topic, current.modality)
            if (progress.state != DiscoveryState.UNSEEN) return@forEach
            if (topic.prerequisites.all { state(it, current.modality) == DiscoveryState.LEARNED }) {
                progressStore.set(topic, current.modality, DiscoveryState.ELIGIBLE)
            }
        }
    }

    private fun maybeShowTip(current: GuideContext): GuideTipState? {
        if (!current.canInterrupt || unsolicitedUsedThisPeriod || browser.open || tip != null) return tip
        val topic = catalog.topics(current.host).firstOrNull { candidate ->
            featureAvailable(candidate) && candidate.domain in current.domains &&
                progressStore.progress(candidate, current.modality).state == DiscoveryState.ELIGIBLE
        } ?: return null
        progressStore.set(topic, current.modality, DiscoveryState.SHOWN)
        unsolicitedUsedThisPeriod = true
        metrics.tipsShown++
        return GuideTipState(topic.id).also {
            tip = it
            openGuideSurface(TIP_SURFACE)
        }
    }

    private fun transitionFromShown(topicId: GuideTopicId, next: DiscoveryState): Boolean {
        require(next == DiscoveryState.LATER || next == DiscoveryState.SNOOZED)
        val topic = catalog.topic(topicId) ?: return false
        val modality = context?.modality ?: GuideInputModality.TOUCH
        if (progressStore.progress(topic, modality).state != DiscoveryState.SHOWN) return false
        progressStore.set(topic, modality, next)
        if (tip?.topicId == topicId) dismissTipTransiently()
        return true
    }

    private fun featureAvailable(topic: GuideTopic): Boolean = enabledFeatures.containsAll(topic.requiredFeatures)

    private fun openGuideSurface(id: String) {
        if (surfaces.active()?.id == id) return
        val surface = ToolSurface(
            id = id,
            surfaceClass = SurfaceClass.PICKER_BROWSER,
            dismissal = DismissalPolicy.OUTSIDE_OR_BACK,
            compatibleDomains = setOf("guide"),
        )
        if (surfaces.depth == 0) surfaces.open(surface) else surfaces.openChild(surface)
    }

    private fun closeGuideSurface(id: String) {
        if (surfaces.active()?.id == id) surfaces.dispatch(DismissTrigger.CANCEL)
    }

    private fun updatePresentationState() {
        if (!browser.open) return
        val state = ToolPresentationState(
            toolId = BROWSER_SURFACE,
            selectedTab = browser.category?.name,
            query = browser.query,
            values = mapOf("topic" to browser.topicId?.value.orEmpty(), "step" to browser.stepIndex.toString()),
        )
        if (presentation.stateOf(BROWSER_SURFACE) == null) presentation.present(state, CompactPreference.SHEET)
        else presentation.update(state)
    }

    companion object {
        const val BROWSER_SURFACE = "guide.surface.browser"
        const val TIP_SURFACE = "guide.surface.tip"
        private const val MAX_QUERY_LENGTH = 120
    }
}

/** No construction, catalog scan, memory read, or side effect occurs while GUIDE is disabled. */
object GuideIntegration {
    fun create(enabled: Boolean, factory: () -> GuideRuntime): GuideRuntime? = if (enabled) factory() else null
}
