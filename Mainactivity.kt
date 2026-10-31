package com.example.xiaomiaicam

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.*
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

    private fun buildCompleteUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        // 1. CAMERA SCREEN (100x ZOOM)
        cameraLayout = FrameLayout(this)
        val previewContainer = FrameLayout(this).apply { clipChildren = true }
        previewView = PreviewView(this)
        previewContainer.addView(previewView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        cameraLayout.addView(previewContainer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(32, 48, 32, 32)
            setBackgroundColor(0x66000000)
        }
        tvZoom = TextView(this).apply {
            text = "ZOOM: 1.0x"
            setTextColor(Color.YELLOW)
            textSize = 18f
        }
        switchAi = Switch(this).apply {
            text = "AI Enhance "
            isChecked = true
            setTextColor(Color.WHITE)
        }
        topBar.addView(tvZoom, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        topBar.addView(switchAi, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        cameraLayout.addView(topBar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))

        val bottomCamPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 48)
            setBackgroundColor(0xCC111111.toInt())
            gravity = Gravity.CENTER
        }

        val seekZoom = SeekBar(this).apply { max = 990 }
        val zoomBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        listOf(1f, 5f, 10f, 50f, 100f).forEach { z ->
            val btn = Button(this).apply {
                text = "${z.toInt()}x"
                setOnClickListener { seekZoom.progress = ((z - 1f) * 10f).toInt() }
            }
            zoomBtnRow.addView(btn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        bottomCamPanel.addView(zoomBtnRow)
        bottomCamPanel.addView(seekZoom, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 16; bottomMargin = 24 })

        seekZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                applyHybridZoom(1.0f + p / 10.0f)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        val btnCapture = Button(this).apply {
            text = "📸 CAPTURE (AI)"
            setBackgroundColor(Color.parseColor("#FF6900"))
            setTextColor(Color.WHITE)
            textSize = 16f
            setOnClickListener { capturePhoto() }
        }
        bottomCamPanel.addView(btnCapture, LinearLayout.LayoutParams(MATCH_PARENT, 140))
        cameraLayout.addView(bottomCamPanel, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))

        // 2. EDITOR & AI UPSCALER SCREEN
        editorLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            visibility = View.GONE
            setPadding(32, 32, 32, 32)
        }

        tvResInfo = TextView(this).apply {
            setTextColor(Color.CYAN)
            textSize = 14f
        }
        editorLayout.addView(tvResInfo)

        ivEditorPreview = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        editorLayout.addView(ivEditorPreview, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        val btnUpscale = Button(this).apply {
            text = "✨ AI Upscaler (2x Resolution + Sharpness)"
            setBackgroundColor(Color.parseColor("#FF6900"))
            setTextColor(Color.WHITE)
            setOnClickListener { runAiUpscaler() }
        }
        editorLayout.addView(btnUpscale, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        seekBrightness = addSlider(editorLayout, "Brightness", 200, 100)
        seekContrast = addSlider(editorLayout, "Contrast", 200, 100)
        seekSharpen = addSlider(editorLayout, "AI Edge Sharpen", 100, 0)

        val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnBack = Button(this).apply {
            text = "Retake"
            setOnClickListener {
                editorLayout.visibility = View.GONE
                cameraLayout.visibility = View.VISIBLE
            }
        }
        val btnSave = Button(this).apply {
            text = "💾 Save to Gallery"
            setBackgroundColor(Color.parseColor("#2E7D32"))
            setTextColor(Color.WHITE)
            setOnClickListener { saveToGallery() }
        }
        actionRow.addView(btnBack, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        actionRow.addView(btnSave, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        editorLayout.addView(actionRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        progressOverlay = ProgressBar(this).apply { visibility = View.GONE }

        root.addView(cameraLayout, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(editorLayout, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(progressOverlay, FrameLayout.LayoutParams(150, 150, Gravity.CENTER))
        setContentView(root)
    }

    private fun addSlider(parent: LinearLayout, label: String, maxVal: Int, defVal: Int): SeekBar {
        val tv = TextView(this).apply { text = label; setTextColor(Color.LTGRAY); textSize = 12f }
        val sb = SeekBar(this).apply {
            max = maxVal
            progress = defVal
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) updateEditorPreview()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        parent.addView(tv)
        parent.addView(sb)
        return sb
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
                camera?.cameraInfo?.zoomState?.observe(this) { state ->
                    maxHwZoom = state.maxZoomRatio.coerceAtLeast(1.0f)
                }
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
        previewView.scaleX = currentSwZoom
        previewView.scaleY = currentSwZoom
        tvZoom.text = String.format("ZOOM: %.1fx", currentTotalZoom)
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
                bgExecutor.execute {
                    var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (rot != 0) {
                        val m = Matrix().apply { postRotate(rot.toFloat()) }
                        bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                    }
                    bmp = cropZoom(bmp, currentSwZoom)
                    if (autoAi) {
                        bmp = sharpenBitmap(bmp, 0.55f)
                        bmp = adjustColors(bmp, 4f, 1.08f)
                    }
                    baseBitmap = bmp
                    editedBitmap = bmp

                    runOnUiThread {
                        progressOverlay.visibility = View.GONE
                        seekBrightness.progress = 100
                        seekContrast.progress = 100
                        seekSharpen.progress = 0
                        showEditor()
                    }
                }
            }
            override fun onError(exc: ImageCaptureException) {
                progressOverlay.visibility = View.GONE
