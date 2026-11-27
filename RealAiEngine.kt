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

enum class PhotoQuality(val label: String, val jpegQuality: Int, val edgePower: Float) {
    STANDARD("Standard", 85, 0.55f),
    HIGH("High Pro", 95, 0.85f),
    AI_ULTRA("AI Ultra 250x", 100, 1.15f)
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

        // Extract exact raw sensor crop BEFORE upscaling
        val z = max(1f, digitalMultiplier)
        val cropW = (source.width / z).toInt().coerceIn(48, source.width)
        val cropH = (source.height / z).toInt().coerceIn(48, source.height)
        val left = ((source.width - cropW) / 2).coerceAtLeast(0)
        val top = ((source.height - cropH) / 2).coerceAtLeast(0)
        val rawCrop = Bitmap.createBitmap(source, left, top, cropW, cropH)

        // 1. WITHOUT AI: Standard single-pass digital zoom stretch (soft/pixelated)
        val withoutAi = Bitmap.createScaledBitmap(rawCrop, targetW, targetH, false)

        // 2. WITH AI: Multi-step 2x Pyramid Super-Resolution + Edge Clamping + Leica Grade
        val superRes = upscalePyramidAi(rawCrop, targetW, targetH, quality.edgePower)
        val withAi = applyLeicaColorMatrix(superRes, leicaProfile)

        if (rawCrop !== source && rawCrop !== withoutAi && rawCrop !== superRes) rawCrop.recycle()
        if (superRes !== withAi) superRes.recycle()

        return CapturedPhotoPair(withoutAi, withAi)
    }

    /**
     * Step-by-step 2x upscaling pyramid:
     * Reconstructs sharp edges and smooths sensor noise at EVERY 2x scale jump
     * instead of stretching giant blurry blocks at the end.
     */
    private fun upscalePyramidAi(
        rawCrop: Bitmap,
        targetW: Int,
        targetH: Int,
        edgePower: Float
    ): Bitmap {
        // Clean raw sensor grain at native small size first
        var current = bilateralClean(rawCrop, threshold = 28)

        while (current.width < targetW || current.height < targetH) {
            val nextW = min(targetW, current.width * 2)
            val nextH = min(targetH, current.height * 2)

            val scaled = Bitmap.createScaledBitmap(current, nextW, nextH, true)
            if (current !== rawCrop) current.recycle()

            // Reconstruct crisp edges & clamp halos at this scale level
            val reconstructed = sharpenAndClampStep(scaled, edgePower)
            if (scaled !== reconstructed) scaled.recycle()

            current = reconstructed
        }

        // Final surface polish so flat skies/walls stay ultra-clean while edges stay razor sharp
        val polished = bilateralClean(current, threshold = 18)
        if (current !== polished && current !== rawCrop) current.recycle()
        return polished
    }

    private fun bilateralClean(src: Bitmap, threshold: Int): Bitmap {
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

                var sr = cr * 3
                var sg = cg * 3
                var sb = cb * 3
                var sw = 3

                val nbs = intArrayOf(
                    px[i - w - 1], px[i - w], px[i - w + 1],
                    px[i - 1],                px[i + 1],
                    px[i + w - 1], px[i + w], px[i + w + 1]
                )

                for (n in nbs) {
                    val nr = (n shr 16) and 0xFF
                    val ng = (n shr 8) and 0xFF
                    val nb = n and 0xFF
                    val diff = abs(cr - nr) + abs(cg - ng) + abs(cb - nb)
                    if (diff < threshold * 3) {
                        val wgt = if (diff < threshold) 2 else 1
                        sr += nr * wgt
                        sg += ng * wgt
                        sb += nb * wgt
                        sw += wgt
                    }
                }
                out[i] = (0xFF shl 24) or ((sr / sw) shl 16) or ((sg / sw) shl 8) or (sb / sw)
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * Shock/Clamp Edge Reconstruction:
     * Pulls blurry transition pixels toward local min/max bounds (tightens blurry zoom edges
     * without creating ugly white/black halo artifacts).
     */
    private fun sharpenAndClampStep(src: Bitmap, power: Float): Bitmap {
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
                val t = px[i - w]
                val b = px[i + w]
                val l = px[i - 1]
                val r = px[i + 1]
                val tl = px[i - w - 1]
                val tr = px[i - w + 1]
                val bl = px[i + w - 1]
                val br = px[i + w + 1]

                val nr = reconstructChannel(c, t, b, l, r, tl, tr, bl, br, 16, power)
                val ng = reconstructChannel(c, t, b, l, r, tl, tr, bl, br, 8, power)
                val nb = reconstructChannel(c, t, b, l, r, tl, tr, bl, br, 0, power)

                out[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun reconstructChannel(
        c: Int, t: Int, b: Int, l: Int, r: Int,
        tl: Int, tr: Int, bl: Int, br: Int,
        shift: Int, power: Float
    ): Int {
        val cv = (c shr shift) and 0xFF
        val tv = (t shr shift) and 0xFF
        val bv = (b shr shift) and 0xFF
        val lv = (l shr shift) and 0xFF
        val rv = (r shr shift) and 0xFF
        val tlv = (tl shr shift) and 0xFF
        val trv = (tr shr shift) and 0xFF
        val blv = (bl shr shift) and 0xFF
        val brv = (br shr shift) and 0xFF

        val minN = min(min(min(tv, bv), min(lv, rv)), min(min(tlv, trv), min(blv, brv)))
        val maxN = max(max(max(tv, bv), max(lv, rv)), max(max(tlv, trv), max(blv, brv)))

        val blur = (tv + bv + lv + rv) * 0.15f + (tlv + trv + blv + brv) * 0.10f
        val pushed = (cv + (cv - blur) * (power * 1.6f)).toInt()

        // Clamp strictly inside neighborhood min..max so edges become razor crisp with ZERO haloing
        return pushed.coerceIn(minN, maxN)
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
