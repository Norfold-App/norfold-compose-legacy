package com.norfold.app.ui.editor.image

import android.graphics.Bitmap
import coil.size.Size
import coil.transform.Transformation
import com.norfold.app.editor.image.ImageFilterKind
import kotlin.math.roundToInt

/**
 * Deterministic bitmap effects for the filters that cannot be expressed as a 4x5 colour matrix.
 * This stays in Coil's cacheable preview pipeline: the original URI is never rewritten or saved.
 */
internal class ImageCreativeFilterTransformation(
    private val filter: ImageFilterKind,
    intensity: Float,
) : Transformation {
    private val strength = intensity.coerceIn(0f, 1f)
    override val cacheKey = "norfold-creative-${filter.name.lowercase()}-${(strength * 1_000f).roundToInt()}"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        if (strength <= 0f || input.width <= 1 || input.height <= 1) return input
        return when (filter) {
            ImageFilterKind.GRAIN -> grain(input)
            ImageFilterKind.PIXELATE -> pixelate(input)
            ImageFilterKind.POSTERIZE -> posterize(input)
            ImageFilterKind.EMBOSS -> emboss(input)
            else -> input
        }
    }

    private fun mutableCopy(input: Bitmap): Pair<Bitmap, IntArray> {
        val output = Bitmap.createBitmap(input.width, input.height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(input.width * input.height)
        input.getPixels(pixels, 0, input.width, 0, 0, input.width, input.height)
        return output to pixels
    }

    private fun finish(output: Bitmap, pixels: IntArray): Bitmap = output.apply {
        setPixels(pixels, 0, width, 0, 0, width, height)
    }

    private fun grain(input: Bitmap): Bitmap {
        val (output, pixels) = mutableCopy(input)
        val amount = (strength * 42f).roundToInt()
        pixels.indices.forEach { index ->
            // Fixed spatial hash: previews never shimmer or change after a redraw.
            val noise = (((index * 1103515245 + 12345) ushr 16) and 0x7fff) % (amount * 2 + 1) - amount
            val pixel = pixels[index]
            pixels[index] = argb(pixel ushr 24, red(pixel) + noise, green(pixel) + noise, blue(pixel) + noise)
        }
        return finish(output, pixels)
    }

    private fun pixelate(input: Bitmap): Bitmap {
        val (output, pixels) = mutableCopy(input)
        val block = (2 + strength * 26f).roundToInt()
        for (top in 0 until input.height step block) for (left in 0 until input.width step block) {
            val sample = pixels[top * input.width + left]
            for (y in top until minOf(top + block, input.height)) for (x in left until minOf(left + block, input.width)) {
                pixels[y * input.width + x] = sample
            }
        }
        return finish(output, pixels)
    }

    private fun posterize(input: Bitmap): Bitmap {
        val (output, pixels) = mutableCopy(input)
        val levels = (10f - strength * 7f).roundToInt().coerceAtLeast(2)
        fun band(value: Int): Int = ((value * (levels - 1) / 255f).roundToInt() * 255 / (levels - 1))
        pixels.indices.forEach { index ->
            val pixel = pixels[index]
            pixels[index] = argb(pixel ushr 24, band(red(pixel)), band(green(pixel)), band(blue(pixel)))
        }
        return finish(output, pixels)
    }

    private fun emboss(input: Bitmap): Bitmap {
        val (output, pixels) = mutableCopy(input)
        val source = pixels.copyOf()
        val mix = strength.coerceAtLeast(0.05f)
        for (y in 1 until input.height - 1) for (x in 1 until input.width - 1) {
            val before = source[(y - 1) * input.width + x - 1]
            val after = source[(y + 1) * input.width + x + 1]
            val pixel = source[y * input.width + x]
            fun channel(from: Int, to: Int, original: Int) = (128 + (to - from) * mix + (original - 128) * (1f - mix)).roundToInt()
            pixels[y * input.width + x] = argb(
                pixel ushr 24,
                channel(red(before), red(after), red(pixel)),
                channel(green(before), green(after), green(pixel)),
                channel(blue(before), blue(after), blue(pixel)),
            )
        }
        return finish(output, pixels)
    }

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int) =
        ((alpha and 0xff) shl 24) or ((red.coerceIn(0, 255) and 0xff) shl 16) or
            ((green.coerceIn(0, 255) and 0xff) shl 8) or (blue.coerceIn(0, 255) and 0xff)
    private fun red(pixel: Int) = pixel shr 16 and 0xff
    private fun green(pixel: Int) = pixel shr 8 and 0xff
    private fun blue(pixel: Int) = pixel and 0xff
}
