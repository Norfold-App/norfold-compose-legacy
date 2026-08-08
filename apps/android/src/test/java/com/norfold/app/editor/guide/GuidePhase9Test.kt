package com.norfold.app.editor.guide

import com.norfold.app.editor.command.CommandResult
import com.norfold.app.editor.command.CommandSurface
import com.norfold.app.editor.flags.EditorFeatureFlag
import com.norfold.app.editor.memory.InMemoryInteractionMemoryBackend
import com.norfold.app.editor.memory.InteractionMemory
import com.norfold.app.editor.memory.MemoryScope
import com.norfold.app.editor.presentation.AdaptivePresentationRuntime
import com.norfold.app.editor.presentation.FormFactor
import com.norfold.app.editor.surface.SurfaceCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidePhase9Test {
    private class Router(
        var available: Boolean = true,
        var result: CommandResult = CommandResult.Success(),
    ) : GuideCommandRouter {
        val invoked = mutableListOf<String>()
        override fun availability(commandId: String) = GuideRouteAvailability(available, if (available) null else "Unavailable here")
        override fun execute(commandId: String): CommandResult { invoked += commandId; return result }
    }

    private fun topic(
        id: String = "guide.test",
        domain: GuideDomain = GuideDomain.FLOW,
        hosts: Set<GuideHost> = setOf(GuideHost.FLOW),
        feature: EditorFeatureFlag? = null,
        target: GuideTarget? = GuideTarget.Command("test.command", "Use the visible test command"),
        completion: GuideCompletionRule = GuideCompletionRule.Manual,
        revision: Int = 1,
    ) = GuideTopic(
        id = GuideTopicId(id), title = "Test topic", explanation = "A concise explanation",
        domain = domain, hosts = hosts, target = target,
        steps = listOf(GuideStep("one", "First step", "Use the visible control", target = target)),
        requiredFeatures = feature?.let(::setOf).orEmpty(), completion = completion, revision = revision,
    )

    private data class Fixture(
        val runtime: GuideRuntime,
        val memory: InteractionMemory,
        val router: Router,
        val topic: GuideTopic,
    )

    private fun fixture(
        topic: GuideTopic = topic(),
        features: Set<EditorFeatureFlag> = emptySet(),
        memory: InteractionMemory = InteractionMemory(),
    ): Fixture {
        val router = Router()
        return Fixture(
            GuideRuntime(
                GuideCatalog(listOf(topic)), GuideProgressStore(memory), features, router,
                SurfaceCoordinator(), AdaptivePresentationRuntime(FormFactor(390, 844)),
            ),
            memory,
            router,
            topic,
        )
    }

    private fun GuideRuntime.flowContext(
        blockers: Set<GuideBlocker> = emptySet(),
        modality: GuideInputModality = GuideInputModality.TOUCH,
    ) = updateContext(GuideContext(GuideHost.FLOW, blockers = blockers, modality = modality))

    @Test fun guideFlagDefaultsOff_andDisabledIntegrationConstructsNothing() {
        assertFalse(EditorFeatureFlag.GUIDE.defaultEnabled)
        var constructed = false
        assertNull(GuideIntegration.create(false) { constructed = true; fixture().runtime })
        assertFalse(constructed)
    }

    @Test fun catalogCoversAllOwnedHostsAndPhaseThreeThroughEightDomains() {
        val catalog = NorfoldGuideCatalog.default
        assertTrue(catalog.size >= 20)
        GuideHost.entries.forEach { assertTrue(catalog.topics(it).isNotEmpty()) }
        setOf(GuideDomain.STYLES, GuideDomain.SPREADSHEET, GuideDomain.CHART, GuideDomain.IMAGE,
            GuideDomain.RELATIONSHIPS, GuideDomain.INTERCHANGE).forEach { assertTrue(catalog.topics(it).isNotEmpty()) }
        assertEquals(catalog.size, GuideTopicId::class.java.let { catalog.search("", GuideHost.FLOW).size +
            catalog.topics(GuideHost.DOCUMENT).count { GuideHost.FLOW !in it.hosts } +
            catalog.topics(GuideHost.SPREADSHEET).count { GuideHost.FLOW !in it.hosts && GuideHost.DOCUMENT !in it.hosts } })
    }

    @Test fun canonicalTransitionsAreDeterministic() {
        val f = fixture()
        f.runtime.flowContext()
        assertEquals(DiscoveryState.SHOWN, f.runtime.state(f.topic.id))
        assertTrue(f.runtime.later())
        assertEquals(DiscoveryState.LATER, f.runtime.state(f.topic.id))
        assertFalse(f.runtime.later())
        assertTrue(f.runtime.complete(f.topic.id))
        assertEquals(DiscoveryState.LEARNED, f.runtime.state(f.topic.id))
        assertFalse(f.runtime.complete(f.topic.id))
    }

    @Test fun blockerPreventsUnsolicitedTipButManualBrowserStillWorks() {
        val f = fixture()
        f.runtime.flowContext(setOf(GuideBlocker.TEXT_COMPOSITION))
        assertEquals(DiscoveryState.ELIGIBLE, f.runtime.state(f.topic.id))
        assertNull(f.runtime.tipState())
        assertTrue(f.runtime.openBrowser(f.topic.id))
        assertEquals(f.topic.id, f.runtime.browserState().topicId)
    }

    @Test fun atMostOneUnsolicitedTipAppearsPerMeaningfulPeriod() {
        val first = topic("guide.first")
        val second = topic("guide.second")
        val runtime = GuideRuntime(
            GuideCatalog(listOf(first, second)), GuideProgressStore(InteractionMemory()), emptySet(), Router(),
            SurfaceCoordinator(), AdaptivePresentationRuntime(FormFactor(390, 844)),
        )
        runtime.flowContext()
        val shown = runtime.tipState()!!.topicId
        runtime.snooze(shown)
        runtime.flowContext()
        assertNull(runtime.tipState())
        assertEquals(1L, runtime.metrics().tipsShown)
        runtime.beginMeaningfulPeriod()
        runtime.flowContext()
        assertNotNull(runtime.tipState())
        assertEquals(2L, runtime.metrics().tipsShown)
    }

    @Test fun manualBrowserReplacesTipAndBackDismissesOneLogicalLevel() {
        val f = fixture()
        f.runtime.flowContext()
        assertNotNull(f.runtime.tipState())
        assertTrue(f.runtime.openBrowser())
        assertNull(f.runtime.tipState())
        assertTrue(f.runtime.browserState().open)
        assertTrue(f.runtime.back())
        assertFalse(f.runtime.browserState().open)
        assertFalse(f.runtime.back())
    }

    @Test fun showMeUsesRegisteredRouteAndReportsUnavailableTargets() {
        val f = fixture()
        f.runtime.flowContext(setOf(GuideBlocker.DRAG))
        f.runtime.openBrowser(f.topic.id)
        assertTrue(f.runtime.showMe() is GuideShowMeResult.Invoked)
        assertEquals(listOf("test.command"), f.router.invoked)
        f.router.available = false
        val result = f.runtime.showMe()
        assertTrue(result is GuideShowMeResult.Unavailable)
        assertTrue((result as GuideShowMeResult.Unavailable).reason.contains("Unavailable"))
    }

    @Test fun commandAndSemanticSignalsCompleteWithoutCreatingDocumentState() {
        val commandTopic = topic(completion = GuideCompletionRule.Commands(exactIds = setOf("test.command")))
        val command = fixture(commandTopic)
        command.runtime.flowContext(setOf(GuideBlocker.DRAG))
        command.runtime.onCommandSucceeded("test.command", CommandSurface.DOCK)
        assertEquals(DiscoveryState.LEARNED, command.runtime.state(commandTopic.id))

        val signalTopic = topic("guide.signal", completion = GuideCompletionRule.Signals(setOf(GuideSignal.FLOW_MULTI_SELECT)))
        val signal = fixture(signalTopic)
        signal.runtime.flowContext(setOf(GuideBlocker.DRAG))
        signal.runtime.onSignal(GuideSignal.FLOW_MULTI_SELECT)
        assertEquals(DiscoveryState.LEARNED, signal.runtime.state(signalTopic.id))
    }

    @Test fun featureGatedTopicsStayUnavailableUntilFeatureIsEnabled() {
        val gated = topic(feature = EditorFeatureFlag.IMAGE_FOCUS)
        val off = fixture(gated)
        off.runtime.flowContext()
        assertNull(off.runtime.tipState())
        assertTrue(off.runtime.availableTopics().isEmpty())
        val on = fixture(gated, setOf(EditorFeatureFlag.IMAGE_FOCUS))
        on.runtime.flowContext()
        assertNotNull(on.runtime.tipState())
    }

    @Test fun prerequisitesBecomeEligibleOnlyAfterTheirDependencyIsLearned() {
        val first = topic("guide.first")
        val second = topic("guide.second").copy(prerequisites = setOf(first.id), priority = 2)
        val runtime = GuideRuntime(
            GuideCatalog(listOf(first, second)), GuideProgressStore(InteractionMemory()), emptySet(), Router(),
            SurfaceCoordinator(), AdaptivePresentationRuntime(FormFactor(390, 844)),
        )
        runtime.flowContext(setOf(GuideBlocker.DRAG))
        assertEquals(DiscoveryState.ELIGIBLE, runtime.state(first.id))
        assertEquals(DiscoveryState.UNSEEN, runtime.state(second.id))
        runtime.complete(first.id)
        runtime.flowContext(setOf(GuideBlocker.DRAG))
        assertEquals(DiscoveryState.ELIGIBLE, runtime.state(second.id))
    }

    @Test fun domainContextShowsRelevantTopicAndSuppressesIrrelevantOnes() {
        val flow = topic("guide.flow")
        val chart = topic("guide.chart", domain = GuideDomain.CHART)
        val runtime = GuideRuntime(
            GuideCatalog(listOf(flow, chart)), GuideProgressStore(InteractionMemory()), emptySet(), Router(),
            SurfaceCoordinator(), AdaptivePresentationRuntime(FormFactor(390, 844)),
        )
        runtime.updateContext(GuideContext(GuideHost.FLOW, domains = setOf(GuideDomain.CHART)))
        assertEquals(chart.id, runtime.tipState()?.topicId)
        assertEquals(DiscoveryState.UNSEEN, runtime.state(flow.id))
    }

    @Test fun everyAdvancedDomainIsFeatureGatedInTheCanonicalCatalog() {
        val required = mapOf(
            GuideDomain.CHART to EditorFeatureFlag.CHART_STUDIO,
            GuideDomain.IMAGE to EditorFeatureFlag.IMAGE_FOCUS,
            GuideDomain.RELATIONSHIPS to EditorFeatureFlag.RELATIONSHIPS,
            GuideDomain.INTERCHANGE to EditorFeatureFlag.IMPORT_EXPORT_RAW,
        )
        required.forEach { (domain, flag) ->
            assertTrue(NorfoldGuideCatalog.default.topics(domain).all { flag in it.requiredFeatures })
        }
    }

    @Test fun progressPersistsLocallyAndIsIsolatedByModalityAndRevision() {
        val backend = InMemoryInteractionMemoryBackend()
        val firstMemory = InteractionMemory(backend)
        val first = fixture(memory = firstMemory)
        first.runtime.flowContext(modality = GuideInputModality.TOUCH)
        first.runtime.complete(first.topic.id)

        val restored = fixture(memory = InteractionMemory(backend))
        restored.runtime.flowContext(blockers = setOf(GuideBlocker.DRAG), modality = GuideInputModality.TOUCH)
        assertEquals(DiscoveryState.LEARNED, restored.runtime.state(restored.topic.id))
        assertEquals(DiscoveryState.UNSEEN, restored.runtime.state(restored.topic.id, GuideInputModality.KEYBOARD))

        val revised = fixture(topic(revision = 2), memory = InteractionMemory(backend))
        revised.runtime.flowContext(setOf(GuideBlocker.DRAG))
        assertEquals(DiscoveryState.ELIGIBLE, revised.runtime.state(revised.topic.id))
    }

    @Test fun resetClearsOnlyGuideDiscovery() {
        val memory = InteractionMemory()
        memory.put(MemoryScope.USER_PREFERENCE, "units", "mm")
        memory.put(MemoryScope.CONTEXT_PREFERENCE, "palette", "wheel")
        val f = fixture(memory = memory)
        f.runtime.flowContext()
        f.runtime.resetDiscovery()
        assertEquals("mm", memory.get<String>(MemoryScope.USER_PREFERENCE, "units"))
        assertEquals("wheel", memory.get<String>(MemoryScope.CONTEXT_PREFERENCE, "palette"))
        assertEquals(DiscoveryState.UNSEEN, f.runtime.state(f.topic.id))
    }

    @Test fun searchIsBoundedCategorizedAndDoesNotScanDocumentContent() {
        val catalog = NorfoldGuideCatalog.default
        val results = catalog.search("chart", GuideHost.DOCUMENT, GuideDomain.CHART, limit = Int.MAX_VALUE)
        assertTrue(results.isNotEmpty())
        assertTrue(results.size <= GuideCatalog.MAX_SEARCH_RESULTS)
        assertTrue(results.all { it.domain == GuideDomain.CHART && GuideHost.DOCUMENT in it.hosts })
    }

    @Test fun adaptivePresentationPreservesBrowserQueryTopicAndStep() {
        val f = fixture()
        f.runtime.flowContext(setOf(GuideBlocker.DRAG))
        f.runtime.openBrowser(f.topic.id)
        f.runtime.setQuery("test")
        val before = f.runtime.browserState()
        f.runtime.onFormFactorChanged(FormFactor(1280, 800))
        assertEquals(before, f.runtime.browserState())
        assertNotNull(f.runtime.presentationMode())
    }

    @Test fun unknownTopicsAndUnavailableContextFailSafely() {
        val f = fixture()
        assertFalse(f.runtime.openBrowser())
        assertFalse(f.runtime.selectTopic(GuideTopicId("missing.topic")))
        assertTrue(f.runtime.showMe(GuideTopicId("missing.topic")) is GuideShowMeResult.Unavailable)
        assertFalse(f.runtime.complete(GuideTopicId("missing.topic")))
    }
}
