package com.norfold.app.ui.editor.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import coil.size.Size
import coil.transform.Transformation
import com.norfold.app.editor.image.ImageFreeTransform

/** Applies the persisted four-corner transform to a decode-sized preview without baking the source. */
internal class ImagePerspectiveTransformation(transform: ImageFreeTransform) : Transformation {
    private val corners = transform.normalized()
    override val cacheKey: String = buildString {
        append("norfold-perspective-")
        listOf(corners.topLeft, corners.topRight, corners.bottomRight, corners.bottomLeft).forEach { corner ->
            append((corner.x * 10_000f).toInt()).append(':').append((corner.y * 10_000f).toInt()).append(';')
        }
    }

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        if (corners.isIdentity() || input.width <= 1 || input.height <= 1) return input
        val width = input.width.toFloat()
        val height = input.height.toFloat()
        val source = floatArrayOf(0f, 0f, width, 0f, width, height, 0f, height)
        val destination = floatArrayOf(
            corners.topLeft.x * width, corners.topLeft.y * height,
            corners.topRight.x * width, corners.topRight.y * height,
            corners.bottomRight.x * width, corners.bottomRight.y * height,
            corners.bottomLeft.x * width, corners.bottomLeft.y * height,
        )
        val matrix = Matrix().apply { setPolyToPoly(source, 0, destination, 0, 4) }
        val output = Bitmap.createBitmap(input.width, input.height, Bitmap.Config.ARGB_8888)
        Canvas(output).drawBitmap(input, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        return output
    }
}
