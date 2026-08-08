package com.norfold.app.editor.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 0 D8 validation-gate coverage for [InteractionMemory] (spec §5.7, §18 rejection #7). The gate item:
 * **the six scopes are proven independent — a transient value does not leak into user-preference** — plus
 * scope-appropriate lifetimes and owner namespacing. Pure JVM.
 */
class InteractionMemoryTest {

    // --- THE D8 GATE: scopes are independent, no leakage (§5.7, §18#7) ------------------------------

    @Test fun sameKeyInEveryScope_holdsIndependentValues() {
        val mem = InteractionMemory()
        mem.put(MemoryScope.TRANSIENT, "x", "transient")
        mem.put(MemoryScope.TOOL_SESSION, "x", "session", owner = "tool")
        mem.put(MemoryScope.ARTIFACT_UI, "x", "artifact", owner = "note:1")
        mem.put(MemoryScope.CONTEXT_PREFERENCE, "x", "context")
        mem.put(MemoryScope.USER_PREFERENCE, "x", "user")
        mem.put(MemoryScope.GUIDE_DISCOVERY, "x", "guide")

        assertEquals("transient", mem.get<String>(MemoryScope.TRANSIENT, "x"))
        assertEquals("session", mem.get<String>(MemoryScope.TOOL_SESSION, "x", owner = "tool"))
        assertEquals("artifact", mem.get<String>(MemoryScope.ARTIFACT_UI, "x", owner = "note:1"))
        assertEquals("context", mem.get<String>(MemoryScope.CONTEXT_PREFERENCE, "x"))
        assertEquals("user", mem.get<String>(MemoryScope.USER_PREFERENCE, "x"))
        assertEquals("guide", mem.get<String>(MemoryScope.GUIDE_DISCOVERY, "x"))
    }

    @Test fun transientValue_doesNotLeakIntoUserPreference() {
        val mem = InteractionMemory()
        mem.put(MemoryScope.TRANSIENT, "fill", "temp-drag-color")
        // Reading the same key from user-preference must NOT see the transient write.
        assertNull("Transient must not surface as a user preference", mem.get<String>(MemoryScope.USER_PREFERENCE, "fill"))
        assertFalse(mem.contains(MemoryScope.USER_PREFERENCE, "fill"))
    }

    @Test fun clearingOneScope_leavesOthersIntact() {
        val mem = InteractionMemory()
        mem.put(MemoryScope.GUIDE_DISCOVERY, "seen-connect", true)
        mem.put(MemoryScope.USER_PREFERENCE, "reduced-motion", true)
        mem.clearScope(MemoryScope.GUIDE_DISCOVERY)
        assertNull(mem.get<Boolean>(MemoryScope.GUIDE_DISCOVERY, "seen-connect"))
        assertEquals(true, mem.get<Boolean>(MemoryScope.USER_PREFERENCE, "reduced-motion"))
    }

    // --- Scope-appropriate lifetime -----------------------------------------------------------------

    @Test fun endInteraction_clearsOnlyTransient() {
        val mem = InteractionMemory()
        mem.put(MemoryScope.TRANSIENT, "hover", "obj-3")
        mem.put(MemoryScope.CONTEXT_PREFERENCE, "fill-picker", "Wheel")
        mem.endInteraction()
        assertEquals(0, mem.size(MemoryScope.TRANSIENT))
        assertEquals("Context preference survives interaction end", "Wheel", mem.get<String>(MemoryScope.CONTEXT_PREFERENCE, "fill-picker"))
    }

    @Test fun endToolSession_clearsOnlyThatToolsSessionState() {
        val mem = InteractionMemory()
        mem.put(MemoryScope.TOOL_SESSION, "category", "Sans", owner = "font-browser")
        mem.put(MemoryScope.TOOL_SESSION, "search", "inter", owner = "font-browser")
        mem.put(MemoryScope.TOOL_SESSION, "layer", "2", owner = "chart-tool")
        mem.endToolSession("font-browser")
        assertNull(mem.get<String>(MemoryScope.TOOL_SESSION, "category", owner = "font-browser"))
        assertEquals("Other tool's session survives", "2", mem.get<String>(MemoryScope.TOOL_SESSION, "layer", owner = "chart-tool"))
    }

    @Test fun closeArtifact_clearsOnlyThatArtifactsUiState() {
        val mem = InteractionMemory()
        mem.put(MemoryScope.ARTIFACT_UI, "outline-tab", "open", owner = "note:1")
        mem.put(MemoryScope.ARTIFACT_UI, "inspector-width", "320", owner = "note:2")
        mem.closeArtifact("note:1")
        assertFalse(mem.contains(MemoryScope.ARTIFACT_UI, "outline-tab", owner = "note:1"))
        assertEquals("Other artifact's UI survives", "320", mem.get<String>(MemoryScope.ARTIFACT_UI, "inspector-width", owner = "note:2"))
    }

    // --- Owner namespacing --------------------------------------------------------------------------

    @Test fun sameKey_differentOwners_doNotCollide() {
        val mem = InteractionMemory()
        mem.put(MemoryScope.ARTIFACT_UI, "zoom", "1.0", owner = "note:1")
        mem.put(MemoryScope.ARTIFACT_UI, "zoom", "2.5", owner = "note:2")
        assertEquals("1.0", mem.get<String>(MemoryScope.ARTIFACT_UI, "zoom", owner = "note:1"))
        assertEquals("2.5", mem.get<String>(MemoryScope.ARTIFACT_UI, "zoom", owner = "note:2"))
    }

    @Test fun ownerScopedPut_withoutOwner_throws() {
        val mem = InteractionMemory()
        assertThrows(IllegalArgumentException::class.java) {
            mem.put(MemoryScope.TOOL_SESSION, "k", "v")
        }
        assertThrows(IllegalArgumentException::class.java) {
            mem.put(MemoryScope.ARTIFACT_UI, "k", "v")
        }
    }

    // --- Typed access + defaults --------------------------------------------------------------------

    @Test fun typedGet_andDefaults() {
        val mem = InteractionMemory()
        mem.put(MemoryScope.USER_PREFERENCE, "units", "mm")
        assertEquals("mm", mem.getOrDefault(MemoryScope.USER_PREFERENCE, "units", "px"))
        assertEquals("px", mem.getOrDefault(MemoryScope.USER_PREFERENCE, "missing", "px"))
    }

    @Test fun remove_dropsSingleEntry() {
        val mem = InteractionMemory()
        mem.put(MemoryScope.CONTEXT_PREFERENCE, "palette", "Swatches")
        mem.remove(MemoryScope.CONTEXT_PREFERENCE, "palette")
        assertFalse(mem.contains(MemoryScope.CONTEXT_PREFERENCE, "palette"))
    }

    @Test fun allSixScopes_exist() {
        assertEquals(6, MemoryScope.entries.size)
    }

    @Test fun durablePrimitiveValues_restoreButTransientAndComplexValuesDoNot() {
        val backend = InMemoryInteractionMemoryBackend()
        InteractionMemory(backend).apply {
            put(MemoryScope.GUIDE_DISCOVERY, "topic", "1:LEARNED")
            put(MemoryScope.USER_PREFERENCE, "motion", false)
            put(MemoryScope.TRANSIENT, "drag", "block-1")
            put(MemoryScope.CONTEXT_PREFERENCE, "complex", listOf("session-only"))
        }
        val restored = InteractionMemory(backend)
        assertEquals("1:LEARNED", restored.get<String>(MemoryScope.GUIDE_DISCOVERY, "topic"))
        assertEquals(false, restored.get<Boolean>(MemoryScope.USER_PREFERENCE, "motion"))
        assertNull(restored.get<String>(MemoryScope.TRANSIENT, "drag"))
        assertNull(restored.get<List<String>>(MemoryScope.CONTEXT_PREFERENCE, "complex"))
    }
}
