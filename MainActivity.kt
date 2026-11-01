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
    private lateinit var pv: PreviewView
    private lateinit var camLay: FrameLayout
    private lateinit var edLay: LinearLayout
    private lateinit var ivEd: ImageView
    private lateinit var tvZoom: TextView
    private lateinit var tvStatus: TextView
    private lateinit var prog: ProgressBar
    private lateinit var btnLeica: Button
    private lateinit var sbSplit: SeekBar
    private lateinit var sbS: SeekBar
    private lateinit var sbC: SeekBar
    private lateinit var sbD: SeekBar

    private var imgCap: ImageCapture? = null
    private var cam: androidx.camera.core.Camera? = null
    private val exec = Executors.newSingleThreadExecutor()
    private var curZoom = 1f
    private var maxHw = 20f
    private var swZoom = 1f
    private var leicaAuth = false
    private var rawBmp: Bitmap? = null
    private var aiBmp: Bitmap? = null

    private val perm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) startCam() else Toast.makeText(this, "Povol kameru", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        buildUi()
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
        tvZoom = TextView(this).apply { text = "14T LEICA • 1.0x"; setTextColor(Color.WHITE); textSize = 15f; typeface = Typeface.DEFAULT_BOLD }
        btnLeica = Button(this).apply {
            text = "LEICA VIBRANT"; setTextColor(Color.WHITE); textSize = 11f; typeface = Typeface.DEFAULT_BOLD
            background = glass(0xCCDF0024, 0x99990018, 0xAAFFFFFF, 36f); setPadding(24, 0, 24, 0)
            setOnClickListener { leicaAuth = !leicaAuth; text = if (leicaAuth) "LEICA AUTHENTIC" else "LEICA VIBRANT" }
        }
        top.addView(tvZoom, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        top.addView(btnLeica, LinearLayout.LayoutParams(WRAP_CONTENT, 84))
        camLay.addView(top, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP).apply { setMargins(24, 52, 24, 0) })

        val bot = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 32, 36, 40); gravity = Gravity.CENTER; background = glass(0x48FFFFFF, 0x18FFFFFF, 0x99FFFFFF, 68f) }
        val sbZ = SeekBar(this).apply { max = 990 }
        val zRow = LinearLayout(this).apply { gravity = Gravity.CENTER }
        listOf(1f, 2f, 4f, 20f, 100f).forEach { z ->
            zRow.addView(Button(this).apply {
                text = "${z.toInt()}x"; setTextColor(Color.WHITE); textSize = 12f; typeface = Typeface.DEFAULT_BOLD
                background = glass(0x40FFFFFF, 0x15FFFFFF, 0x77FFFFFF, 36f)
                setOnClickListener { sbZ.progress = ((z - 1f) * 10f).toInt() }
            }, LinearLayout.LayoutParams(0, 100, 1f).apply { setMargins(6, 0, 6, 0) })
        }
        bot.addView(zRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        bot.addView(sbZ, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 24; bottomMargin = 24 })
        sbZ.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { setZoom(1f + p / 10f) }
            override fun onStartTrackingTouch(s: SeekBar?) {}; override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        bot.addView(Button(this).apply {
            text = "CAPTURE • TRUE-DETAIL AI"; setTextColor(Color.WHITE); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
            background = glass(0xEEFF6900, 0xBBCC2200, 0xCCFFFFFF, 60f); setOnClickListener { takePhoto() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, 138))
        camLay.addView(bot, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply { setMargins(24, 0, 24, 36) })

        edLay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#090C12")); visibility = View.GONE; setPadding(24, 44, 24, 28) }
        tvStatus = TextView(this).apply { setTextColor(Color.parseColor("#70F3FF")); textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setPadding(24, 16, 24, 16); background = glass(0x38FFFFFF, 0x14FFFFFF, 0x66FFFFFF, 40f) }
        edLay.addView(tvStatus, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 10 })
        ivEd = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        edLay.addView(ivEd, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { bottomMargin = 10 })

        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 20, 28, 20); background = glass(0x40FFFFFF, 0x16FFFFFF, 0x88FFFFFF, 52f) }
        card.addView(TextView(this).apply { text = "↔ Porovnání: VLEVO Bez AI | VPRAVO S AI"; setTextColor(Color.YELLOW); textSize = 12f; typeface = Typeface.DEFAULT_BOLD })
        sbSplit = SeekBar(this).apply {
            max = 100; progress = 50
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) { renderSplit() }
                override fun onStartTrackingTouch(s: SeekBar?) {}; override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        card.addView(sbSplit, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 8 })
        card.addView(Button(this).apply {
            text = "✨ True-Detail 2x Upscale (4:3 Aspect)"; setTextColor(Color.WHITE); textSize = 12f; typeface = Typeface.DEFAULT_BOLD
            background = glass(0xDD00C6FF, 0xBB0062FF, 0xBBFFFFFF, 40f); setOnClickListener { upscale2x() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, 110).apply { bottomMargin = 8 })

        sbS = slider(card, "AI Crisp Micro-Edges (Ostrost)", 100, 60)
        sbC = slider(card, "Leica Micro-Contrast", 200, 112)
        sbD = slider(card, "Surface Denoise (Bez rozmazání)", 100, 25)

        val act = LinearLayout(this)
        act.addView(Button(this).apply {
            text = "Retake"; setTextColor(Color.WHITE); background = glass(0x38FFFFFF, 0x14FFFFFF, 0x66FFFFFF, 40f)
            setOnClickListener { edLay.visibility = View.GONE; camLay.visibility = View.VISIBLE }
        }, LinearLayout.LayoutParams(0, 112, 1f).apply { rightMargin = 10 })
        act.addView(Button(this).apply {
            text = "Save AI Photo"; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            background = glass(0xDD00E676, 0xAA00B0FF, 0xBBFFFFFF, 40f); setOnClickListener { saveImg() }
        }, LinearLayout.LayoutParams(0, 112, 1f).apply { leftMargin = 10 })
        card.addView(act, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 10 })
        edLay.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        prog = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(camLay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(edLay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(prog, FrameLayout.LayoutParams(160, 160, Gravity.CENTER))
        setContentView(root)
    }

    private fun slider(p: LinearLayout, txt: String, m: Int, d: Int): SeekBar {
        p.addView(TextView(this).apply { text = txt; setTextColor(Color.WHITE); textSize = 11f })
        return SeekBar(this).apply {
            max = m; progress = d
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) {}
                override fun onStartTrackingTouch(s: SeekBar?) {}; override fun onStopTrackingTouch(s: SeekBar?) { recalcAi() }
            })
            p.addView(this)
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun startCam() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            val p = f.get()
            val prev = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
            val cb = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).setJpegQuality(100)
            Camera2Interop.Extender(cb)
                .setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                .setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
            imgCap = cb.build()
            try {
                p.unbindAll()
                cam = p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, prev, imgCap)
                cam?.cameraInfo?.zoomState?.observe(this) { maxHw = it.maxZoomRatio.coerceAtLeast(1f) }
            } catch (e: Exception) {}
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setZoom(z: Float) {
        curZoom = z.coerceIn(1f, 100f); val hw = min(curZoom, maxHw)
        cam?.cameraControl?.setZoomRatio(hw); swZoom = (curZoom / hw).coerceAtLeast(1f)
        pv.scaleX = swZoom; pv.scaleY = swZoom
        tvZoom.text = String.format("14T %s • %.1fx", if (curZoom >= 2f) "TELE 50MP" else "IMX906", curZoom)
    }

    private fun takePhoto() {
        val cap = imgCap ?: return; prog.visibility = View.VISIBLE
        cap.takePicture(ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(img: ImageProxy) {
                val rot = img.imageInfo.rotationDegrees; val buf: ByteBuffer = img.planes[0].buffer
                val bytes = ByteArray(buf.remaining()).also { buf.get(it) }; img.close()
                exec.execute {
                    var b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (rot != 0) b = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
                    b = cropAspect(b, swZoom)
                    rawBmp = b; aiBmp = sobelAi(b, 0.60f, 1.12f, 0.25f, leicaAuth)
                    runOnUiThread {
                        prog.visibility = View.GONE; sbSplit.progress = 50; sbS.progress = 60; sbC.progress = 112; sbD.progress = 25
                        camLay.visibility = View.GONE; edLay.visibility = View.VISIBLE; renderSplit()
                    }
                }
            }
            override fun onError(e: ImageCaptureException) { prog.visibility = View.GONE }
        })
    }

    private fun recalcAi() {
        val orig = rawBmp ?: return; prog.visibility = View.VISIBLE
        val s = sbS.progress / 100f; val c = sbC.progress / 100f; val d = sbD.progress / 100f; val auth = leicaAuth
        exec.execute { aiBmp = sobelAi(orig, s, c, d, auth); runOnUiThread { prog.visibility = View.GONE; renderSplit() } }
    }

    private fun renderSplit() {
        val orig = rawBmp ?: return; val ai = aiBmp ?: return
        val w = ai.width; val h = ai.height; val splitX = (w * (sbSplit.progress / 100f)).roundToInt().coerceIn(0, w)
        val comp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val cv = Canvas(comp)
        cv.drawBitmap(ai, 0f, 0f, null)
        if (splitX > 0) {
            cv.save(); cv.clipRect(0, 0, splitX, h)
            cv.drawBitmap(orig, Rect(0, 0, orig.width, orig.height), Rect(0, 0, w, h), null); cv.restore()
        }
        if (splitX in 2..(w - 2)) {
            cv.drawLine(splitX.toFloat(), 0f, splitX.toFloat(), h.toFloat(), Paint().apply { color = Color.CYAN; strokeWidth = max(4f, w * 0.004f) })
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = max(28f, w * 0.028f); typeface = Typeface.DEFAULT_BOLD; setShadowLayer(8f, 0f, 2f, Color.BLACK) }
            if (splitX > w * 0.2f) cv.drawText("◀ BEZ AI", 32f, h * 0.08f, tp)
            if (splitX < w * 0.8f) cv.drawText("S AI ▶", splitX + 28f, h * 0.08f, tp)
        }
        ivEd.setImageBitmap(comp)
        tvStatus.text = "14T TRUE-DETAIL • ${w}x${h} px (AI: ${100 - sbSplit.progress}%)"
    }

    private fun sobelAi(src: Bitmap, sh: Float, co: Float, dn: Float, auth: Boolean): Bitmap {
        val w = src.width; val h = src.height; val p = IntArray(w * h); src.getPixels(p, 0, w, 0, 0, w, h)
        val out = p.clone(); val luma = IntArray(w * h)
        for (i in p.indices) { val c = p[i]; luma[i] = ((c shr 16 and 0xFF) * 77 + (c shr 8 and 0xFF) * 150 + (c and 0xFF) * 29) shr 8 }
        val gain = sh * 1.4f; val dThr = (14 + dn * 38).toInt(); val dStr = (dn * 0.65f).coerceIn(0f, 0.7f)
        for (y in 1 until h - 1) {
            val r = y * w
            for (x in 1 until w - 1) {
                val i = r + x; val c = p[i]; val lum = luma[i]
                val lT = luma[i - w]; val lB = luma[i + w]; val lL = luma[i - 1]; val lR = luma[i + 1]
                val lTL = luma[i - w - 1]; val lTR = luma[i - w + 1]; val lBL = luma[i + w - 1]; val lBR = luma[i + w + 1]
                val edge = (abs((lTR + 2 * lR + lBR) - (lTL + 2 * lL + lBL)) + abs((lBL + 2 * lB + lBR) - (lTL + 2 * lT + lTR))) shr 2
                val det = lum - ((lT + lB + lL + lR + lTL + lTR + lBL + lBR) shr 3)
                val delta = if (edge > 15) (det * gain * ((edge - 15) / 45f).coerceIn(0.2f, 1f)).roundToInt().coerceIn(-28, 28)
                else if (dn > 0.05f && abs(det) < dThr) (-det * dStr).roundToInt() else 0
                out[i] = (0xFF shl 24) or ((c shr 16 and 0xFF) + delta).coerceIn(0, 255).shl(16) or ((c shr 8 and 0xFF) + delta).coerceIn(0, 255).shl(8) or ((c and 0xFF) + delta).coerceIn(0, 255)
            }
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(out, 0, w, 0, 0, w, h) }
        val t = (-0.5f * co + 0.5f) * 255f + (if (auth) -2f else 3f)
        val cm = ColorMatrix(floatArrayOf(co, 0f, 0f, 0f, t, 0f, co, 0f, 0f, t, 0f, 0f, co, 0f, t, 0f, 0f, 0f, 1f, 0f)).apply { postConcat(ColorMatrix().apply { setSaturation(if (auth) 0.95f else 1.12f) }) }
        val res = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(res).drawBitmap(bmp, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(cm) })
        return res
    }

    private fun upscale2x() {
        val orig = rawBmp ?: return; prog.visibility = View.VISIBLE
        val s = sbS.progress / 100f; val c = sbC.progress / 100f; val d = sbD.progress / 100f; val auth = leicaAuth
        exec.execute {
            val sc = min(2f, 4096f / max(orig.width, orig.height))
            val up = Bitmap.createScaledBitmap(orig, (orig.width * sc).roundToInt(), (orig.height * sc).roundToInt(), true)
            rawBmp = up; aiBmp = sobelAi(up, (s + 0.15f).coerceAtMost(1f), c, d, auth)
            runOnUiThread { prog.visibility = View.GONE; renderSplit() }
        }
    }

    private fun cropAspect(s: Bitmap, f: Float): Bitmap {
        if (f <= 1.02f) return s
        val cw = max(64, (s.width / f).roundToInt()); val ch = max(64, (s.height / f).roundToInt())
        return Bitmap.createScaledBitmap(Bitmap.createBitmap(s, (s.width - cw) / 2, (s.height - ch) / 2, cw, ch), s.width, s.height, true)
    }

    private fun saveImg() {
        val b = aiBmp ?: return; prog.visibility = View.VISIBLE
        exec.execute {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "Xiaomi14T_AI_${System.currentTimeMillis()}.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/XiaomiAICam")
            }
            contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)?.let { contentResolver.openOutputStream(it)?.use { s -> b.compress(Bitmap.CompressFormat.JPEG, 100, s) } }
            runOnUiThread { prog.visibility = View.GONE; Toast.makeText(this, "Uloženo do Galerie!", Toast.LENGTH_LONG).show() }
        }
    }
}
