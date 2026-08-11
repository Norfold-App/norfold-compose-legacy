package com.norfold.app.editor.flags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Certifies the configuration this build actually ships.
 *
 * The three final hosts are no longer being trialled, so their flags are not switches — they are a
 * written statement of what the product is, and this suite is what makes that statement checkable. The
 * failure it exists to prevent is quiet: a default flipped to `false` during debugging and never
 * flipped back compiles, passes every other test, and ships an app in which a finished domain has
 * simply disappeared from every host.
 *
 * Defaults are asserted through [EditorFeatureFlags.productionDefaults], the same constructor the app
 * uses, rather than by reading [EditorFeatureFlag.defaultEnabled] directly — otherwise the assertions
 * would restate the declaration and agree with it no matter what it said.
 */
class FinalHostDefaultsTest {

    private val shipped = EditorFeatureFlags.productionDefaults()

    // ── What ships ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun allThreeHostsAreOnByDefault() {
        assertTrue(shipped.isEnabled(EditorFeatureFlag.FLOW_HOST))
        assertTrue(shipped.isEnabled(EditorFeatureFlag.DOCUMENT_HOST))
        assertTrue(shipped.isEnabled(EditorFeatureFlag.SPREADSHEET_HOST))
        assertTrue("the hosts must stand or fall together", shipped.allHostsEnabled())
    }

    @Test
    fun everyFlagExceptCollaborationIsOnByDefault() {
        // Stated as "everything except one" rather than as a list of names: a flag added later is
        // covered by this test the day it is added, and its author has to decide deliberately whether
        // it belongs in the exception.
        val off = EditorFeatureFlag.entries.filterNot(shipped::isEnabled)

        assertEquals(listOf(EditorFeatureFlag.COLLABORATION), off)
    }

    @Test
    fun collaborationIsOffBecauseNoBackendIsDeployedRatherThanBecauseItIsUnfinished() {
        // The one deliberate `false`. It gates opening live transport, so defaulting it on would make
        // every editor session attempt a connection that cannot succeed. Local durable history and the
        // merge engine are not gated here, which is why they are on above.
        assertFalse(shipped.isEnabled(EditorFeatureFlag.COLLABORATION))
        assertFalse(EditorFeatureFlag.COLLABORATION.defaultEnabled)
    }

    @Test
    fun theShippedSetIsExactlyTheFlagsThatResolveEnabled() {
        assertEquals(EditorFeatureFlag.entries.filter(shipped::isEnabled).toSet(), EditorFeatureFlag.SHIPPED)
    }

    @Test
    fun aFreshRegistryIsTheDefaultConfiguration() {
        assertTrue(shipped.isDefaultConfiguration())
    }

    // ── Nothing in the product can change it ──────────────────────────────────────────────────────

    @Test
    fun everyFlagKeyIsUniqueAndResolvesBackToItsOwnFlag() {
        // The keys are the stable identity for logging and any future remote config. Two flags sharing
        // one key would make a later remote override silently move the wrong feature.
        val keys = EditorFeatureFlag.entries.map { it.key }
        assertEquals("flag keys must be unique", keys.size, keys.toSet().size)
        EditorFeatureFlag.entries.forEach { assertEquals(it, EditorFeatureFlag.byKey(it.key)) }
        assertEquals(null, EditorFeatureFlag.byKey("editor.host.legacy"))
    }

    @Test
    fun anOverrideIsScopedToItsOwnRegistryAndNeverLeaksIntoTheShippedDefaults() {
        // Overrides exist so a test can pin a configuration. If one leaked into the production
        // registry, a single test could turn a domain off for the whole app.
        val pinned = EditorFeatureFlags.withOverrides(EditorFeatureFlag.CHART_STUDIO to false)

        assertFalse(pinned.isEnabled(EditorFeatureFlag.CHART_STUDIO))
        assertTrue(
            "the shipped registry is unaffected",
            EditorFeatureFlags.productionDefaults().isEnabled(EditorFeatureFlag.CHART_STUDIO),
        )
        assertFalse(pinned.isDefaultConfiguration())
    }

    @Test
    fun clearingAnOverrideRestoresTheShippedValueRatherThanTheLastOneSet() {
        val flags = EditorFeatureFlags.productionDefaults()
        flags.setOverride(EditorFeatureFlag.DOCUMENT_HOST, false)
        assertFalse(flags.isEnabled(EditorFeatureFlag.DOCUMENT_HOST))

        flags.clearOverride(EditorFeatureFlag.DOCUMENT_HOST)

        assertTrue(flags.isEnabled(EditorFeatureFlag.DOCUMENT_HOST))
        assertTrue(flags.isDefaultConfiguration())
    }

    @Test
    fun aFullyDisabledConfigurationIsReachableOnlyByExplicitlyOverridingEveryFlag() {
        // `allDisabled` describes a state no shipped build can be in. Asserting that it is reachable
        // only this way is what makes it meaningful as a guard elsewhere.
        val flags = EditorFeatureFlags.productionDefaults()
        assertFalse(flags.allDisabled())

        EditorFeatureFlag.entries.forEach { flags.setOverride(it, false) }

        assertTrue(flags.allDisabled())
        assertFalse(flags.allHostsEnabled())
        flags.clearAllOverrides()
        assertTrue("and clearing returns the shipped configuration", flags.isDefaultConfiguration())
    }
}
