package com.example.xiaomiaicam

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CaptureRequest
import android.os.*
import android.provider.MediaStore
import android.view.*
import android.view.ViewGroup.LayoutParams.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.*

class MainActivity : AppCompatActivity() {
    private lateinit var pv: PreviewView; private lateinit var camLay: FrameLayout; private lateinit var edLay: LinearLayout
    private lateinit var ivEd: ImageView; private lateinit var tvZoom: TextView; private lateinit var tvStatus: TextView
    private lateinit var prog: ProgressBar; private lateinit var sbSplit: SeekBar
    private lateinit var sbDepix: SeekBar; private lateinit var sbDetail: SeekBar; private lateinit var sbClarity: SeekBar
    private var imgCap: ImageCapture? = null; private var cam: androidx.camera.core.Camera? = null
    private val exec = Executors.newSingleThreadExecutor()
    private val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(4)
    private val pool = Executors.newFixedThreadPool(cores)
    private var curZoom = 1f; private var maxHw = 20f; private var swZoom = 1f; private var curScale = 2
    private var rawBmp: Bitmap? = null; private var aiBmp: Bitmap? = null
    private val perm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) startCam() }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s); buildUi()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCam()
        else perm.launch(Manifest.permission.CAMERA)
    }

    private fun glass(c1: Long, c2: Long, st: Long, r: Float) =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c1.toInt(), c2.toInt())).apply { cornerRadius = r; setStroke(3, st.toInt()) }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        camLay = FrameLayout(this); pv = PreviewView(this)
        camLay.addView(FrameLayout(this).apply { clipChildren = true; addView(pv, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)) }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val top = LinearLayout(this).apply { setPadding(36, 24, 36, 24); gravity = Gravity.CENTER_VERTICAL; background = glass(0x55FFFFFF, 0x22FFFFFF, 0x99FFFFFF, 60f) }
        tvZoom = TextView(this).apply { text = "XIAOMI 14T • 1.0x LANCZOS AI"; setTextColor(Color.WHITE); textSize = 15f; typeface = Typeface.DEFAULT_BOLD }
        top.addView(tvZoom, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        camLay.addView(top, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP).apply { setMargins(24, 52, 24, 0) })

        val bot = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 32, 36, 40); background = glass(0x48FFFFFF, 0x18FFFFFF, 0x99FFFFFF, 68f) }
        val sbZ = SeekBar(this).apply { max = 990 }
        val zRow = LinearLayout(this).apply { gravity = Gravity.CENTER }
        listOf(1f, 2f, 4f, 20f, 100f).forEach { z ->
            zRow.addView(Button(this).apply {
                text = "${z.toInt()}x"; setTextColor(Color.WHITE); textSize = 12f; background = glass(0x40FFFFFF, 0x15FFFFFF, 0x77FFFFFF, 36f)
                setOnClickListener { sbZ.progress = ((z - 1f) * 10f).toInt() }
            }, LinearLayout.LayoutParams(0, 100, 1f).apply { setMargins(6, 0, 6, 0) })
        }
        bot.addView(zRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        bot.addView(sbZ, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 20; bottomMargin = 20 })
        sbZ.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { setZoom(1f + p / 10f) }
            override fun onStartTrackingTouch(s: SeekBar?) {}; override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        bot.addView(Button(this).apply {
            text = "CAPTURE • 8-CORE LANCZOS-3 AI"; setTextColor(Color.WHITE); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
            background = glass(0xEEFF6900, 0xBBCC2200, 0xCCFFFFFF, 60f); setOnClickListener { takePhoto() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, 136))
        camLay.addView(bot, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply { setMargins(24, 0, 24, 36) })

        edLay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#090C12")); visibility = View.GONE; setPadding(24, 44, 24, 28) }
        tvStatus = TextView(this).apply { setTextColor(Color.CYAN); textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setPadding(24, 16, 24, 16); background = glass(0x38FFFFFF, 0x14FFFFFF, 0x66FFFFFF, 40f) }
        edLay.addView(tvStatus, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 10 })
        ivEd = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        edLay.addView(ivEd, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { bottomMargin = 10 })

        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 20, 28, 20); background = glass(0x40FFFFFF, 0x16FFFFFF, 0x88FFFFFF, 52f) }
        card.addView(TextView(this).apply { text = "↔ VLEVO Bez AI | VPRAVO Lanczos-3 + PDE AI"; setTextColor(Color.YELLOW); textSize = 12f; typeface = Typeface.DEFAULT_BOLD })
        sbSplit = SeekBar(this).apply {
            max = 100; progress = 50
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) { renderSplit() }
                override fun onStartTrackingTouch(s: SeekBar?) {}; override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        card.addView(sbSplit, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 6 })

        val upRow = LinearLayout(this)
        upRow.addView(Button(this).apply {
            text = "✨ 2x Lanczos Upscale"; setTextColor(Color.WHITE); textSize = 11f; typeface = Typeface.DEFAULT_BOLD
            background = glass(0xDD00C6FF, 0xBB0062FF, 0xBBFFFFFF, 36f); setOnClickListener { curScale = 2; recalcAi() }
        }, LinearLayout.LayoutParams(0, 105, 1f).apply { rightMargin = 6 })
        upRow.addView(Button(this).apply {
            text = "🚀 4x Ultra Super-Res"; setTextColor(Color.WHITE); textSize = 11f; typeface = Typeface.DEFAULT_BOLD
            background = glass(0xDDAA00FF, 0xBB5500CC, 0xBBFFFFFF, 36f); setOnClickListener { curScale = 4; recalcAi() }
        }, LinearLayout.LayoutParams(0, 105, 1f).apply { leftMargin = 6 })
        card.addView(upRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 6 })

        sbDepix = slider(card, "1. Anisotropic De-Pixelate (Odstranění čtverečků)", 100, 80)
        sbDetail = slider(card, "2. Deconvolution Edge Detail (Ostré hrany)", 100, 75)
        sbClarity = slider(card, "3. Local Texture Structure (Kresba bez změny jasu)", 100, 60)

        val act = LinearLayout(this)
        act.addView(Button(this).apply { text = "Retake"; setTextColor(Color.WHITE); background = glass(0x38FFFFFF, 0x14FFFFFF, 0x66FFFFFF, 40f); setOnClickListener { edLay.visibility = View.GONE; camLay.visibility = View.VISIBLE } }, LinearLayout.LayoutParams(0, 108, 1f).apply { rightMargin = 8 })
        act.addView(Button(this).apply { text = "Save AI"; setTextColor(Color.WHITE); background = glass(0xDD00E676, 0xAA00B0FF, 0xBBFFFFFF, 40f); setOnClickListener { saveImg() } }, LinearLayout.LayoutParams(0, 108, 1f).apply { leftMargin = 8 })
        card.addView(act, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 8 })
        edLay.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        prog = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(camLay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)); root.addView(edLay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(prog, FrameLayout.LayoutParams(150, 150, Gravity.CENTER)); setContentView(root)
    }

    private fun slider(p: LinearLayout, txt: String, m: Int, d: Int): SeekBar {
        p.addView(TextView(this).apply { text = txt; setTextColor(Color.WHITE); textSize = 11f })
        return SeekBar(this).apply {
            max = m; progress = d
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) {}; override fun onStartTrackingTouch(s: SeekBar?) {}; override fun onStopTrackingTouch(s: SeekBar?) { recalcAi() }
            }); p.addView(this)
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun startCam() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            val p = f.get(); val prev = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
            val cb = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).setJpegQuality(100)
            Camera2Interop.Extender(cb).setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY).setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
            imgCap = cb.build()
            try { p.unbindAll(); cam = p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, prev, imgCap); cam?.cameraInfo?.zoomState?.observe(this) { maxHw = it.maxZoomRatio.coerceAtLeast(1f) } } catch (e: Exception) {}
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setZoom(z: Float) {
        curZoom = z.coerceIn(1f, 100f); val hw = min(curZoom, maxHw); cam?.cameraControl?.setZoomRatio(hw); swZoom = (curZoom / hw).coerceAtLeast(1f)
        pv.scaleX = swZoom; pv.scaleY = swZoom; tvZoom.text = String.format("XIAOMI 14T • %.1fx LANCZOS AI", curZoom)
    }

    private fun takePhoto() {
        val cap = imgCap ?: return; prog.visibility = View.VISIBLE
        cap.takePicture(ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(img: ImageProxy) {
                val rot = img.imageInfo.rotationDegrees; val buf: ByteBuffer = img.planes[0].buffer; val bytes = ByteArray(buf.remaining()).also { buf.get(it) }; img.close()
                exec.execute {
                    var b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (rot != 0) b = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
                    val cw = max(140, (b.width / swZoom).roundToInt()); val ch = max(140, (b.height / swZoom).roundToInt())
                    val crop = Bitmap.createBitmap(b, (b.width - cw) / 2, (b.height - ch) / 2, cw, ch)
                    rawBmp = crop; curScale = 2
                    aiBmp = processRealAi(crop, 2, 0.80f, 0.75f, 0.60f)
                    runOnUiThread { prog.visibility = View.GONE; sbSplit.progress = 50; sbDepix.progress = 80; sbDetail.progress = 75; sbClarity.progress = 60; camLay.visibility = View.GONE; edLay.visibility = View.VISIBLE; renderSplit() }
                }
            }
            override fun onError(e: ImageCaptureException) { prog.visibility = View.GONE }
        })
    }

    private fun recalcAi() {
        val orig = rawBmp ?: return; prog.visibility = View.VISIBLE
        val dp = sbDepix.progress / 100f; val dt = sbDetail.progress / 100f; val cl = sbClarity.progress / 100f
        exec.execute { aiBmp = processRealAi(orig, curScale, dp, dt, cl); runOnUiThread { prog.visibility = View.GONE; renderSplit() } }
    }

    private fun renderSplit() {
        val orig = rawBmp ?: return; val ai = aiBmp ?: return
        val w = ai.width; val h = ai.height; val sx = (w * (sbSplit.progress / 100f)).roundToInt().coerceIn(0, w)
        val comp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val cv = Canvas(comp)
        cv.drawBitmap(ai, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        if (sx > 0) { cv.save(); cv.clipRect(0, 0, sx, h); cv.drawBitmap(orig, Rect(0, 0, orig.width, orig.height), Rect(0, 0, w, h), null); cv.restore() }
        if (sx in 2..(w - 2)) cv.drawLine(sx.toFloat(), 0f, sx.toFloat(), h.toFloat(), Paint().apply { color = Color.CYAN; strokeWidth = 5f })
        ivEd.setImageBitmap(comp); tvStatus.text = "LANCZOS-3 + PDE AI • ${w}x${h} px (Raw: ${orig.width}x${orig.height})"
    }

    private fun processRealAi(src: Bitmap, scale: Int, depix: Float, detail: Float, clarity: Float): Bitmap {
        var w = pdeDiffusion(src, (2 + depix * 4).roundToInt(), 18f + depix * 35f)
        val sc = min(scale.toFloat(), 3000f / max(w.width, w.height)).coerceAtLeast(1.1f)
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
                        val d = ((if (abs(hf) > 1) (hf * gF).coerceIn(-22f, 22f) else 0f)
