package com.example.xiaomiaicam

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.provider.MediaStore
import android.view.*
import android.view.ViewGroup.LayoutParams.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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
    private lateinit var sbB: SeekBar
    private lateinit var sbC: SeekBar
    private lateinit var sbS: SeekBar
    private lateinit var sbD: SeekBar

    private var imgCap: ImageCapture? = null
    private var cam: androidx.camera.core.Camera? = null
    private val exec = Executors.newSingleThreadExecutor()
    private var curZoom = 1f
    private var maxHw = 10f
    private var swZoom = 1f
    private var baseBmp: Bitmap? = null
    private var edBmp: Bitmap? = null

    private val perm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) startCam() else Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
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
        camLay.addView(FrameLayout(this).apply { clipChildren = true; addView(pv, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)) }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val top = LinearLayout(this).apply {
            setPadding(40, 28, 40, 28); gravity = Gravity.CENTER_VERTICAL
            background = glass(0x55FFFFFF, 0x22FFFFFF, 0x99FFFFFF, 60f)
        }
        tvZoom = TextView(this).apply { text = "1.0x LIQUID AI"; setTextColor(Color.WHITE); textSize = 16f; typeface = Typeface.DEFAULT_BOLD }
        swAi = Switch(this).apply { text = "Deep AI "; isChecked = true; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD }
        top.addView(tvZoom, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        top.addView(swAi)
        camLay.addView(top, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP).apply { setMargins(28, 56, 28, 0) })

        val bot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(36, 32, 36, 40); gravity = Gravity.CENTER
            background = glass(0x48FFFFFF, 0x18FFFFFF, 0x99FFFFFF, 68f)
        }
        val sbZ = SeekBar(this).apply { max = 990 }
        val zRow = LinearLayout(this).apply { gravity = Gravity.CENTER }
        listOf(1f, 5f, 10f, 50f, 100f).forEach { z ->
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
            text = "CAPTURE • DEEP LIQUID AI"; setTextColor(Color.WHITE); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
            background = glass(0xEEFF7700, 0xBBFF2200, 0xCCFFFFFF, 60f)
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
            text = "✨ Deep AI 2x Ultra-Upscale (6-Pass)"
            setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            background = glass(0xDD00C6FF, 0xBB0062FF, 0xBBFFFFFF, 44f)
            setOnClickListener { upscale2x() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, 118).apply { bottomMargin = 12 })

        sbB = slider(card, "HDR Brightness", 200, 100)
        sbC = slider(card, "Liquid Contrast", 200, 100)
        sbS = slider(card, "AI Micro-Detail", 100, 35)
        sbD = slider(card, "Deep AI Denoise", 100, 25)

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

    private fun startCam() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            val p = f.get()
            val prev = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
            imgCap = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
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
        tvZoom.text = String.format("%.1fx LIQUID AI", curZoom)
    }

    private fun takePhoto() {
        val cap = imgCap ?: return
        prog.visibility = View.VISIBLE
        Toast.makeText(this, "Deep AI processing...", Toast.LENGTH_SHORT).show()
        cap.takePicture(ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(img: ImageProxy) {
                val rot = img.imageInfo.rotationDegrees
                val buf: ByteBuffer = img.planes[0].buffer
                val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                img.close()
                val ai = swAi.isChecked; val z = curZoom
                exec.execute {
                    var b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (rot != 0) b = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
                    b = crop(b, swZoom)
                    if (ai) {
                        val dn = if (z > 10f) 0.72f else if (z > 3f) 0.5f else 0.3f
                        b = denoise(b, dn)
                        b = denoise(b, dn * 0.7f)
                        b = color(b, 7f, 1.15f, 1.14f)
                        b = sharp(b, if (z > 12f) 0.85f else 0.65f)
                        b = sharp(b, 0.45f)
                    }
                    baseBmp = b; edBmp = b
                    runOnUiThread {
                        prog.visibility = View.GONE
                        sbB.progress = 100; sbC.progress = 100; sbS.progress = 35; sbD.progress = 25
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
        tvStatus.text = "LIQUID GLASS AI • ${b.width} x ${b.height} px"
    }

    private fun updateEd() {
        val src = baseBmp ?: return
        val b = (sbB.progress - 100).toFloat(); val c = sbC.progress / 100f
        val s = sbS.progress / 100f; val d = sbD.progress / 100f
        exec.execute {
            var r = color(src, b, c, 1.08f)
            if (d > 0.05f) r = denoise(r, d)
            if (s > 0.05f) r = sharp(r, s)
            edBmp = r
            runOnUiThread { ivEd.setImageBitmap(r); tvStatus.text = "LIQUID GLASS AI • ${r.width} x ${r.height} px" }
        }
    }

    private fun upscale2x() {
        val src = edBmp ?: return
        prog.visibility = View.VISIBLE
        Toast.makeText(this, "Running 6-Pass Deep AI Super-Res...", Toast.LENGTH_SHORT).show()
        exec.execute {
            val w = min(src.width * 2, 4096); val h = min(src.height * 2, 4096)
            var b = denoise(src, 0.6f)
            b = denoise(b, 0.45f)
            b = Bitmap.createScaledBitmap(b, (src.width + w) / 2, (src.height + h) / 2, true)
            b = sharp(b, 0.55f)
            b = Bitmap.createScaledBitmap(b, w, h, true)
            b = denoise(b, 0.35f)
            b = sharp(b, 0.85f)
            b = color(sharp(b, 0.4f), 4f, 1.08f, 1.12f)
            baseBmp = b; edBmp = b
            runOnUiThread { prog.visibility = View.GONE; showEd(); Toast.makeText(this, "6-Pass AI 2x Complete!", Toast.LENGTH_SHORT).show() }
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

    private fun denoise(s: Bitmap, str: Float): Bitmap {
        val w = s.width; val h = s.height; val p = IntArray(w * h); val out = p.clone()
        s.getPixels(p, 0, w, 0, 0, w, h)
        val thr = (20 + str * 48).toInt(); val bl = str.coerceIn(0.05f, 0.88f)
        for (y in 1 until h - 1) {
            val r = y * w
            for (x in 1 until w - 1) {
                val i = r + x; val c = p[i]; val cr = c shr 16 and 0xFF; val cg = c shr 8 and 0xFF; val cb = c and 0xFF
                var sr = cr; var sg = cg; var sb = cb; var cnt = 1
                val nb = intArrayOf(p[i - 1], p[i + 1], p[i - w], p[i + w], p[i - w - 1], p[i - w + 1], p[i + w - 1], p[i + w + 1])
                for (n in nb) {
                    val nr = n shr 16 and 0xFF; val ng = n shr 8 and 0xFF; val nbl = n and 0xFF
                    if (abs(cr - nr) + abs(cg - ng) + abs(cb - nbl) < thr) { sr += nr; sg += ng; sb += nbl; cnt++ }
                }
                if (cnt > 1) {
                    val fr = (cr * (1f - bl) + (sr / cnt) * bl).roundToInt().coerceIn(0, 255)
                    val fg = (cg * (1f - bl) + (sg / cnt) * bl).roundToInt().coerceIn(0, 255)
                    val fb = (cb * (1f - bl) + (sb / cnt) * bl).roundToInt().coerceIn(0, 255)
                    out[i] = (0xFF shl 24) or (fr shl 16) or (fg shl 8) or fb
                }
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(out, 0, w, 0, 0, w, h) }
    }

    private fun sharp(s: Bitmap, amt: Float): Bitmap {
        val w = s.width; val h = s.height; val p = IntArray(w * h); val out = p.clone()
        s.getPixels(p, 0, w, 0, 0, w, h)
        val cw = 1f + 4f * amt; val nw = -amt
        for (y in 1 until h - 1) {
            val r = y * w
            for (x in 1 until w - 1) {
                val i = r + x; val c = p[i]; val t = p[i - w]; val b = p[i + w]; val l = p[i - 1]; val rt = p[i + 1]
                val cr = c shr 16 and 0xFF; val tr = t shr 16 and 0xFF
                if (abs(cr - tr) < 3) continue
                val red = ((cr * cw) + (tr + (b shr 16 and 0xFF) + (l shr 16 and 0xFF) + (rt shr 16 and 0xFF)) * nw).roundToInt().coerceIn(0, 255)
                val grn = (((c shr 8 and 0xFF) * cw) + ((t shr 8 and 0xFF) + (b shr 8 and 0xFF) + (l shr 8 and 0xFF) + (rt shr 8 and 0xFF)) * nw).roundToInt().coerceIn(0, 255)
                val blu = (((c and 0xFF) * cw) + ((t and 0xFF) + (b and 0xFF) + (l and 0xFF) + (rt and 0xFF)) * nw).roundToInt().coerceIn(0, 255)
                out[i] = (0xFF shl 24) or (red shl 16) or (grn shl 8) or blu
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(out, 0, w, 0, 0, w, h) }
    }

    private fun saveImg() {
        val b = edBmp ?: return
        prog.visibility = View.VISIBLE
        exec.execute {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "XiaomiAI_${System.currentTimeMillis()}.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/XiaomiAICam")
            }
            contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)?.let {
                contentResolver.openOutputStream(it)?.use { s -> b.compress(Bitmap.CompressFormat.JPEG, 100, s) }
            }
            runOnUiThread { prog.visibility = View.GONE; Toast.makeText(this, "Saved to Gallery!", Toast.LENGTH_LONG).show() }
        }
    }
}
