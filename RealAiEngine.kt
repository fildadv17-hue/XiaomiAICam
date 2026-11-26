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
    LEICA_WARM("Leica Warm", tempShift = 28f, contrast = 1.12f, saturation = 1.08f),
    LEICA_COLD("Leica Cold", tempShift = -26f, contrast = 1.10f, saturation = 0.95f),
    LEICA_VIBRANT("Leica Vibrant", tempShift = 4f, contrast = 1.18f, saturation = 1.28f),
    LEICA_NATURAL("Leica Natural", tempShift = 0f, contrast = 1.05f, saturation = 1.00f)
}

enum class PhotoQuality(val label: String, val jpegQuality: Int, val sharpenStrength: Float) {
    STANDARD("Standard", 82, 0.15f),
    HIGH("High Pro", 94, 0.35f),
    AI_ULTRA("AI Ultra 250x", 100, 0.55f)
}

object RealAiEngine {

    /**
     * Splits requested 1x..250x zoom into:
     * 1) Hardware CameraX zoom ratio (up to Xiaomi 14T max hardware zoom)
     * 2) Digital AI crop multiplier for the remaining magnification up to 250x
     */
    fun calculateZoomSplit(requestedZoom: Float, hardwareMaxZoom: Float): Pair<Float, Float> {
        val clampedRequest = requestedZoom.coerceIn(1f, 250f)
        val hwZoom = min(clampedRequest, hardwareMaxZoom)
        val digitalMultiplier = max(1f, clampedRequest / hwZoom)
        return Pair(hwZoom, digitalMultiplier)
    }

    /**
     * Applies 250x digital crop, Leica Warm/Cold/Vibrant/Natural grading,
     * and AI Liquid edge reconstruction.
     */
    fun processCapturedImage(
        source: Bitmap,
        digitalMultiplier: Float,
        leicaProfile: LeicaProfile,
        quality: PhotoQuality,
        aiLiquidEnhanceEnabled: Boolean
    ): Bitmap {
        // 1. Digital Crop up to 250x equivalent
        val cropped = if (digitalMultiplier > 1.01f) {
            val cropWidth = (source.width / digitalMultiplier).toInt().coerceAtLeast(32)
            val cropHeight = (source.height / digitalMultiplier).toInt().coerceAtLeast(32)
            val startX = (source.width - cropWidth) / 2
            val startY = (source.height - cropHeight) / 2
            val sub = Bitmap.createBitmap(source, startX, startY, cropWidth, cropHeight)
            Bitmap.createScaledBitmap(sub, source.width, source.height, true)
        } else {
            source.copy(Bitmap.Config.ARGB_8888, true)
        }

        // 2. Apply Leica Color Grading (Warm / Cold / Vibrant / Natural)
        val graded = applyLeicaColorMatrix(cropped, leicaProfile)

        // 3. Apply AI Detail Sharpening when enabled or zoomed past hardware limits
        return if (aiLiquidEnhanceEnabled || digitalMultiplier > 1.5f) {
            applyAiSharpen(graded, strength = quality.sharpenStrength)
        } else {
            graded
        }
    }

    private fun applyLeicaColorMatrix(bitmap: Bitmap, profile: LeicaProfile): Bitmap {
        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val colorMatrix = ColorMatrix().apply {
            setSaturation(profile.saturation)
        }

        val c = profile.contrast
        val t = (1f - c) * 128f
        val rShift = if (profile.tempShift > 0) profile.tempShift else 0f
        val bShift = if (profile.tempShift < 0) -profile.tempShift else 0f

        val toneMatrix = ColorMatrix(
            floatArrayOf(
                c, 0f, 0f, 0f, t + rShift,
                0f, c, 0f, 0f, t + (rShift * 0.25f),
                0f, 0f, c, 0f, t + bShift,
                0f, 0f, 0f, 1f, 0f
            )
        )
        colorMatrix.postConcat(toneMatrix)
        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return output
    }

    private fun applyAiSharpen(src: Bitmap, strength: Float): Bitmap {
        val w = src.width
        val h = src.height
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        val outPixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val idx = y * w + x
                val center = pixels[idx]
                val top = pixels[(y - 1) * w + x]
                val bottom = pixels[(y + 1) * w + x]
                val left = pixels[y * w + (x - 1)]
                val right = pixels[y * w + (x + 1)]

                val r = sharpenChannel(Color.red(center), Color.red(top), Color.red(bottom), Color.red(left), Color.red(right), strength)
                val g = sharpenChannel(Color.green(center), Color.green(top), Color.green(bottom), Color.green(left), Color.green(right), strength)
                val b = sharpenChannel(Color.blue(center), Color.blue(top), Color.blue(bottom), Color.blue(left), Color.blue(right), strength)

                outPixels[idx] = Color.rgb(r, g, b)
            }
        }
        result.setPixels(outPixels, 0, w, 0, 0, w, h)
        return result
    }

    private fun sharpenChannel(c: Int, t: Int, b: Int, l: Int, r: Int, s: Float): Int {
        val laplacian = (4 * c) - (t + b + l + r)
        return (c + (laplacian * s)).toInt().coerceIn(0, 255)
    }
}
