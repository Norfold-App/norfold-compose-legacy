package com.norfold.app.editor.style

import com.norfold.app.domain.style.ColorValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchLoupeControllerTest {
    private val source = object : ColorSampleSource {
        override val width = 10
        override val height = 8
        override fun sample(x: Int, y: Int) = ColorValue(x, y, 0)
    }

    @Test fun `loupe clamps sampling to host bounds and chooses canonical color`() {
        val controller = TouchLoupeController(source)
        val state = controller.begin(50f, -4f)
        assertTrue(state.visible)
        assertEquals(ColorValue(9, 0, 0), state.sampled)
        assertEquals(ColorValue(9, 0, 0), controller.choose())
        assertFalse(controller.state.visible)
    }

    @Test fun `cancel discards a sampled color`() {
        val controller = TouchLoupeController(source)
        controller.begin(3f, 4f)
        controller.cancel()
        assertNull(controller.choose())
    }
}
