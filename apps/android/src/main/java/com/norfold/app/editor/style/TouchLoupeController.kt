package com.norfold.app.editor.style

import com.norfold.app.domain.style.ColorValue

/**
 * Toolkit-independent seam between Color Studio and a host-provided rendered-pixel sampler.
 *
 * The controller never captures a window itself (which would couple it to either editor host). Flow and
 * Document can provide their own [ColorSampleSource], while the loupe lifecycle, clamping, cancellation and
 * chosen canonical [ColorValue] remain identical and unit-testable.
 */
class TouchLoupeController(private val source: ColorSampleSource) {
    var state: TouchLoupeState = TouchLoupeState()
        private set

    fun begin(x: Float, y: Float): TouchLoupeState = update(x, y, visible = true)

    fun move(x: Float, y: Float): TouchLoupeState =
        if (state.visible) update(x, y, visible = true) else state

    fun choose(): ColorValue? = state.sampled.also { state = TouchLoupeState() }

    fun cancel() { state = TouchLoupeState() }

    private fun update(x: Float, y: Float, visible: Boolean): TouchLoupeState {
        val px = x.coerceIn(0f, source.width.coerceAtLeast(1) - 1f)
        val py = y.coerceIn(0f, source.height.coerceAtLeast(1) - 1f)
        state = TouchLoupeState(visible, px, py, source.sample(px.toInt(), py.toInt()))
        return state
    }
}

data class TouchLoupeState(
    val visible: Boolean = false,
    val x: Float = 0f,
    val y: Float = 0f,
    val sampled: ColorValue? = null,
)

interface ColorSampleSource {
    val width: Int
    val height: Int
    fun sample(x: Int, y: Int): ColorValue?
}
