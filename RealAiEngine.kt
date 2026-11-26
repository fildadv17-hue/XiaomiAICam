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
    LEICA_WARM("Leica Warm", tempShift = 24f, contrast = 1.08f, saturation = 1.06f),
    LEICA_COLD("Leica Cold", tempShift = -22f, contrast = 1.07f, saturation = 0.96f),
    LEICA_VIBRANT("Leica Vibrant", tempShift = 4f, contrast = 1.12f, saturation = 1.20f),
    LEICA_NATURAL("Leica Natural", tempShift = 0f, contrast = 1.03f, saturation = 1.00f)
}

enum class PhotoQuality(val label: String, val jpegQuality: Int, val aiStrength: Float) {
    STANDARD("Standard", 85, 0.12f),
    HIGH("High Pro", 95, 0.22f),
    AI_ULTRA("AI Ultra 250x", 100, 0.30f)
}

data class CapturedPhotoPair(
    val withoutAi: Bitmap,
    val withAi: Bitmap
)

object RealAiEngine {

    fun calculateZoomSplit(requestedZoom: Float, hardwareMaxZoom: Float): Pair<Float, Float> {
        val clampedRequest = requestedZoom.coerceIn(1f, 250f)
        val hwZoom = min(clampedRequest, hardwareMaxZoom)
        val digitalMultiplier = max(1f, clampedRequest / hwZoom)
        return Pair(hwZoom, digitalMultiplier)
    }

    fun processCapturePair(
        source: Bitmap,
        digitalMultiplier: Float,
        leicaProfile: LeicaProfile,
        quality: PhotoQuality
    ): CapturedPhotoPair {
        val outW = min(source.width, 2048).coerceAtLeast(1)
        val outH = min(source.height, 2048).coerceAtLeast(1)

        // 1. WITHOUT AI: Smoothly scaled zoom crop without harsh sharpening
        val withoutAi = cropAndScaleSmooth(source, digitalMultiplier, outW, outH)

        // 2. WITH AI: Leica color science + gentle unsharp mask (less blockiness)
        val graded = applyLeicaColorMatrix(withoutAi, leicaProfile)
        val withAi = applyGentleAiEnhance(graded, strength = quality.aiStrength)
        if (graded !== withAi && graded !== withoutAi) {
            graded.recycle()
        }

        return CapturedPhotoPair(withoutAi = withoutAi, withAi = withAi)
    }

    private fun cropAndScaleSmooth(
        src: Bitmap,
        zoomFactor: Float,
        targetW: Int,
        targetH: Int
    ): Bitmap {
        val output = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        val effectiveZoom = max(1f, zoomFactor)
        val cropW = (src.width / effectiveZoom).coerceAtLeast(64f)
        val cropH = (src.height / effectiveZoom).coerceAtLeast(64f)
        val left = ((src.width - cropW) / 2f).coerceAtLeast(0f)
        val top = ((src.height - cropH) / 2f).coerceAtLeast(0f)

        val srcRect = RectF(left, top, min(src.width.toFloat(), left + cropW), min(src.height.toFloat(), top + cropH))
        val dstRect = RectF(0f, 0f, targetW.toFloat(), targetH.toFloat())
        canvas.drawBitmap(src, srcRect, dstRect, paint)
        return output
    }

    private fun applyLeicaColorMatrix(bitmap: Bitmap, profile: LeicaProfile): Bitmap {
        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        val colorMatrix = ColorMatrix().apply { setSaturation(profile.saturation) }
        val c = profile.contrast
        val t = (1f - c) * 128f
        val rShift = max(0f, profile.tempShift)
        val bShift = max(0f, -profile.tempShift)

        val toneMatrix = ColorMatrix(
            floatArrayOf(
                c, 0f, 0f, 0f, t + rShift,
                0f, c, 0f, 0f, t + (rShift * 0.2f),
                0f, 0f, c, 0f, t + bShift,
                0f, 0f, 0f, 1f, 0f
            )
        )
        colorMatrix.postConcat(toneMatrix)
        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
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
                val avg = pixels[idx - w - 1]
                val top = pixels[idx - w]
                val bot = pixels[idx + w]
                val left = pixels[idx - 1]
                val right = pixels[idx + 1]

                // Soft 5-neighbor average to reduce pixel stair-stepping
                val blurR = (Color.red(c) + Color.red(top) + Color.red(bot) + Color.red(left) + Color.red(right) + Color.red(avg)) / 6f
                val blurG = (Color.green(c) + Color.green(top) + Color.green(bot) + Color.green(left) + Color.green(right) + Color.green(avg)) / 6f
                val blurB = (Color.blue(c) + Color.blue(top) + Color.blue(bot) + Color.blue(left) + Color.blue(right) + Color.blue(avg)) / 6f

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
