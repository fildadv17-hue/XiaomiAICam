package com.fildadv.xiaomiaicam

import android.graphics.*
import kotlin.math.max
import kotlin.math.min

enum class LeicaProfile(
    val label: String,
    val tempShift: Float,
    val contrast: Float,
    val saturation: Float
) {
    LEICA_WARM("Leica Warm", 24f, 1.08f, 1.06f),
    LEICA_COLD("Leica Cold", -22f, 1.07f, 0.96f),
    LEICA_VIBRANT("Leica Vibrant", 4f, 1.12f, 1.20f),
    LEICA_NATURAL("Leica Natural", 0f, 1.03f, 1.00f)
}

enum class PhotoQuality(val label: String, val jpegQuality: Int, val aiStrength: Float) {
    STANDARD("Standard", 85, 0.12f),
    HIGH("High Pro", 95, 0.22f),
    AI_ULTRA("AI Ultra 250x", 100, 0.30f)
}

data class CapturedPhotoPair(val withoutAi: Bitmap, val withAi: Bitmap)

object RealAiEngine {

    fun calculateZoomSplit(requestedZoom: Float, hardwareMaxZoom: Float): Pair<Float, Float> {
        val req = requestedZoom.coerceIn(1f, 250f)
        val hw = min(req, hardwareMaxZoom)
        return Pair(hw, max(1f, req / hw))
    }

    fun processCapturePair(
        source: Bitmap,
        digitalMultiplier: Float,
        leicaProfile: LeicaProfile,
        quality: PhotoQuality
    ): CapturedPhotoPair {
        val outW = min(source.width, 2048).coerceAtLeast(1)
        val outH = min(source.height, 2048).coerceAtLeast(1)
        val withoutAi = cropAndScaleSmooth(source, digitalMultiplier, outW, outH)
        val graded = applyLeicaColorMatrix(withoutAi, leicaProfile)
        val withAi = applyGentleAiEnhance(graded, quality.aiStrength)
        if (graded !== withAi && graded !== withoutAi) graded.recycle()
        return CapturedPhotoPair(withoutAi, withAi)
    }

    private fun cropAndScaleSmooth(src: Bitmap, zoomFactor: Float, targetW: Int, targetH: Int): Bitmap {
        val output = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        val z = max(1f, zoomFactor)
        val cropW = (src.width / z).coerceAtLeast(64f)
        val cropH = (src.height / z).coerceAtLeast(64f)
        val left = ((src.width - cropW) / 2f).coerceAtLeast(0f).toInt()
        val top = ((src.height - cropH) / 2f).coerceAtLeast(0f).toInt()
        val right = min(src.width, left + cropW.toInt()).coerceAtLeast(left + 1)
        val bottom = min(src.height, top + cropH.toInt()).coerceAtLeast(top + 1)

        val srcRect = Rect(left, top, right, bottom)
        val dstRect = RectF(0f, 0f, targetW.toFloat(), targetH.toFloat())
        canvas.drawBitmap(src, srcRect, dstRect, paint)
        return output
    }

    private fun applyLeicaColorMatrix(bitmap: Bitmap, profile: LeicaProfile): Bitmap {
        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        val matrix = ColorMatrix().apply { setSaturation(profile.saturation) }
        val c = profile.contrast
        val t = (1f - c) * 128f
        val rShift = max(0f, profile.tempShift)
        val bShift = max(0f, -profile.tempShift)

        matrix.postConcat(
            ColorMatrix(
                floatArrayOf(
                    c, 0f, 0f, 0f, t + rShift,
                    0f, c, 0f, 0f, t + (rShift * 0.2f),
                    0f, 0f, c, 0f, t + bShift,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        paint.colorFilter = ColorMatrixColorFilter(matrix)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return output
    }

    private fun applyGentleAiEnhance(src: Bitmap, strength: Float): Bitmap {
        val w = src.width
        val h = src.height
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val outPixels = IntArray(w * h)

        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val idx = row + x
                val c = pixels[idx]
                val diag = pixels[idx - w - 1]
                val top = pixels[idx - w]
                val bot = pixels[idx + w]
                val left = pixels[idx - 1]
                val right = pixels[idx + 1]

                val blurR = (Color.red(c) + Color.red(top) + Color.red(bot) + Color.red(left) + Color.red(right) + Color.red(diag)) / 6f
                val blurG = (Color.green(c) + Color.green(top) + Color.green(bot) + Color.green(left) + Color.green(right) + Color.green(diag)) / 6f
                val blurB = (Color.blue(c) + Color.blue(top) + Color.blue(bot) + Color.blue(left) + Color.blue(right) + Color.blue(diag)) / 6f

                val r = (Color.red(c) + strength * (Color.red(c) - blurR)).toInt().coerceIn(0, 255)
                val g = (Color.green(c) + strength * (Color.green(c) - blurG)).toInt().coerceIn(0, 255)
                val b = (Color.blue(c) + strength * (Color.blue(c) - blurB)).toInt().coerceIn(0, 255)

                outPixels[idx] = Color.rgb(r, g, b)
            }
        }
        result.setPixels(outPixels, 0, w, 0, 0, w, h)
        return result
    }
}
// END OF FILE

