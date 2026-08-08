package com.norfold.app.ui.editor.image

import android.graphics.Bitmap
import coil.size.Size
import coil.transform.Transformation

/** Bounded preview transformation. Coil invokes it only after target-size-aware decode. */
internal class ImageSharpnessTransformation(amount: Float) : Transformation {
    private val strength = amount.coerceIn(0f, 1f)
    override val cacheKey: String = "norfold-sharpness-${(strength * 1_000f).toInt()}"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        if (strength <= 0f || input.width < 3 || input.height < 3) return input
        val width = input.width
        val height = input.height
        val source = IntArray(width * height)
        val output = IntArray(width * height)
        input.getPixels(source, 0, width, 0, 0, width, height)
        source.copyInto(output)
        val center = 1f + 4f * strength
        val edge = -strength
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val index = row + x
                val value = source[index]
                val left = source[index - 1]
                val right = source[index + 1]
                val top = source[index - width]
                val bottom = source[index + width]
                val alpha = value ushr 24 and 0xff
                val red = sharpenChannel(value ushr 16 and 0xff, left ushr 16 and 0xff, right ushr 16 and 0xff, top ushr 16 and 0xff, bottom ushr 16 and 0xff, center, edge)
                val green = sharpenChannel(value ushr 8 and 0xff, left ushr 8 and 0xff, right ushr 8 and 0xff, top ushr 8 and 0xff, bottom ushr 8 and 0xff, center, edge)
                val blue = sharpenChannel(value and 0xff, left and 0xff, right and 0xff, top and 0xff, bottom and 0xff, center, edge)
                output[index] = alpha shl 24 or (red shl 16) or (green shl 8) or blue
            }
        }
        return Bitmap.createBitmap(output, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun sharpenChannel(centerValue: Int, left: Int, right: Int, top: Int, bottom: Int, centerWeight: Float, edgeWeight: Float): Int =
        (centerValue * centerWeight + (left + right + top + bottom) * edgeWeight).toInt().coerceIn(0, 255)
}
