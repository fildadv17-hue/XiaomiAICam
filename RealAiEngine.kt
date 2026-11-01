package com.example.xiaomiaicam

import android.graphics.Bitmap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.*

object RealAiEngine {
    private val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(4)
    private val pool = Executors.newFixedThreadPool(cores)

    fun processRealAi(src: Bitmap, scale: Int, depix: Float, detail: Float, clarity: Float): Bitmap {
        var w = pdeDiffusion(src, (2 + depix * 4).roundToInt(), 18f + depix * 35f)
        val sc = min(scale.toFloat(), 3200f / max(w.width, w.height)).coerceAtLeast(1.1f)
        w = lanczos3(w, (w.width * sc).roundToInt(), (w.height * sc).roundToInt())
        if (depix > 0.1f) w = pdeDiffusion(w, 2, 14f + depix * 20f)
        return synthTexture(w, detail, clarity)
    }

    private fun pdeDiffusion(src: Bitmap, steps: Int, kappa: Float): Bitmap {
        val w = src.width; val h = src.height; var p = IntArray(w * h); src.getPixels(p, 0, w, 0, 0, w, h); var nx = p.clone()
        val ik2 = 1f / (kappa * kappa); val ch = max(1, (h - 2) / cores)
        repeat(steps) {
            (0 until cores).map { t ->
                val y0 = (1 + t * ch).coerceAtMost(h - 1); val y1 = if (t == cores - 1) h - 1 else (y0 + ch).coerceAtMost(h - 1)
                pool.submit {
                    for (y in y0 until y1) {
                        val r = y * w
                        for (x in 1 until w - 1) {
                            val i = r + x; val c = p[i]; val rC = c shr 16 and 0xFF; val gC = c shr 8 and 0xFF; val bC = c and 0xFF
                            var dR = 0f; var dG = 0f; var dB = 0f
                            val nb = intArrayOf(p[i - w], p[i + w], p[i - 1], p[i + 1], p[i - w - 1], p[i - w + 1], p[i + w - 1], p[i + w + 1])
                            for (k in nb.indices) {
                                val n = nb[k]; val nr = (n shr 16 and 0xFF) - rC; val ng = (n shr 8 and 0xFF) - gC; val nbl = (n and 0xFF) - bC
                                val wt = (1f / (1f + ((nr * nr + ng * ng + nbl * nbl) / 3f) * ik2)) * (if (k < 4) 0.16f else 0.08f)
                                dR += nr * wt; dG += ng * wt; dB += nbl * wt
                            }
                            nx[i] = (0xFF shl 24) or ((rC + dR).roundToInt().coerceIn(0, 255) shl 16) or ((gC + dG).roundToInt().coerceIn(0, 255) shl 8) or (bC + dB).roundToInt().coerceIn(0, 255)
                        }
                    }
                }
            }.forEach { it.get() }
            val tmp = p; p = nx; nx = tmp
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(p, 0, w, 0, 0, w, h) }
    }

    private fun lk(x: Float): Float {
        val ax = abs(x); if (ax < 1e-5f) return 1f; if (ax >= 3f) return 0f
        val px = Math.PI.toFloat() * ax; return (sin(px) / px) * (sin(px / 3f) / (px / 3f))
    }

    private fun lanczos3(src: Bitmap, dstW: Int, dstH: Int): Bitmap {
        val sw = src.width; val sh = src.height; val sp = IntArray(sw * sh); src.getPixels(sp, 0, sw, 0, 0, sw, sh)
        val tmp = IntArray(dstW * sh); val dp = IntArray(dstW * dstH); val xR = sw.toFloat() / dstW; val yR = sh.toFloat() / dstH
        val hCh = max(1, sh / cores); val vCh = max(1, dstH / cores)
        (0 until cores).map { t ->
            val y0 = (t * hCh).coerceAtMost(sh); val y1 = if (t == cores - 1) sh else (y0 + hCh).coerceAtMost(sh)
            pool.submit {
                for (y in y0 until y1) {
                    val rI = y * sw; val rO = y * dstW
                    for (x in 0 until dstW) {
                        val cx = (x + 0.5f) * xR - 0.5f; val ix = floor(cx).toInt()
                        var rS = 0f; var gS = 0f; var bS = 0f; var wS = 0f
                        for (k in -2..3) {
                            val w = lk(cx - (ix + k)); val c = sp[rI + (ix + k).coerceIn(0, sw - 1)]
                            rS += (c shr 16 and 0xFF) * w; gS += (c shr 8 and 0xFF) * w; bS += (c and 0xFF) * w; wS += w
                        }
                        val iw = if (abs(wS) > 1e-4f) 1f / wS else 1f
                        tmp[rO + x] = (0xFF shl 24) or ((rS * iw).roundToInt().coerceIn(0, 255) shl 16) or ((gS * iw).roundToInt().coerceIn(0, 255) shl 8) or (bS * iw).roundToInt().coerceIn(0, 255)
                    }
                }
            }
        }.forEach { it.get() }
        (0 until cores).map { t ->
            val y0 = (t * vCh).coerceAtMost(dstH); val y1 = if (t == cores - 1) dstH else (y0 + vCh).coerceAtMost(dstH)
            pool.submit {
                for (y in y0 until y1) {
                    val cy = (y + 0.5f) * yR - 0.5f; val iy = floor(cy).toInt(); val rO = y * dstW
                    for (x in 0 until dstW) {
                        var rS = 0f; var gS = 0f; var bS = 0f; var wS = 0f
                        for (k in -2..3) {
                            val w = lk(cy - (iy + k)); val c = tmp[(iy + k).coerceIn(0, sh - 1) * dstW + x]
                            rS += (c shr 16 and 0xFF) * w; gS += (c shr 8 and 0xFF) * w; bS += (c and 0xFF) * w; wS += w
                        }
                        val iw = if (abs(wS) > 1e-4f) 1f / wS else 1f
                        dp[rO + x] = (0xFF shl 24) or ((rS * iw).roundToInt().coerceIn(0, 255) shl 16) or ((gS * iw).roundToInt().coerceIn(0, 255) shl 8) or (bS * iw).roundToInt().coerceIn(0, 255)
                    }
                }
            }
        }.forEach { it.get() }
        return Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888).apply { setPixels(dp, 0, dstW, 0, 0, dstW, dstH) }
    }

    private fun synthTexture(src: Bitmap, detail: Float, clarity: Float): Bitmap {
        val w = src.width; val h = src.height; val p = IntArray(w * h); src.getPixels(p, 0, w, 0, 0, w, h); val out = p.clone()
        val med = Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(src, max(32, w / 4), max(32, h / 4), true), w, h, true)
        val pM = IntArray(w * h); med.getPixels(pM, 0, w, 0, 0, w, h)
        val lum = IntArray(w * h) { ((p[it] shr 16 and 0xFF) * 77 + (p[it] shr 8 and 0xFF) * 150 + (p[it] and 0xFF) * 29) shr 8 }
        val gF = detail * 1.85f; val gC = clarity * 0.65f; val ch = max(1, (h - 2) / cores)
        (0 until cores).map { t ->
            val y0 = (1 + t * ch).coerceAtMost(h - 1); val y1 = if (t == cores - 1) h - 1 else (y0 + ch).coerceAtMost(h - 1)
            pool.submit {
                for (y in y0 until y1) {
                    val r = y * w
                    for (x in 1 until w - 1) {
                        val i = r + x; val c = p[i]; val cm = pM[i]; val lC = lum[i]
                        val blur = (lC * 4 + (lum[i - w] + lum[i + w] + lum[i - 1] + lum[i + 1]) * 2 + lum[i - w - 1] + lum[i - w + 1] + lum[i + w - 1] + lum[i + w + 1]) shr 4
                        val hf = lC - blur; val lM = ((cm shr 16 and 0xFF) * 77 + (cm shr 8 and 0xFF) * 150 + (cm and 0xFF) * 29) shr 8
                        val d = ((if (abs(hf) > 1) (hf * gF).coerceIn(-22f, 22f) else 0f) + ((lC - lM) * gC).coerceIn(-18f, 18f)).roundToInt()
                        out[i] = (0xFF shl 24) or ((c shr 16 and 0xFF) + d).coerceIn(0, 255).shl(16) or ((c shr 8 and 0xFF) + d).coerceIn(0, 255).shl(8) or ((c and 0xFF) + d).coerceIn(0, 255)
                    }
                }
            }
        }.forEach { it.get() }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(out, 0, w, 0, 0, w, h) }
    }
}
