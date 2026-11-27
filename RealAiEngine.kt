package com.fildadv.xiaomiaicam

import android.graphics.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class LeicaProfile(
    val label: String,
    val tempShift: Float,
    val contrast: Float,
    val saturation: Float
) {
    LEICA_WARM("Leica Warm", 22f, 1.08f, 1.06f),
    LEICA_COLD("Leica Cold", -20f, 1.07f, 0.96f),
    LEICA_VIBRANT("Leica Vibrant", 4f, 1.14f, 1.22f),
    LEICA_NATURAL("Leica Natural", 0f, 1.03f, 1.00f)
}

enum class PhotoQuality(val label: String, val jpegQuality: Int, val aiStrength: Float) {
    STANDARD("Standard", 85, 0.8f),
    HIGH("High Pro", 95, 1.3f),
    AI_ULTRA("AI Ultra 250x", 100, 1.8f)
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
        val aspect = source.height.toFloat() / source.width.toFloat().coerceAtLeast(1f)
        val targetW = 1440
        val targetH = (targetW * aspect).toInt().coerceAtLeast(1)

        val z = max(1f, digitalMultiplier)
        val cropW = (source.width / z).toInt().coerceIn(48, source.width)
        val cropH = (source.height / z).toInt().coerceIn(48, source.height)
        val left = ((source.width - cropW) / 2).coerceAtLeast(0)
        val top = ((source.height - cropH) / 2).coerceAtLeast(0)
        val rawCrop = Bitmap.createBitmap(source, left, top, cropW, cropH)

        // 1. WITHOUT AI: Raw nearest-neighbor pixel blocks (unprocessed)
        val withoutAi = Bitmap.createScaledBitmap(rawCrop, targetW, targetH, false)

        // 2. WITH AI: True pixel-level EPX contour reconstruction + wide-radius clarity + Leica
        val superRes = buildAiPixels(rawCrop, targetW, targetH, quality.aiStrength)
        val withAi = applyLeicaColorMatrix(superRes, leicaProfile)

        if (rawCrop !== source && rawCrop !== withoutAi && rawCrop !== superRes) rawCrop.recycle()
        if (superRes !== withAi) superRes.recycle()

        return CapturedPhotoPair(withoutAi, withAi)
    }

    private fun buildAiPixels(
        rawCrop: Bitmap,
        targetW: Int,
        targetH: Int,
        strength: Float
    ): Bitmap {
        var cur = rawCrop.copy(Bitmap.Config.ARGB_8888, false)

        // Step A: True pixel-by-pixel Scale2x/EPX contour reconstruction BEFORE any blur
        while (cur.width * 2 <= targetW && cur.height * 2 <= targetH && cur.width < 900) {
            val next = scale2xEpx(cur)
            cur.recycle()
            cur = next
        }

        // Step B: Smoothly reach exact target dimensions
        if (cur.width != targetW || cur.height != targetH) {
            val resized = Bitmap.createScaledBitmap(cur, targetW, targetH, true)
            cur.recycle()
            cur = resized
        }

        // Step C: Multi-radius pixel structure & texture synthesis (5x5 clarity + 3x3 crisp detail)
        val detailed = synthesizePixelStructure(cur, strength)
        cur.recycle()
        return detailed
    }

    /**
     * Real pixel-by-pixel Scale2x (EPX) algorithm:
     * Inspects top/bottom/left/right neighbors of every single pixel and splits each
     * pixel into 4 sub-pixels that follow diagonal lines and curves instead of square blocks.
     */
    private fun scale2xEpx(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val outW = w * 2
        val outH = h * 2
        val inPx = IntArray(w * h)
        src.getPixels(inPx, 0, w, 0, 0, w, h)
        val outPx = IntArray(outW * outH)

        for (y in 0 until h) {
            val yUp = max(0, y - 1)
            val yDn = min(h - 1, y + 1)
            for (x in 0 until w) {
                val xLt = max(0, x - 1)
                val xRt = min(w - 1, x + 1)

                val p = inPx[y * w + x]
                val a = inPx[yUp * w + x]
                val b = inPx[y * w + xRt]
                val c = inPx[y * w + xLt]
                val d = inPx[yDn * w + x]

                val p1 = if (similar(c, a) && !similar(c, d) && !similar(a, b)) blend(a, c) else p
                val p2 = if (similar(a, b) && !similar(a, c) && !similar(b, d)) blend(a, b) else p
                val p3 = if (similar(d, c) && !similar(d, b) && !similar(c, a)) blend(d, c) else p
                val p4 = if (similar(b, d) && !similar(b, a) && !similar(d, c)) blend(b, d) else p

                val outRow0 = (y * 2) * outW + (x * 2)
                val outRow1 = outRow0 + outW
                outPx[outRow0] = p1
                outPx[outRow0 + 1] = p2
                outPx[outRow1] = p3
                outPx[outRow1 + 1] = p4
            }
        }
        return Bitmap.createBitmap(outPx, outW, outH, Bitmap.Config.ARGB_8888)
    }

    private fun similar(c1: Int, c2: Int): Boolean {
        val dr = abs(((c1 shr 16) and 0xFF) - ((c2 shr 16) and 0xFF))
        val dg = abs(((c1 shr 8) and 0xFF) - ((c2 shr 8) and 0xFF))
        val db = abs((c1 and 0xFF) - (c2 and 0xFF))
        return (dr + dg + db) < 55
    }

    private fun blend(c1: Int, c2: Int): Int {
        val r = (((c1 shr 16) and 0xFF) + ((c2 shr 16) and 0xFF)) shr 1
        val g = (((c1 shr 8) and 0xFF) + ((c2 shr 8) and 0xFF)) shr 1
        val b = ((c1 and 0xFF) + (c2 and 0xFF)) shr 1
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /**
     * Multi-radius clarity + detail reconstruction:
     * Compares each pixel against a 4-pixel-wide ring (structure/clarity) AND
     * 1-pixel-wide neighbors (fine texture) to carve crisp pixel edges.
     */
    private fun synthesizePixelStructure(src: Bitmap, strength: Float): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val out = px.copyOf()

        for (y in 4 until h - 4) {
            val row = y * w
            for (x in 4 until w - 4) {
                val i = row + x
                val c = px[i]

                // Wide 4px radius samples for macro clarity
                val wL = px[i - 4]; val wR = px[i + 4]
                val wT = px[i - w * 4]; val wB = px[i + w * 4]

                // Tight 1px radius samples for fine pixel edges
                val nL = px[i - 1]; val nR = px[i + 1]
                val nT = px[i - w]; val nB = px[i + w]

                val nr = boostPixel(c, wL, wR, wT, wB, nL, nR, nT, nB, 16, strength)
                val ng = boostPixel(c, wL, wR, wT, wB, nL, nR, nT, nB, 8, strength)
                val nb = boostPixel(c, wL, wR, wT, wB, nL, nR, nT, nB, 0, strength)

                out[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun boostPixel(
        c: Int,
        wL: Int, wR: Int, wT: Int, wB: Int,
        nL: Int, nR: Int, nT: Int, nB: Int,
        shift: Int, strength: Float
    ): Int {
        val cv = (c shr shift) and 0xFF
        val wideAvg = (((wL shr shift) and 0xFF) + ((wR shr shift) and 0xFF) +
            ((wT shr shift) and 0xFF) + ((wB shr shift) and 0xFF)) * 0.25f
        val nearAvg = (((nL shr shift) and 0xFF) + ((nR shr shift) and 0xFF) +
            ((nT shr shift) and 0xFF) + ((nB shr shift) and 0xFF)) * 0.25f

        val clarity = (cv - wideAvg) * (strength * 0.55f)
        val fineEdge = (cv - nearAvg) * (strength * 0.75f)
        return (cv + clarity + fineEdge).toInt().coerceIn(0, 255)
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
}
// END OF FILE
