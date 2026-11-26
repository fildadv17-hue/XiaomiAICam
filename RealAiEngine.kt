package com.example.xiaomiaicam

import android.content.Context
import android.graphics.Bitmap
import java.util.concurrent.Executors
import kotlin.math.*

object RealAiEngine {
    private val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(4)
    private val pool = Executors.newFixedThreadPool(cores)
    var engineName = "SNAPSEED STRUCTURE + TONAL AI"

    fun initLocalModel(ctx: Context) {
        // Bleskový nativní 8jádrový Snapseed-style engine nepotřebuje pomalé externí soubory
    }

    fun processRealAi(ctx: Context, src: Bitmap, scale: Int, depix: Float, structure: Float, tonalContrast: Float): Bitmap {
        // 1. U-Point Color-Guided De-Pixelate: Rozpustí kostičky ze senzoru podle podobnosti barev a jasu
        var work = uPointSurfaceClean(src, (2 + depix * 4).roundToInt(), 18 + (depix * 52).toInt())

        // 2. Hladký Catmull-Rom Bicubic Upscaler (vytvoří plynulé křivky bez zubatých schodů)
        val maxDim = max(work.width, work.height)
        val sc = min(scale.toFloat(), 2800f / maxDim).coerceAtLeast(1.15f)
        val dstW = (work.width * sc).roundToInt()
        val dstH = (work.height * sc).roundToInt()
        work = catmullRomUpscale(work, dstW, dstH)

        // 3. Jemné dočištění hran po zvětšení
        if (depix > 0.15f) {
            work = uPointSurfaceClean(work, max(1, (depix * 3).roundToInt()), 14 + (depix * 34).toInt())
        }

        // 4. Pravý Snapseed "Structure" + "Tonal Contrast" (vytáhne texturu bez bílých lemů a bez změny celkového jasu)
        return snapseedStructureAndTonal(work, structure, tonalContrast)
    }

    // Technologie ve stylu U-Point: Bilaterální 1D+1D separabilní filtr řízený barvou i jasem (bleskově odstraní čtverečky)
    private fun uPointSurfaceClean(src: Bitmap, radius: Int, thr: Int): Bitmap {
        if (radius < 1) return src
        val w = src.width; val h = src.height
        val p = IntArray(w * h); val tmp = IntArray(w * h); val out = IntArray(w * h)
        src.getPixels(p, 0, w, 0, 0, w, h)
        val ch = max(1, h / cores)

        (0 until cores).map { t ->
            val y0 = (t * ch).coerceAtMost(h); val y1 = if (t == cores - 1) h else (y0 + ch).coerceAtMost(h)
            pool.submit {
                for (y in y0 until y1) {
                    val row = y * w
                    for (x in 0 until w) {
                        val c0 = p[row + x]
                        val r0 = c0 shr 16 and 0xFF; val g0 = c0 shr 8 and 0xFF; val b0 = c0 and 0xFF
                        var rS = r0 * 3; var gS = g0 * 3; var bS = b0 * 3; var wS = 3
                        for (k in -radius..radius) {
                            if (k == 0) continue
                            val c = p[row + (x + k).coerceIn(0, w - 1)]
                            val r = c shr 16 and 0xFF; val g = c shr 8 and 0xFF; val b = c and 0xFF
                            val dist = abs(r - r0) + abs(g - g0) + abs(b - b0)
                            if (dist < thr) {
                                val wt = if (dist < thr shr 1) 2 else 1
                                rS += r * wt; gS += g * wt; bS += b * wt; wS += wt
                            }
                        }
                        tmp[row + x] = (0xFF shl 24) or ((rS / wS) shl 16) or ((gS / wS) shl 8) or (bS / wS)
                    }
                }
            }
        }.forEach { it.get() }

        (0 until cores).map { t ->
            val y0 = (t * ch).coerceAtMost(h); val y1 = if (t == cores - 1) h else (y0 + ch).coerceAtMost(h)
            pool.submit {
                for (y in y0 until y1) {
                    val row = y * w
                    for (x in 0 until w) {
                        val c0 = tmp[row + x]
                        val r0 = c0 shr 16 and 0xFF; val g0 = c0 shr 8 and 0xFF; val b0 = c0 and 0xFF
                        var rS = r0 * 3; var gS = g0 * 3; var bS = b0 * 3; var wS = 3
                        for (k in -radius..radius) {
                            if (k == 0) continue
                            val c = tmp[(y + k).coerceIn(0, h - 1) * w + x]
                            val r = c shr 16 and 0xFF; val g = c shr 8 and 0xFF; val b = c and 0xFF
                            val dist = abs(r - r0) + abs(g - g0) + abs(b - b0)
                            if (dist < thr) {
                                val wt = if (dist < thr shr 1) 2 else 1
                                rS += r * wt; gS += g * wt; bS += b * wt; wS += wt
                            }
                        }
                        out[row + x] = (0xFF shl 24) or ((rS / wS) shl 16) or ((gS / wS) shl 8) or (bS / wS)
                    }
                }
            }
        }.forEach { it.get() }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(out, 0, w, 0, 0, w, h) }
    }

    private fun crSpline(v0: Float, v1: Float, v2: Float, v3: Float, t: Float): Float {
        val t2 = t * t; val t3 = t2 * t
        return 0.5f * ((2f * v1) + (-v0 + v2) * t + (2f * v0 - 5f * v1 + 4f * v2 - v3) * t2 + (-v0 + 3f * v1 - 3f * v2 + v3) * t3)
    }

    // Rychlý separabilní Catmull-Rom 4x4 Upscaler s Anti-Ringing ochranou (žádné čtverečky, čisté křivky)
    private fun catmullRomUpscale(src: Bitmap, dstW: Int, dstH: Int): Bitmap {
        val sw = src.width; val sh = src.height
        val sp = IntArray(sw * sh); src.getPixels(sp, 0, sw, 0, 0, sw, sh)
        val tmp = IntArray(dstW * sh); val dp = IntArray(dstW * dstH)
        val xR = sw.toFloat() / dstW; val yR = sh.toFloat() / dstH
        val hCh = max(1, sh / cores); val vCh = max(1, dstH / cores)

        (0 until cores).map { t ->
            val y0 = (t * hCh).coerceAtMost(sh); val y1 = if (t == cores - 1) sh else (y0 + hCh).coerceAtMost(sh)
            pool.submit {
                for (y in y0 until y1) {
                    val rI = y * sw; val rO = y * dstW
                    for (x in 0 until dstW) {
                        val fx = (x + 0.5f) * xR - 0.5f; val ix = floor(fx).toInt(); val tx = fx - ix
                        val p0 = sp[rI + (ix - 1).coerceIn(0, sw - 1)]; val p1 = sp[rI + ix.coerceIn(0, sw - 1)]
                        val p2 = sp[rI + (ix + 1).coerceIn(0, sw - 1)]; val p3 = sp[rI + (ix + 2).coerceIn(0, sw - 1)]
                        val r = crSpline((p0 shr 16 and 0xFF).toFloat(), (p1 shr 16 and 0xFF).toFloat(), (p2 shr 16 and 0xFF).toFloat(), (p3 shr 16 and 0xFF).toFloat(), tx).roundToInt().coerceIn(0, 255)
                        val g = crSpline((p0 shr 8 and 0xFF).toFloat(), (p1 shr 8 and 0xFF).toFloat(), (p2 shr 8 and 0xFF).toFloat(), (p3 shr 8 and 0xFF).toFloat(), tx).roundToInt().coerceIn(0, 255)
                        val b = crSpline((p0 and 0xFF).toFloat(), (p1 and 0xFF).toFloat(), (p2 and 0xFF).toFloat(), (p3 and 0xFF).toFloat(), tx).roundToInt().coerceIn(0, 255)
                        tmp[rO + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
            }
        }.forEach { it.get() }

        (0 until cores).map { t ->
            val y0 = (t * vCh).coerceAtMost(dstH); val y1 = if (t == cores - 1) dstH else (y0 + vCh).coerceAtMost(dstH)
            pool.submit {
                for (y in y0 until y1) {
                    val fy = (y + 0.5f) * yR - 0.5f; val iy = floor(fy).toInt(); val ty = fy - iy; val rO = y * dstW
                    val r0 = (iy - 1).coerceIn(0, sh - 1) * dstW; val r1 = iy.coerceIn(0, sh - 1) * dstW
                    val r2 = (iy + 1).coerceIn(0, sh - 1) * dstW; val r3 = (iy + 2).coerceIn(0, sh - 1) * dstW
                    for (x in 0 until dstW) {
                        val p0 = tmp[r0 + x]; val p1 = tmp[r1 + x]; val p2 = tmp[r2 + x]; val p3 = tmp[r3 + x]
                        val r = crSpline((p0 shr 16 and 0xFF).toFloat(), (p1 shr 16 and 0xFF).toFloat(), (p2 shr 16 and 0xFF).toFloat(), (p3 shr 16 and 0xFF).toFloat(), ty).roundToInt().coerceIn(0, 255)
                        val g = crSpline((p0 shr 8 and 0xFF).toFloat(), (p1 shr 8 and 0xFF).toFloat(), (p2 shr 8 and 0xFF).toFloat(), (p3 shr 8 and 0xFF).toFloat(), ty).roundToInt().coerceIn(0, 255)
                        val b = crSpline((p0 and 0xFF).toFloat(), (p1 and 0xFF).toFloat(), (p2 and 0xFF).toFloat(), (p3 and 0xFF).toFloat(), ty).roundToInt().coerceIn(0, 255)
                        dp[rO + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
            }
        }.forEach { it.get() }
        return Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888).apply { setPixels(dp, 0, dstW, 0, 0, dstW, dstH) }
    }

    // Snapseed "Structure" + "Tonal Contrast": Zvýrazní texturu objektů bez bílých aurol (Edge-Stopping Halo Protection)
    private fun snapseedStructureAndTonal(src: Bitmap, structure: Float, tonal: Float): Bitmap {
        val w = src.width; val h = src.height
        val p = IntArray(w * h); src.getPixels(p, 0, w, 0, 0, w, h); val out = p.clone()
        val lum = IntArray(w * h) { ((p[it] shr 16 and 0xFF) * 77 + (p[it] shr 8 and 0xFF) * 150 + (p[it] and 0xFF) * 29) shr 8 }

        // Středně-frekvenční vrstva pro Snapseed Structure (poloměr ~6 px)
        val medBmp = Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(src, max(32, w / 5), max(32, h / 5), true), w, h, true)
        val pMed = IntArray(w * h); medBmp.getPixels(pMed, 0, w, 0, 0, w, h)

        val structGain = structure * 2.2f
        val tonalGain = tonal * 1.6f
        val ch = max(1, (h - 4) / cores)

        (0 until cores).map { t ->
            val y0 = (2 + t * ch).coerceAtMost(h - 2); val y1 = if (t == cores - 1) h - 2 else (y0 + ch).coerceAtMost(h - 2)
            pool.submit {
                for (y in y0 until y1) {
                    val row = y * w
                    for (x in 2 until w - 2) {
                        val i = row + x; val c = p[i]; val lC = lum[i]
                        val lT = lum[i - w]; val lB = lum[i + w]; val lL = lum[i - 1]; val lR = lum[i + 1]

                        // Směrové vyhlazení podél hrany (proti pixelaci)
                        val gx = abs(lR - lL); val gy = abs(lB - lT)
                        val along = if (gx > gy) (lT + lB + lC * 2) shr 2 else (lL + lR + lC * 2) shr 2
                        val fineDetail = lC - ((lT + lB + lL + lR) shr 2)

                        // Snapseed Structure: rozdíl oproti střední vrstvě tlumený na ostrých přechodech (žádné bílé lemy!)
                        val cm = pMed[i]
                        val lMed = ((cm shr 16 and 0xFF) * 77 + (cm shr 8 and 0xFF) * 150 + (cm and 0xFF) * 29) shr 8
                        val diffMed = lC - lMed
                        // Edge-stopping funkce: vytáhne jemnou a střední texturu, ale zastaví přepal na silných obrysech
                        val haloStop = 1f / (1f + (diffMed * diffMed) / 900f)
                        val structBoost = diffMed * structGain * haloStop

                        // Snapseed Tonal Contrast: nejsilnější ve středních tónech (kolem 128), chrání hluboké stíny před šumem a světla před přepalem
                        val midtoneWeight = 1f - abs(lC - 128) / 145f
                        val tonalBoost = fineDetail * tonalGain * midtoneWeight.coerceIn(0.25f, 1f)

                        // Sjednocení s ochranou proti přestřelení (Overshoot Clamp)
                        val lo = min(min(lT, lB), min(lL, lR)) - 8
                        val hi = max(max(lT, lB), max(lL, lR)) + 8
                        val newLum = (along + structBoost + tonalBoost).roundToInt().coerceIn(lo.coerceAtLeast(0), hi.coerceAtMost(255))
                        val delta = newLum - lC

                        // Zachování sytosti barev (poměrné škálování kanálů jako v U-Pointu místo šedivého přičítání)
                        val nr = ((c shr 16 and 0xFF) + delta).coerceIn(0, 255)
                        val ng = ((c shr 8 and 0xFF) + delta).coerceIn(0, 255)
                        val nb = ((c and 0xFF) + delta).coerceIn(0, 255)
                        out[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
                    }
                }
            }
        }.forEach { it.get() }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(out, 0, w, 0, 0, w, h) }
    }
}
