package com.example.xiaomiaicam

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var cameraLayout: FrameLayout
    private lateinit var editorLayout: LinearLayout
    private lateinit var ivEditorPreview: ImageView
    private lateinit var tvZoom: TextView
    private lateinit var tvResInfo: TextView
    private lateinit var progressOverlay: ProgressBar
    private lateinit var switchAi: Switch

    private lateinit var seekBrightness: SeekBar
    private lateinit var seekContrast: SeekBar
    private lateinit var seekSharpen: SeekBar
    private lateinit var seekDenoise: SeekBar

    private var imageCapture: ImageCapture? = null
    private var camera: androidx.camera.core.Camera? = null
    private val bgExecutor = Executors.newSingleThreadExecutor()

    private var currentTotalZoom = 1.0f
    private var maxHwZoom = 10.0f
    private var currentSwZoom = 1.0f

    private var baseBitmap: Bitmap? = null
    private var editedBitmap: Bitmap? = null

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else Toast.makeText(this, "Camera permission required!", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildCompleteUi()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun glassBg(c1: Int, c2: Int, stroke: Int, rad: Float) =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c1, c2)).apply {
            cornerRadius = rad
            setStroke(3, stroke)
        }

    private fun buildCompleteUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        cameraLayout = FrameLayout(this)
        val previewContainer = FrameLayout(this).apply { clipChildren = true }
        previewView = PreviewView(this)
        previewContainer.addView(previewView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        cameraLayout.addView(previewContainer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(40, 28, 40, 28)
            background = glassBg(0x44FFFFFF, 0x18FFFFFF, 0x77FFFFFF, 60f)
            gravity = Gravity.CENTER_VERTICAL
        }
        tvZoom = TextView(this).apply {
            text = "1.0x AI OPTICS"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
        }
        switchAi = Switch(this).apply {
            text = "Liquid AI "
            isChecked = true
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        topBar.addView(tvZoom, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        topBar.addView(switchAi, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        cameraLayout.addView(topBar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP).apply {
            setMargins(28, 56, 28, 0)
        })

        val bottomCamPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 32, 36, 40)
            background = glassBg(0x40FFFFFF, 0x15FFFFFF, 0x80FFFFFF.toInt(), 68f)
            gravity = Gravity.CENTER
        }

        val seekZoom = SeekBar(this).apply { max = 990 }
        val zoomBtnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        listOf(1f, 5f, 10f, 50f, 100f).forEach { z ->
            val btn = Button(this).apply {
                text = "${z.toInt()}x"
                setTextColor(Color.WHITE)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                background = glassBg(0x38FFFFFF, 0x12FFFFFF, 0x66FFFFFF, 36f)
                setOnClickListener { seekZoom.progress = ((z - 1f) * 10f).toInt() }
            }
            zoomBtnRow.addView(btn, LinearLayout.LayoutParams(0, 100, 1f).apply { setMargins(6, 0, 6, 0) })
        }
        bottomCamPanel.addView(zoomBtnRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        bottomCamPanel.addView(seekZoom, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 24; bottomMargin = 24 })

        seekZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { applyHybridZoom(1.0f + p / 10.0f) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        val btnCapture = Button(this).apply {
            text = "CAPTURE • LIQUID AI"
            background = glassBg(0xDDFF7700.toInt(), 0xAAFF3300.toInt(), 0xAAFFFFFF.toInt(), 60f)
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { capturePhoto() }
        }
        bottomCamPanel.addView(btnCapture, LinearLayout.LayoutParams(MATCH_PARENT, 140))
        cameraLayout.addView(bottomCamPanel, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply {
            setMargins(24, 0, 24, 36)
        })

        editorLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0E14"))
            visibility = View.GONE
            setPadding(28, 48, 28, 32)
        }

        tvResInfo = TextView(this).apply {
            setTextColor(Color.parseColor("#70F3FF"))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(24, 16, 24, 16)
            background = glassBg(0x33FFFFFF, 0x11FFFFFF, 0x55FFFFFF, 40f)
        }
        editorLayout.addView(tvResInfo, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 12 })

        ivEditorPreview = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        editorLayout.addView(ivEditorPreview, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { bottomMargin = 12 })

        val controlsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
            background = glassBg(0x38FFFFFF, 0x14FFFFFF, 0x77FFFFFF, 56f)
        }

        val btnUpscale = Button(this).apply {
            text = "AI Super-Res 2x (Denoise + Detail)"
            background = glassBg(0xCC00C6FF.toInt(), 0xAA0072FF.toInt(), 0xAAFFFFFF.toInt(), 44f)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { runAiUpscaler() }
        }
        controlsCard.addView(btnUpscale, LinearLayout.LayoutParams(MATCH_PARENT, 120).apply { bottomMargin = 12 })

        seekBrightness = addSlider(controlsCard, "HDR Brightness", 200, 100)
        seekContrast = addSlider(controlsCard, "Dynamic Contrast", 200, 100)
        seekSharpen = addSlider(controlsCard, "AI Edge Detail", 100, 30)
        seekDenoise = addSlider(controlsCard, "AI Astro Denoise", 100, 20)

        val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnBack = Button(this).apply {
            text = "Retake"
            setTextColor(Color.WHITE)
            background = glassBg(0x33FFFFFF, 0x11FFFFFF, 0x55FFFFFF, 40f)
            setOnClickListener { editorLayout.visibility = View.GONE; cameraLayout.visibility = View.VISIBLE }
        }
        val btnSave = Button(this).apply {
            text = "Save to Gallery"
            background = glassBg(0xCC00E676.toInt(), 0x9900B0FF.toInt(), 0xAAFFFFFF.toInt(), 40f)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { saveToGallery() }
        }
        actionRow.addView(btnBack, LinearLayout.LayoutParams(0, 120, 1f).apply { rightMargin = 10 })
        actionRow.addView(btnSave, LinearLayout.LayoutParams(0, 120, 1f).apply { leftMargin = 10 })
        controlsCard.addView(actionRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 12 })
        editorLayout.addView(controlsCard, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        progressOverlay = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(cameraLayout, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(editorLayout, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(progressOverlay, FrameLayout.LayoutParams(150, 150, Gravity.CENTER))
        setContentView(root)
    }

    private fun addSlider(parent: LinearLayout, label: String, maxVal: Int, defVal: Int): SeekBar {
        parent.addView(TextView(this).apply { text = label; setTextColor(Color.WHITE); textSize = 12f })
        return SeekBar(this).apply {
            max = maxVal; progress = defVal
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) updateEditorPreview() }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            parent.addView(this)
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                camera?.cameraInfo?.zoomState?.observe(this) { maxHwZoom = it.maxZoomRatio.coerceAtLeast(1.0f) }
            } catch (e: Exception) {
                Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun applyHybridZoom(targetZoom: Float) {
        currentTotalZoom = targetZoom.coerceIn(1.0f, 100.0f)
        val hw = min(currentTotalZoom, maxHwZoom)
        camera?.cameraControl?.setZoomRatio(hw)
        currentSwZoom = (currentTotalZoom / hw).coerceAtLeast(1.0f)
        previewView.scaleX = currentSwZoom; previewView.scaleY = currentSwZoom
        tvZoom.text = String.format("%.1fx AI OPTICS", currentTotalZoom)
    }

    private fun capturePhoto() {
        val cap = imageCapture ?: return
        progressOverlay.visibility = View.VISIBLE
        cap.takePicture(ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val rot = image.imageInfo.rotationDegrees
                val buf: ByteBuffer = image.planes[0].buffer
                val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                image.close()
                val autoAi = switchAi.isChecked
                val z = currentTotalZoom
                bgExecutor.execute {
                    var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (rot != 0) {
                        val m = Matrix().apply { postRotate(rot.toFloat()) }
                        bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                    }
                    bmp = cropZoom(bmp, currentSwZoom)
                    if (autoAi) {
                        bmp = edgeDenoise(bmp, if (z > 10f) 0.65f else if (z > 3f) 0.4f else 0.2f)
                        bmp = adjustColors(bmp, 6f, 1.12f, 1.12f)
                        bmp = sharpen(bmp, if (z > 15f) 0.85f else 0.6f)
                    }
                    baseBitmap = bmp; editedBitmap = bmp
                    runOnUiThread {
                        progressOverlay.visibility = View.GONE
                        seekBrightness.progress = 100; seekContrast.progress = 100
                        seekSharpen.progress = 30; seekDenoise.progress = 20
                        showEditor()
                    }
                }
            }
            override fun onError(exc: ImageCaptureException) {
                progressOverlay.visibility = View.GONE
            }
        })
    }

    private fun showEditor() {
        val bmp = editedBitmap ?: return
        cameraLayout.visibility = View.GONE; editorLayout.visibility = View.VISIBLE
        ivEditorPreview.setImageBitmap(bmp)
        tvResInfo.text = "LIQUID AI • ${bmp.width} x ${bmp.height} px"
    }

    private fun updateEditorPreview() {
        val src = baseBitmap ?: return
        val b = (seekBrightness.progress - 100).toFloat()
        val c = seekContrast.progress / 100f
        val s = seekSharpen.progress / 100f
        val d = seekDenoise.progress / 100f
        bgExecutor.execute {
            var res = adjustColors(src, b, c, 1.08f)
            if (d > 0.05f) res = edgeDenoise(res, d)
            if (s > 0.05f) res = sharpen(res, s)
            editedBitmap = res
            runOnUiThread {
                ivEditorPreview.setImageBitmap(res)
                tvResInfo.text = "LIQUID AI • ${res.width} x ${res.height} px"
            }
        }
    }

    private fun runAiUpscaler() {
        val src = editedBitmap ?: return
        progressOverlay.visibility = View.VISIBLE
        bgExecutor.execute {
            val w = min(src.width * 2, 4096); val h = min(src.height * 2, 4096)
            val clean = edgeDenoise(src, 0.45f)
            val mid = Bitmap.createScaledBitmap(clean, (src.width + w) / 2, (src.height + h) / 2, true)
            val scaled = Bitmap.createScaledBitmap(mid, w, h, true)
            val finalBmp = adjustColors(sharpen(scaled, 0.82f), 3f, 1.06f, 1.1f)
            baseBitmap = finalBmp; editedBitmap = finalBmp
            runOnUiThread {
                progressOverlay.visibility = View.GONE
                showEditor()
                Toast.makeText(this, "AI Super-Res 2x Complete!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun cropZoom(src: Bitmap, factor: Float): Bitmap {
        if (factor <= 1.02f) return src
        val cw = max(64, (src.width / factor).roundToInt())
        val ch = max(64, (src.height / factor).roundToInt())
        val crop = Bitmap.createBitmap(src, (src.width - cw) / 2, (src.height - ch) / 2, cw, ch)
        return Bitmap.createScaledBitmap(crop, src.width, src.height, true)
    }

    private fun adjustColors(src: Bitmap, b: Float, c: Float, sat: Float): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val t = (-0.5f * c + 0.5f) * 255f + b
        val cm = ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f))
        cm.postConcat(ColorMatrix().apply { setSaturation(sat) })
        Canvas(out).drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(cm) })
        return out
    }

    private fun edgeDenoise(src: Bitmap, str: Float): Bitmap {
        val w = src.width; val h = src.height
        val pix = IntArray(w * h); val out = pix.clone()
        src.getPixels(pix, 0, w, 0, 0, w, h)
        val thr = (18 + str * 42).toInt(); val bl = str.coerceIn(0.05f, 0.85f)
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x; val c = pix[i]
                val cr = c shr 16 and 0xFF; val cg = c shr 8 and 0xFF; val cb = c and 0xFF
                var sr = cr; var sg = cg; var sb = cb; var cnt = 1
                val nb = intArrayOf(pix[i - 1], pix[i + 1], pix[i - w], pix[i + w])
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

    private fun sharpen(src: Bitmap, amt: Float): Bitmap {
        val w = src.width; val h = src.height
        val pix = IntArray(w * h); val out = pix.clone()
        src.getPixels(pix, 0, w, 0, 0, w, h)
        val cw = 1f + 4f * amt; val nw = -amt
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x; val c = pix[i]; val t = pix[i - w]; val b = pix[i + w]; val l = pix[i - 1]; val r = pix[i + 1]
                val cr = c shr 16 and 0xFF; val tr = t shr 16 and 0xFF
                if (abs(cr - tr) < 4) continue
                val red = ((cr * cw) + (tr + (b shr 16 and 0xFF) + (l shr 16 and 0xFF) + (r shr 16 and 0xFF)) * nw).roundToInt().coerceIn(0, 255)
                val grn = (((c shr 8 and 0xFF) * cw) + ((t shr 8 and 0xFF) + (b shr 8 and 0xFF) + (l shr 8 and 0xFF) + (r shr 8 and 0xFF)) * nw).roundToInt().coerceIn(0, 255)
                val blu = (((c and 0xFF) * cw) + ((t and 0xFF) + (b and 0xFF) + (l and 0xFF) + (r and 0xFF)) * nw).roundToInt().coerceIn(0, 255)
                out[i] = (0xFF shl 24) or (red shl 16) or (grn shl 8) or blu
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(out, 0, w, 0, 0, w, h) }
    }

    private fun saveToGallery() {
        val bmp = editedBitmap ?: return
        progressOverlay.visibility = View.VISIBLE
        bgExecutor.execute {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "XiaomiAI_${System.currentTimeMillis()}.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/XiaomiAICam")
                }
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
            uri?.let { contentResolver.openOutputStream(it)?.use { s -> bmp.compress(Bitmap.Compr
