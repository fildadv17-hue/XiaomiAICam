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
    private lateinit var swAi: Switch
    private lateinit var btnLeica: Button
    private lateinit var sbB: SeekBar
    private lateinit var sbC: SeekBar
    private lateinit var sbS: SeekBar
    private lateinit var sbD: SeekBar

    private var imgCap: ImageCapture? = null
    private var cam: androidx.camera.core.Camera? = null
    private val exec = Executors.newSingleThreadExecutor()
    private var curZoom = 1f
    private var maxHw = 20f
    private var swZoom = 1f
    private var leicaAuthentic = false
    private var baseBmp: Bitmap? = null
    private var edBmp: Bitmap? = null

    private val perm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) startCam() else Toast.makeText(this, "Povol přístup ke kameře", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        buildUi()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCam()
        else perm.launch(Manifest.permission.CAMERA)
    }

    private fun glass(c1: Long, c2: Long, st: Long, r: Float) =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c1.toInt(), c2.toInt())).apply {
            cornerRadius = r; setStroke(3, st.toInt())
        }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        camLay = FrameLayout(this)
        pv = PreviewView(this)
        camLay.addView(FrameLayout(this).apply {
            clipChildren = true
            addView(pv, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val top = LinearLayout(this).apply {
            setPadding(36, 24, 36, 24); gravity = Gravity.CENTER_VERTICAL
            background = glass(0x55FFFFFF, 0x22FFFFFF, 0x99FFFFFF, 60f)
        }
        tvZoom = TextView(this).apply {
            text = "14T LEICA • 1.0x"
            setTextColor(Color.WHITE); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
        }
        btnLeica = Button(this).apply {
            text = "LEICA VIBRANT"
            setTextColor(Color.WHITE); textSize = 11f; typeface = Typeface.DEFAULT_BOLD
            background = glass(0xCCDF0024, 0x99990018, 0xAAFFFFFF, 36f)
            setPadding(24, 0, 24, 0)
            setOnClickListener {
                leicaAuthentic = !leicaAuthentic
                text = if (leicaAuthentic) "LEICA AUTHENTIC" else "LEICA VIBRANT"
            }
        }
        swAi = Switch(this).apply {
            text = " AI "; isChecked = true; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        }
        top.addView(tvZoom, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        top.addView(btnLeica, LinearLayout.LayoutParams(WRAP_CONTENT, 84).apply { rightMargin = 16 })
        top.addView(swAi)
        camLay.addView(top, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP).apply { setMargins(24, 52, 24, 0) })

        val bot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(36, 32, 36, 40); gravity = Gravity.CENTER
            background = glass(0x48FFFFFF, 0x18FFFFFF, 0x99FFFFFF, 68f)
        }
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
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        bot.addView(Button(this).apply {
            text = "CAPTURE • XIAOMI 14T AI"; setTextColor(Color.WHITE); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
            background = glass(0xEEFF6900, 0xBBCC2200, 0xCCFFFFFF, 60f)
            setOnClickListener { takePhoto() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, 138))
        camLay.addView(bot, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply { setMargins(24, 0, 24, 36) })

        edLay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#090C12")); visibility = View.GONE; setPadding(28, 48, 28, 32)
        }
        tvStatus = TextView(this).apply {
            setTextColor(Color.parseColor("#70F3FF")); textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setPadding(28, 20, 28, 20)
            background = glass(0x38FFFFFF, 0x14FFFFFF, 0x66FFFFFF, 40f)
        }
        edLay.addView(tvStatus, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 12 })
        ivEd = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        edLay.addView(ivEd, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { bottomMargin = 12 })

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(32, 24, 32, 24)
            background = glass(0x40FFFFFF, 0x16FFFFFF, 0x88FFFFFF, 56f)
        }
        card.addView(Button(this).apply {
            text = "✨ Xiaomi 14T AI 2x Super-Res (Ultra Clean)"
            setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            background = glass(0xDD00C6FF, 0xBB0062FF, 0xBBFFFFFF, 44f)
            setOnClickListener { upscale2x() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, 118).apply { bottomMargin = 12 })

        sbB = slider(card, "Leica Exposure", 200, 100)
        sbC = slider(card, "Micro Contrast", 200, 100)
        sbS = slider(card, "Optical Acutance (No-Grain)", 100, 20)
        sbD = slider(card, "IMX906 Chroma Denoise", 100, 40)

        val act = LinearLayout(this)
        act.addView(Button(this).apply {
            text = "Retake"; setTextColor(Color.WHITE); background = glass(0x38FFFFFF, 0x14FFFFFF, 0x66FFFFFF, 40f)
            setOnClickListener { edLay.visibility = View.GONE; camLay.visibility = View.VISIBLE }
        }, LinearLayout.LayoutParams(0, 118, 1f).apply { rightMargin = 10 })
        act.addView(Button(this).apply {
            text = "Save Gallery"; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            background = glass(0xDD00E676, 0xAA00B0FF, 0xBBFFFFFF, 40f)
            setOnClickListener { saveImg() }
        }, LinearLayout.LayoutParams(0, 118, 1f).apply { leftMargin = 10 })
        card.addView(act, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 12 })
        edLay.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        prog = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(camLay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(edLay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(prog, FrameLayout.LayoutParams(160, 160, Gravity.CENTER))
        setContentView(root)
    }

    private fun slider(p: LinearLayout, txt: String, m: Int, d: Int): SeekBar {
        p.addView(TextView(this).apply { text = txt; setTextColor(Color.WHITE); textSize = 12f })
        return SeekBar(this).apply {
            max = m; progress = d
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) { if (u) updateEd() }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
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
            val capBuilder = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setJpegQuality(100)
            Camera2Interop.Extender(capBuilder)
                .setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                .setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                .setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)
            imgCap = capBuilder.build()
            try {
                p.unbindAll()
                cam = p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, prev, imgCap)
                cam?.cameraInfo?.zoomState?.observe(this) { maxHw = it.maxZoomRatio.coerceAtLeast(1f) }
            } catch (e: Exception) {}
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setZoom(z: Float) {
        curZoom = z.coerceIn(1f, 100f)
        val hw = min(curZoom, maxHw)
        cam?.cameraControl?.setZoomRatio(hw)
        swZoom = (curZoom / hw).coerceAtLeast(1f)
        pv.scaleX = swZoom; pv.scaleY = swZoom
        val lens = if (curZoom >= 2f) "TELE 50MP" else "IMX906 50MP"
        tvZoom.text = String.format("14T %s • %.1fx", lens, curZoom)
    }

    private fun takePhoto() {
        val cap = imgCap ?: return
        prog.visibility = View.VISIBLE
        cap.takePicture(ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(img: ImageProxy) {
                val rot = img.imageInfo.rotationDegrees
                val buf: ByteBuffer = img.planes[0].buffer
                val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                img.close()
                val ai = swAi.isChecked; val z = curZoom; val auth = leicaAuthentic
                exec.execute {
                    var b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (rot != 0) b = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
                    b = crop(b, swZoom)
                    if (ai) {
                        b = smoothBlurDenoise(b, if (z > 8f) 0.70f else 0.45f)
                        b = softHaloSharpen(b, if (z > 8f) 0.35f else 0.25f)
                    }
                    b = if (auth) color(b, -4f, 1.14f, 0.92f) else color(b, 2f, 1.06f, 1.14f)
                    baseBmp = b; edBmp = b
                    runOnUiThread {
                        prog.visibility = View.GONE
                        sbB.progress = 100; sbC.progress = 100; sbS.progress = 20; sbD.progress = 40
                        showEd()
                    }
                }
            }
            override fun onError(e: ImageCaptureException) { prog.visibility = View.GONE }
        })
    }

    private fun showEd() {
        val b = edBmp ?: return
        camLay.visibility = View.GONE; edLay.visibility = View.VISIBLE
        ivEd.setImageBitmap(b)
        tvStatus.text = "XIAOMI 14T LEICA AI • ${b.width} x ${b.height} px"
    }

    private fun updateEd() {
        val src = baseBmp ?: return
        val b = (sbB.progress - 100).toFloat(); val c = sbC.progress / 100f
        val s = sbS.progress / 100f; val d = sbD.progress / 100f
        exec.execute {
            var r = color(src, b, c, 1.0f)
            if (d > 0.05f) r = smoothBlurDenoise(r, d)
            if (s > 0.05f) r = softHaloSharpen(r, s * 0.5f)
            edBmp = r
            runOnUiThread { ivEd.setImageBitmap(r); tvStatus.text = "XIAOMI 14T LEICA AI • ${r.width} x ${r.height} px" }
        }
    }

    private fun upscale2x() {
        val src = edBmp ?: return
        prog.visibility = View.VISIBLE
        Toast.makeText(this, "Xiaomi 14T AI Super-Res...", Toast.LENGTH_SHORT).show()
        exec.execute {
            val w = min(src.width * 2, 4096); val h = min(src.height * 2, 4096)
            var b = smoothBlurDenoise(src, 0.75f)
            b = Bitmap.createScaledBitmap(b, (src.width + w) / 2, (src.height + h) / 2, true)
            b = Bitmap.createScaledBitmap(b, w, h, true)
            b = smoothBlurDenoise(b, 0.45f)
            b = softHaloSharpen(b, 0.38f)
            baseBmp = b; edBmp = b
            runOnUiThread { prog.visibility = View.GONE; showEd(); Toast.makeText(this, "Leica 2x Super-Res Hotovo!", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun crop(s: Bitmap, f: Float): Bitmap {
        if (f <= 1.02f) return s
        val cw = max(64, (s.width / f).roundToInt()); val ch = max(64, (s.height / f).roundToInt())
        return Bitmap.createScaledBitmap(Bitmap.createBitmap(s, (s.width - cw) / 2, (s.height - ch) / 2, cw, ch), s.width, s.height, true)
    }

    private fun color(s: Bitmap, b: Float, c: Float, sat: Float): Bitmap {
        val out = Bitmap.createBitmap(s.width, s.height, Bitmap.Config.ARGB_8888)
        val t = (-0.5f * c + 0.5f) * 255f + b
        val cm = ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f))
        cm.postConcat(ColorMatrix().apply { setSaturation(sat) })
        Canvas(out).drawBitmap(s, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(cm) })
        return out
    }

    // Čisté vyhlazení šumu přes hardwarovou bikubickou interpolaci + zachování hran (nikdy nevytváří zrnění)
    private fun smoothBlurDenoise(s: Bitmap, str: Float): Bitmap {
        val w = s.width; val h = s.height
        val factor = (1f - str * 0.45f).coerceIn(0.45f, 0.92f)
        val small = Bitmap.createScaledBitmap(s, max(32, (w * factor).roundToInt()), max(32, (h * factor).roundToInt()), true)
        val smooth = Bitmap.createScaledBitmap(small, w, h, true)
        val pOrig = IntArray(w * h); val pSmooth = IntArray(w * h)
        s.getPixels(pOrig, 0, w, 0, 0, w, h); smooth.getPixels(pSmooth, 0, w, 0, 0, w, h)
        val thr = (32 + str * 60).toInt()
        val bl = str.coerceIn(0.15f, 0.85f)
        for (i in pOrig.indices) {
            val c1 = pOrig[i]; val c2 = pSmooth[i]
            val r1 = c1 shr 16 and 0xFF; val g1 = c1 shr 8 and 0xFF; val b1 = c1 and 0xFF
            val r2 = c2 shr 16 and 0xFF; val g2 = c2 shr 8 and 0xFF; val b2 = c2 and 0xFF
            val diff = abs(r1 - r2) + abs(g1 - g2) + abs(b1 - b2)
            // Pokud je rozdíl malý (šum na ploše), vyhladíme ho; pokud je to ostrá hrana, necháme ji ostrou
            if (diff < thr) {
                val nr = (r1 * (1f - bl) + r2 * bl).roundToInt().coerceIn(0, 255)
                val ng = (g1 * (1f - bl) + g2 * bl).roundToInt().coerceIn(0, 255)
                val nb = (b1 * (1f - bl) + b2 * bl).roundToInt().coerceIn(0, 255)
                pOrig[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(pOrig, 0, w, 0, 0, w, h) }
    }

    // Jemné optické doostření s omezením maximální změny o ±18 úrovní jasu (žádné bílé tečky ani zrno)
    private fun softHaloSharpen(s: Bitmap, amt: Float): Bitmap {
        val w = s.width; val h = s.height
        val half = Bitmap.createScaledBitmap(s, max(32, w / 2), max(32, h / 2), true)
        val blur = Bitmap.createScaledBitmap(half, w, h, true)
        val pOrig = IntArray(w * h); val pBlur = IntArray(w * h)
        s.getPixels(pOrig, 0, w, 0, 0, w, h); blur.getPixels(pBlur, 0, w, 0, 0, w, h)
        val gain = amt.coerceIn(0.05f, 0.65f)
        for (i in pOrig.indices) {
            val c1 = pOrig[i]; val c2 = pBlur[i]
            val r1 = c1 shr 16 and 0xFF; val g1 = c1 shr 8 and 0xFF; val b1 = c1 and 0xFF
            val r2 = c2 shr 16 and 0xFF; val g2 = c2 shr 8 and 0xFF; val b2 = c2 and 0xFF
            val dr = r1 - r2; val dg = g1 - g2; val db = b1 - b2
            val lumDiff = (abs(dr) + abs(dg) + abs(db)) / 3
            if (lumDiff < 8) continue // Ignorujeme jemný šum
            val boostR = (dr * gain).roundToInt().coerceIn(-18, 18)
            val boostG = (dg * gain).roundToInt().coerceIn(-18, 18)
            val boostB = (db * gain).roundToInt().coerceIn(-18, 18)
            val nr = (r1 + boostR).coerceIn(0, 255)
            val ng = (g1 + boostG).coerceIn(0, 255)
            val nb = (b1 + boostB).coerceIn(0, 255)
            pOrig[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(pOrig, 0, w, 0, 0, w, h) }
    }

    private fun saveImg() {
        val b = edBmp ?: return
        prog.visibility = View.VISIBLE
        exec.execute {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "Xiaomi14T_Leica_${System.currentTimeMillis()}.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/XiaomiAICam")
            }
            contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)?.let {
                contentResolver.openOutputStream(it)?.use { s -> b.compress(Bitmap.CompressFormat.JPEG, 100, s) }
            }
            runOnUiThread { prog.visibility = View.GONE; Toast.makeText(this, "Uloženo do Galerie!", Toast.LENGTH_LONG).show() }
        }
    }
}
