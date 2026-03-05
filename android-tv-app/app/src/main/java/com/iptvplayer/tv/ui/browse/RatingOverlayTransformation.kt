package com.iptvplayer.tv.ui.browse

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import coil.size.Size
import coil.transform.Transformation

/**
 * Coil Transformation that draws a star emoji + rating score
 * on the top-left corner of the image bitmap.
 */
class RatingOverlayTransformation(
    private val rating: String
) : Transformation {

    override val cacheKey: String = "rating_overlay_$rating"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val ratingValue = rating.toDoubleOrNull() ?: return input
        if (ratingValue <= 0) return input

        val output = input.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)

        val density = output.width / 260f // approximate density factor based on card width
        val textSize = 14f * density.coerceIn(0.8f, 2.5f)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setShadowLayer(4f, 1f, 1f, Color.BLACK)
        }

        val ratingText = "\u2B50 ${String.format("%.1f", ratingValue)}"
        val x = 8f * density.coerceIn(0.8f, 2.5f)
        val y = textSize + 6f * density.coerceIn(0.8f, 2.5f)

        // Draw text with shadow for readability on any background
        canvas.drawText(ratingText, x, y, textPaint)

        return output
    }
}
