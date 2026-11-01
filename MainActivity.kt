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
import kotlin.math.*

class MainActivity : AppCompatActivity() {
    private lateinit var pv: PreviewView; private lateinit var camLay: FrameLayout; private lateinit var edLay: LinearLayout
    private lateinit var ivEd: ImageView; private lateinit var tvZoom: TextView; private lateinit var tvStatus: TextView
    private lateinit var prog: ProgressBar; private lateinit var sbSplit: SeekBar
    private lateinit var sbDepix: SeekBar; private lateinit var sbQual: SeekBar; private lateinit var sbBri: SeekBar
    private var imgCap: ImageCapture? = null; private var cam: androidx.camera.core.Camera? = null
    private val exec = Executors.newSingleThreadExecutor()
    private var curZoom = 1f; private var maxHw = 20f; private var swZoom = 1f
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
        tvZoom = TextView(this).apply { text = "14T AI • 1.0x"; setTextColor(Color.WHITE); textSize = 15f; typeface = Typeface.DEFAULT_BOLD }
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
            text = "CAPTURE • TRUE QUALITY AI"; setTextColor(Color.WHITE); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
            background = glass(0xEEFF6900, 0xBBCC2200, 0xCCFFFFFF, 60f); setOnClickListener { takePhoto() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, 136))
        camLay.addView(bot, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply { setMargins(24, 0, 24, 36) })

        edLay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#090C12")); visibility = View.GONE; setPadding(24, 44, 24, 28) }
        tvStatus = TextView(this).apply { setTextColor(Color.CYAN); textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setPadding(24, 16, 24, 16); background = glass(0x38FFFFFF, 0x14FFFFFF, 0x66FFFFFF, 40f) }
        edLay.addView(tvStatus, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 10 })
        ivEd = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        edLay.addView(ivEd, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { bottomMargin = 10 })

        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 20, 28, 20); background = glass(0x40FFFFFF, 0x16FFFFFF, 0x88FFFFFF, 52f) }
        card.addView(TextView(this).apply { text = "↔ VLEVO Bez AI | VPRAVO AI Upscaled"; setTextColor(Color.YELLOW); textSize = 12f; typeface = Typeface.DEFAULT_BOLD })
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
            text = "✨ 2x AI Upscaler"; setTextColor(Color.WHITE); textSize = 11f; typeface = Typeface.DEFAULT_BOLD
            background = glass(0xDD00C6FF, 0xBB0062FF, 0xBBFFFFFF, 36f); setOnClickListener { runUpscaler(2) }
        }, LinearLayout.LayoutParams(0, 105, 1f).apply { rightMargin = 6 })
        upRow.addView(Button(this).apply {
            text = "🚀 4x Ultra Upscaler"; setTextColor(Color.WHITE); textSize = 11f; typeface = Typeface.DEFAULT_BOLD
            background = glass(0xDDAA00FF, 0xBB5500CC, 0xBBFFFFFF, 36f); setOnClickListener { runUpscaler(4) }
        }, LinearLayout.LayoutParams(0, 105, 1f).apply { leftMargin = 6 })
        card.addView(upRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 6 })

        sbDepix = slider(card, "1. De-Pixelate (Odstranění čtverečků)", 100, 80)
        sbQual = slider(card, "2. AI Edge & Texture Quality (Kresba)", 100, 65)
        sbBri = slider(card, "3. Pure Brightness (Pouze jas)", 200, 100)

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
        pv.scaleX = swZoom; pv.scaleY = swZoom; tvZoom.text = String.format("14T • %.1fx", curZoom)
    }

    private fun takePhoto() {
        val cap = imgCap ?: return; prog.visibility = View.VISIBLE
        cap.takePicture(ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(img: ImageProxy) {
                val rot = img.imageInfo.rotationDegrees; val buf: ByteBuffer = img.planes[0].buffer; val bytes = ByteArray(buf.remaining()).also { buf.get(it) }; img.close()
                exec.execute {
                    var b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (rot != 0) b = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
                    val cw = max(120, (b.width / swZoom).roundToInt()); val ch = max(120, (b.height / swZoom).roundToInt())
                    val crop = Bitmap.createBitmap(b, (b.width - cw) / 2, (b.height - ch) / 2, cw, ch)
                    rawBmp = crop
                    aiBmp = processQualityAi(crop, 2, 0.80f, 0.65f, 0f)
                    runOnUiThread { prog.visibility = View.GONE; sbSplit.progress = 50; sbDepix.progress = 80; sbQual.progress = 65; sbBri.progress = 100; camLay.visibility = View.GONE; edLay.visibility = View.VISIBLE; renderSplit() }
                }
            }
            override fun onError(e: ImageCaptureException) { prog.visibility = View.GONE }
        })
    }

    private fun recalcAi() {
        val orig = rawBmp ?: return; prog.visibility = View.VISIBLE
        val dp = sbDepix.progress / 100f; val q = sbQual.progress / 100f; val br = (sbBri.progress - 100).toFloat()
        exec.execute { aiBmp = processQualityAi(orig, 2, dp, q, br); runOnUiThread { prog.visibility = View.GONE; renderSplit() } }
    }

    private fun runUpscaler(factor: Int) {
        val orig = rawBmp ?: return; prog.visibility = View.VISIBLE
        val dp = sbDepix.progress / 100f; val q = sbQual.progress / 100f; val br = (sbBri.progress - 100).toFloat()
        Toast.makeText(this, "AI ${factor}x Super-Resolution...", Toast.LENGTH_SHORT).show()
        exec.execute {
            aiBmp = processQualityAi(orig, factor, (dp + 0.1f).coerceAtMost(1f), (q + 0.15f).coerceAtMost(1f), br)
            runOnUiThread { prog.visibility = View.GONE; renderSplit() }
        }
    }

    private fun renderSplit() {
        val orig = rawBmp ?: return; val ai = aiBmp ?: return
        val w = ai.width; val h = ai.height; val sx = (w * (sbSplit.progress / 100f)).roundToInt().coerceIn(0, w)
        val comp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val cv = Canvas(comp)
        cv.drawBitmap(ai, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        if (sx > 0) { cv.save(); cv.clipRect(0, 0, sx, h); cv.drawBitmap(orig, Rect(0, 0, orig.width, orig.height), Rect(0, 0, w, h), null); cv.restore() }
        if (sx in 2..(w - 2)) cv.drawLine(sx.toFloat(), 0f, sx.toFloat(), h.toFloat(), Paint().apply { color = Color.CYAN; strokeWidth = 5f })
        ivEd.setImageBitmap(comp); tvStatus.text = "AI SUPER-RES • ${w}x${h} px (Originál: ${orig.width}x${orig.height})"
    }

    // Skutečný De-Pixelate + Multi-Step Super-Resolution Upscaler (nemění jas, čistí kostičky a rekonstruuje hrany)
    private fun processQualityAi(src: Bitmap, upFactor: Int, depix: Float, qual: Float, bri: Float): Bitmap {
        // 1. Pre-Upscale Bilateral De-Blocking (vyhladí čtverečky přímo na malém výřezu před zvětšením)
        var cur = bilateralClean(src, depix)
        val maxEdge = max(src.width, src.height)
        val targetScale = min(upFactor.toFloat(), 3200f / maxEdge).coerceAtLeast(1.2f)
        val dstW = (src.width * targetScale).roundToInt(); val dstH = (src.height * targetScale).roundToInt()

        // 2. Postupné sub-pixelové převzorkování po krocích 1.35x s mezikrokovým čištěním hran
        while (cur.width * 1.35f < dstW) {
            val nw = (cur.width * 1.35f).roundToInt(); val nh = (cur.height * 1.35f).roundToInt()
            cur = Bitmap.createScaledBitmap(cur, nw, nh, true)
            cur = edgeGuidedReconstruct(cur, depix * 0.6f, qual * 0.5f)
        }
        cur = Bitmap.createScaledBitmap(cur, dstW, dstH, true)

        // 3. Finální rekonstrukce ostrosti a vyhlazení schodů na vysokém rozlišení
        cur = edgeGuidedReconstruct(cur, depix, qual)

        if (abs(bri) < 1f) return cur
        val out = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
        val cm = ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, bri, 0f, 1f, 0f, 0f, bri, 0f, 0f, 1f, 0f, bri, 0f, 0f, 0f, 1f, 0f))
        Canvas(out).drawBitmap(cur, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }); return out
    }

    private fun bilateralClean(s: Bitmap, str: Float): Bitmap {
        if (str < 0.05f) return s
        val w = s.width; val h = s.height; val p = IntArray(w * h); s.getPixels(p, 0, w, 0, 0, w, h); val out = p.clone()
        val thr = (25 + str * 75).toInt(); val bl = (str * 0.85f).coerceIn(0.1f, 0.9f)
        for (y in 1 until h - 1) {
            val r = y * w
            for (x in 1 until w - 1) {
                val i = r + x; val c = p[i]; val cr = c shr 16 and 0xFF; val cg = c shr 8 and 0xFF; val cb = c and 0xFF
                var sr = cr * 2; var sg = cg * 2; var sb = cb * 2; var wt = 2
                val nb = intArrayOf(p[i - 1], p[i + 1], p[i - w], p[i + w], p[i - w - 1], p[i - w + 1], p[i + w - 1], p[i + w + 1])
                for (n in nb) {
                    val nr = n shr 16 and 0xFF; val ng = n shr 8 and 0xFF; val nbl = n and 0xFF
                    if (abs(cr - nr) + abs(cg - ng) + abs(cb - nbl) < thr) { sr += nr; sg += ng; sb += nbl; wt++ }
                }
                val fr = (cr * (1f - bl) + (sr / wt) * bl).roundToInt().coerceIn(0, 255)
                val fg = (cg * (1f - bl) + (sg / wt) * bl).roundToInt().coerceIn(0, 255)
                val fb = (cb * (1f - bl) + (sb / wt) * bl).roundToInt().coerceIn(0, 255)
                out[i] = (0xFF shl 24) or (fr shl 16) or (fg shl 8) or fb
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(out, 0, w, 0, 0, w, h) }
    }

    private fun edgeGuidedReconstruct(s: Bitmap, depix: Float, qual: Float): Bitmap {
        val w = s.width; val h = s.height
        val rad = (1f - depix * 0.38f).coerceIn(0.55f, 0.92f)
        val smooth = Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(s, max(32, (w * rad).roundToInt()), max(32, (h * rad).roundToInt()), true), w, h, true)
        val wide = Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(smooth, max(24, w / 3), max(24, h / 3), true), w, h, true)
        val pO = IntArray(w * h); val pS = IntArray(w * h); val pW = IntArray(w * h)
        s.getPixels(pO, 0, w, 0, 0, w, h); smooth.getPixels(pS, 0, w, 0, 0, w, h); wide.getPixels(pW, 0, w, 0, 0, w, h)
        val mix = (depix * 0.85f).coerceIn(0.1f, 0.92f); val gain = qual * 1.9f
        for (i in pO.indices) {
            val cO = pO[i]; val cS = pS[i]; val cW = pW[i]
            val rS = cS shr 16 and 0xFF; val gS = cS shr 8 and 0xFF; val bS = cS and 0xFF
            val bR = (cO shr 16 and 0xFF) * (1f - mix) + rS * mix
            val bG = (cO shr 8 and 0xFF) * (1f - mix) + gS * mix
            val bB = (cO and 0xFF) * (1f - mix) + bS * mix
            val nr = (bR + ((rS - (cW shr 16 and 0xFF)) * gain).coerceIn(-26f, 26f)).roundToInt().coerceIn(0, 255)
            val ng = (bG + ((gS - (cW shr 8 and 0xFF)) * gain).coerceIn(-26f, 26f)).roundToInt().coerceIn(0, 255)
            val nb = (bB + ((bS - (cW and 0xFF)) * gain).coerceIn(-26f, 26f)).roundToInt().coerceIn(0, 255)
            pO[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(pO, 0, w, 0, 0, w, h) }
    }

    private fun saveImg() {
        val b = aiBmp ?: return; prog.visibility = View.VISIBLE
        exec.execute {
            val cv = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, "14T_AI_${System.currentTimeMillis()}.jpg"); put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg") }
            contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)?.let { contentResolver.openOutputStream(it)?.use { s -> b.compress(Bitmap.CompressFormat.JPEG, 100, s) } }
            runOnUiThread { prog.visibility = View.GONE; Toast.makeText(this, "Uloženo!", Toast.LENGTH_LONG).show() }
        }
    }
}
