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

enum class PhotoQuality(val label: String, val jpegQuality: Int, val aiDetail: Float) {
    STANDARD("Standard", 85, 0.65f),
    HIGH("High Pro", 95, 1.05f),
    AI_ULTRA("AI Ultra 250x", 100, 1.45f)
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

        // 1. WITHOUT AI: Unprocessed nearest-neighbor zoom crop (shows raw blocky sensor pixels)
        val withoutAi = Bitmap.createScaledBitmap(rawCrop, targetW, targetH, false)

        // 2. WITH AI: Directional 2x Super-Resolution + Pixel Texture Synthesis + Leica Grade
        val reconstructed = runSuperResolutionAi(rawCrop, targetW, targetH, quality.aiDetail)
        val withAi = applyLeicaColorMatrix(reconstructed, leicaProfile)

        if (rawCrop !== source && rawCrop !== withoutAi && rawCrop !== reconstructed) rawCrop.recycle()
        if (reconstructed !== withAi) reconstructed.recycle()

        return CapturedPhotoPair(withoutAi, withAi)
    }

    private fun runSuperResolutionAi(
        rawCrop: Bitmap,
        targetW: Int,
        targetH: Int,
        aiDetail: Float
    ): Bitmap {
        var current = denoiseFlatRegions(rawCrop, threshold = 24)

        while (current.width < targetW || current.height < targetH) {
            val nextW = min(targetW, current.width * 2)
            val nextH = min(targetH, current.height * 2)

            // Directional edge-guided 2x upsample + contour synthesis
            val stepped = directionalUpscale2x(current, nextW, nextH, aiDetail)
            if (current !== rawCrop) current.recycle()
            current = stepped
        }

        val textured = synthesizeMicroDetail(current, aiDetail)
        if (current !== textured && current !== rawCrop) current.recycle()
        return textured
    }

    /**
     * Edge-directed 2x upscaler:
     * Measures local horizontal, vertical, and diagonal gradients so edges
     * are reconstructed as crisp lines instead of square pixel blocks.
     */
    private fun directionalUpscale2x(
        src: Bitmap,
        outW: Int,
        outH: Int,
        detailPower: Float
    ): Bitmap {
        val base = Bitmap.createScaledBitmap(src, outW, outH, true)
        val px = IntArray(outW * outH)
        base.getPixels(px, 0, outW, 0, 0, outW, outH)
        base.recycle()

        val out = px.copyOf()
        val overshoot = (22f * detailPower).toInt()

        for (y in 2 until outH - 2) {
            val row = y * outW
            for (x in 2 until outW - 2) {
                val i = row + x
                val c = px[i]
                val l1 = px[i - 1]
                val r1 = px[i + 1]
                val t1 = px[i - outW]
                val b1 = px[i + outW]
                val l2 = px[i - 2]
                val r2 = px[i + 2]
                val t2 = px[i - outW * 2]
                val b2 = px[i + outW * 2]
                val tl = px[i - outW - 1]
                val tr = px[i - outW + 1]
                val bl = px[i + outW - 1]
                val br = px[i + outW + 1]

                // Detect edge orientation via luma gradients
                val gradH = abs(luma(l1) - luma(r1)) + abs(luma(l2) - luma(r2))
                val gradV = abs(luma(t1) - luma(b1)) + abs(luma(t2) - luma(b2))
                val gradD1 = abs(luma(tl) - luma(br)) * 2
                val gradD2 = abs(luma(tr) - luma(bl)) * 2

                val minGrad = min(min(gradH, gradV), min(gradD1, gradD2))

                // Sample along the detected edge direction (lowest gradient)
                val (eA, eB) = when (minGrad) {
                    gradH -> Pair(l1, r1)
                    gradV -> Pair(t1, b1)
                    gradD1 -> Pair(tl, br)
                    else -> Pair(tr, bl)
                }

                // Sample across the edge (highest gradient) to sharpen contour transition
                val (cA, cB) = when {
                    gradH >= gradV -> Pair(l1, r1)
                    else -> Pair(t1, b1)
                }

                val nr = synthesizeEdgePixel(c, eA, eB, cA, cB, 16, detailPower, overshoot)
                val ng = synthesizeEdgePixel(c, eA, eB, cA, cB, 8, detailPower, overshoot)
                val nb = synthesizeEdgePixel(c, eA, eB, cA, cB, 0, detailPower, overshoot)

                out[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
        return Bitmap.createBitmap(out, outW, outH, Bitmap.Config.ARGB_8888)
    }

    private fun synthesizeEdgePixel(
        c: Int, eA: Int, eB: Int, cA: Int, cB: Int,
        shift: Int, power: Float, overshoot: Int
    ): Int {
        val cv = (c shr shift) and 0xFF
        val alongAvg = (((eA shr shift) and 0xFF) + ((eB shr shift) and 0xFF)) * 0.5f
        val acrossAvg = (((cA shr shift) and 0xFF) + ((cB shr shift) and 0xFF)) * 0.5f

        // Smooth along the contour while boosting contrast across the contour
        val smoothed = cv * 0.55f + alongAvg * 0.45f
        val contourPush = (smoothed - acrossAvg) * (power * 0.95f)
        val localMin = min(cv, min((cA shr shift) and 0xFF, (cB shr shift) and 0xFF)) - overshoot
        val localMax = max(cv, max((cA shr shift) and 0xFF, (cB shr shift) and 0xFF)) + overshoot

        return (smoothed + contourPush).toInt()
            .coerceIn(localMin.coerceAtLeast(0), localMax.coerceAtMost(255))
    }

    /**
     * Reconstructs high-frequency micro-detail & luma texture on mid-tone surfaces
     * while protecting smooth highlights/shadows from grain.
     */
    private fun synthesizeMicroDetail(src: Bitmap, detailPower: Float): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val out = px.copyOf()

        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                val c = px[i]
                val cr = (c shr 16) and 0xFF
                val cg = (c shr 8) and 0xFF
                val cb = c and 0xFF

                val avgR = (((px[i - 1] shr 16) and 0xFF) + ((px[i + 1] shr 16) and 0xFF) +
                    ((px[i - w] shr 16) and 0xFF) + ((px[i + w] shr 16) and 0xFF)) * 0.25f
                val avgG = (((px[i - 1] shr 8) and 0xFF) + ((px[i + 1] shr 8) and 0xFF) +
                    ((px[i - w] shr 8) and 0xFF) + ((px[i + w] shr 8) and 0xFF)) * 0.25f
                val avgB = ((px[i - 1] and 0xFF) + (px[i + 1] and 0xFF) +
                    (px[i - w] and 0xFF) + (px[i + w] and 0xFF)) * 0.25f

                val diffLuma = abs((cr - avgR) * 0.3f + (cg - avgG) * 0.59f + (cb - avgB) * 0.11f)
                val boost = if (diffLuma > 3f) detailPower * 0.85f else 0f

                val nr = (cr + (cr - avgR) * boost).toInt().coerceIn(0, 255)
                val ng = (cg + (cg - avgG) * boost).toInt().coerceIn(0, 255)
                val nb = (cb + (cb - avgB) * boost).toInt().coerceIn(0, 255)

                out[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun denoiseFlatRegions(src: Bitmap, threshold: Int): Bitmap {
        val w = src.width
        val h = src.height
        if (w < 4 || h < 4) return src.copy(Bitmap.Config.ARGB_8888, false)
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val out = px.copyOf()

        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                val c = px[i]
                val cr = (c shr 16) and 0xFF
                val cg = (c shr 8) and 0xFF
                val cb = c and 0xFF
                var sr = cr * 2; var sg = cg * 2; var sb = cb * 2; var sw = 2
                val nbs = intArrayOf(px[i - w], px[i + w], px[i - 1], px[i + 1])
                for (n in nbs) {
                    val nr = (n shr 16) and 0xFF; val ng = (n shr 8) and 0xFF; val nb = n and 0xFF
                    if (abs(cr - nr) + abs(cg - ng) + abs(cb - nb) < threshold * 3) {
                        sr += nr; sg += ng; sb += nb; sw++
                    }
                }
                out[i] = (0xFF shl 24) or ((sr / sw) shl 16) or ((sg / sw) shl 8) or (sb / sw)
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun luma(c: Int): Int {
        return (((c shr 16) and 0xFF) * 3 + ((c shr 8) and 0xFF) * 6 + (c and 0xFF)) / 10
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
