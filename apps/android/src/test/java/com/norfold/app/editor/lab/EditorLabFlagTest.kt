package com.norfold.app.editor.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [EditorLabFlag].
 *
 * These guard the invariants the rest of the Editor Lab relies on: nothing ships on, keys are unique and
 * namespaced so they cannot collide with an architectural flag, every entry is presentable, and [byKey] is the
 * exact inverse of [EditorLabFlag.key] for round-tripping persisted values.
 */
class EditorLabFlagTest {

    @Test
    fun everyExperimentDefaultsOff() {
        EditorLabFlag.entries.forEach { flag ->
            assertFalse("${flag.name} must default off — experiments never ship enabled", flag.defaultEnabled)
        }
    }

    @Test
    fun keysAreUnique() {
        val keys = EditorLabFlag.entries.map { it.key }
        assertEquals("Editor Lab keys must be unique", keys.size, keys.toSet().size)
    }

    @Test
    fun keysAreNamespacedAndNonBlank() {
        EditorLabFlag.entries.forEach { flag ->
            assertTrue("${flag.name} key must be non-blank", flag.key.isNotBlank())
            assertTrue(
                "${flag.name} key '${flag.key}' must be namespaced 'lab.' so it cannot collide with a shipped flag",
                flag.key.startsWith("lab."),
            )
        }
    }

    @Test
    fun titlesAndDescriptionsArePresentable() {
        EditorLabFlag.entries.forEach { flag ->
            assertTrue("${flag.name} title must be non-blank", flag.title.isNotBlank())
            assertTrue("${flag.name} description must be non-blank", flag.description.isNotBlank())
        }
    }

    @Test
    fun byKeyRoundTripsEveryEntry() {
        EditorLabFlag.entries.forEach { flag ->
            assertSame("byKey must return the flag for its own key", flag, EditorLabFlag.byKey(flag.key))
        }
    }

    @Test
    fun byKeyReturnsNullForUnknownKey() {
        assertNull(EditorLabFlag.byKey("lab.does.not.exist"))
        assertNull(EditorLabFlag.byKey(""))
    }
}
