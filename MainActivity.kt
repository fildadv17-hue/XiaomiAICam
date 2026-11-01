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

    private fun liquidGlassDrawable(
        topColor: Int = 0x55FFFFFF,
        bottomColor: Int = 0x22FFFFFF,
        strokeColor: Int = 0x88FFFFFF.toInt(),
        cornerRadius: Float = 54f
    ): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(topColor, bottomColor)
        ).apply {
            this.cornerRadius = cornerRadius
            setStroke(3, strokeColor)
        }
    }

    private fun liquidAccentButtonDrawable(
        colorStart: Int = 0xCCFF6900.toInt(),
        colorEnd: Int = 0x99FF2E00.toInt(),
        radius: Float = 60f
    ): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(colorStart, colorEnd)
        ).apply {
            cornerRadius = radius
            setStroke(3, 0xAAFFFFFF.toInt())
        }
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
            setPadding(44, 32, 44, 32)
            background = liquidGlassDrawable(0x44FFFFFF, 0x18FFFFFF, 0x77FFFFFF, 64f)
            gravity = Gravity.CENTER_VERTICAL
            elevation = 16f
        }
        tvZoom = TextView(this).apply {
            text = "1.0x AI OPTICS"
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
        }
        switchAi = Switch(this).apply {
            text = "AI Vision Pro "
            isChecked = true
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        topBar.addView(tvZoom, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        topBar.addView(switchAi, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        cameraLayout.addView(topBar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP).apply {
            setMargins(32, 64, 32, 0)
        })

        val bottomCamPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 36, 40, 44)
            background = liquidGlassDrawable(0x40FFFFFF, 0x15FFFFFF, 0x80FFFFFF.toInt(), 72f)
            gravity = Gravity.CENTER
            elevation = 24f
        }

        val seekZoom = SeekBar(this).apply { max = 990 }
        val zoomBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        listOf(1f, 5f, 10f, 50f, 100f).forEach { z ->
            val btn = Button(this).apply {
                text = "${z.toInt()}x"
                setTextColor(Color.WHITE)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                background = liquidGlassDrawable(0x38FFFFFF, 0x12FFFFFF, 0x66FFFFFF, 40f)
                setOnClickListener { seekZoom.progress = ((z - 1f) * 10f).toInt() }
            }
            zoomBtnRow.addView(btn, LinearLayout.LayoutParams(0, 105, 1f).apply {
                setMargins(8, 0, 8, 0)
            })
        }
        bottomCamPanel.addView(zoomBtnRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        bottomCamPanel.addView(seekZoom, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = 28
            bottomMargin = 28
        })

        seekZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                applyHybridZoom(1.0f + p / 10.0f)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        val btnCapture = Button(this).apply {
            text = "CAPTURE • LIQUID AI"
            background = liquidAccentButtonDrawable(0xDDFF7700.toInt(), 0xAAFF3300.toInt(), 64f)
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            elevation = 12f
            setOnClickListener { capturePhoto() }
        }
        bottomCamPanel.addView(btnCapture, LinearLayout.LayoutParams(MATCH_PARENT, 145))
        cameraLayout.addView(bottomCamPanel, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply {
            setMargins(28, 0, 28, 42)
        })

        editorLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0E14"))
            visibility = View.GONE
            setPadding(32, 56, 32, 36)
        }

        val editorTopCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
            background = liquidGlassDrawable(0x33FFFFFF, 0x11FFFFFF, 0x66FFFFFF, 48f)
        }
        tvResInfo = TextView(this).apply {
            setTextColor(Color.parseColor("#70F3FF"))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
        }
        editorTopCard.addView(tvResInfo)
        editorLayout.addView(editorTopCard, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = 16
        })

        ivEditorPreview = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        editorLayout.addView(ivEditorPreview, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply {
            bottomMargin = 16
        })

        val controlsGlassCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 28, 36, 28)
            background = liquidGlassDrawable(0x38FFFFFF, 0x14FFFFFF, 0x77FFFFFF, 60f)
        }

        val btnUpscale = Button(this).apply {
            text = "AI Super-Res 2x (Denoise + Micro-Detail)"
            background = liquidAccentButtonDrawable(0xCC00C6FF.toInt(), 0xAA0072FF.toInt(), 48f)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { runAiUpscaler() }
        }
        controlsGlassCard.addView(btnUpscale, LinearLayout.LayoutParams(MATCH_PARENT, 125).apply {
            bottomMargin = 16
        })

        seekBrightness = addSlider(controlsGlassCard, "HDR Brightness", 200, 100)
        seekContrast = addSlider(controlsGlassCard, "Dynamic Contrast", 200, 100)
        seekSharpen = addSlider(controlsGlassCard, "AI Micro-Edge Detail", 100, 30)
        seekDenoise = addSlider(controlsGlassCard, "AI Astro Denoise", 100, 20)

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val btnBack = Button(this).apply {
            text = "Retake"
            setTextColor(Color.WHITE)
            background = liquidGlassDrawable(0x33FFFFFF, 0x11FFFFFF, 0x55FFFFFF, 44f)
            setOnClickListener {
                editorLayout.visibility = View.GONE
                cameraLayout.visibility = View.VISIBLE
            }
        }
        val btnSave = Button(this).apply {
            text = "Save to Gallery"
            background = liquidAccentButtonDrawable(0xCC00E676.toInt(), 0x9900B0FF.toInt(), 44f)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { saveToGallery() }
        }
        actionRow.addView(btnBack, LinearLayout.LayoutParams(0, 125, 1f).apply { rightMargin = 12 })
        actionRow.addView(btnSave, LinearLayout.LayoutParams(0, 125, 1f).apply { leftMargin = 12 })
        controlsGlassCard.addView(actionRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = 16
        })

        editorLayout.addView(controlsGlassCard, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        progressOverlay = ProgressBar(this).apply { visibility = View.GONE }

        root.addView(cameraLayout, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(editorLayout, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(progressOverlay, FrameLayout.LayoutParams(150, 150, Gravity.CENTER))
        setContentView(root)
    }

    private fun addSlider(parent: LinearLayout, label: String, maxVal: Int, defVal: Int): SeekBar {
        val tv = TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
        }
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
                val zoomLevel = currentTotalZoom
                bgExecutor.execute {
                    var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (rot != 0) {
                        val m = Matrix().apply { postRotate(rot.toFloat()) }
                        bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                    }
                    bmp = cropZoom(bmp, currentSwZoom)
                    if (autoAi) {
                        bmp = runSmartAiPipeline(bmp, zoomLevel)
                    }
                    baseBitmap = bmp
                    editedBitmap = bmp

                    runOnUiThread {
                        progressOverlay.visibility = View.GONE
                        seekBrightness.progress = 100
                        seekContrast.progress = 100
                        seekSharpen.progress = 30
                        seekDenoise.progress = 20
                        showEditor()
                    }
                }
            }
            override fun onError(exc: ImageCaptureException) {
                progressOverlay.visibility = View.GONE
                Toast.makeText(this@MainActivity, "Capture error", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun runSmartAiPipeline(src: Bitmap, zoom: Float): Bitmap {
        var work = src
        val denoiseStrength = if (zoom > 10f) 0.65f else if (zoom > 3f) 0.40f else 0.20f
        work = edgePreservingDenoise(work, denoiseStrength)
        work = autoHdrToneMapping(work)
        val sharpStrength = if (zoom > 15f) 0.85f else 0.60f
        work = sharpenBitmap(work, sharpStrength)
        return work
    }

    private fun showEditor() {
        val bmp = editedBitmap ?: return
        cameraLayout.visibility = View.GONE
        editorLayout.visibility = View.VISIBLE
        ivEditorPreview.setImageBitmap(bmp)
        tvResInfo.text = "LIQUID AI ENGINE • ${bmp.width} x ${bmp.height} px"
    }

    private fun updateEditorPreview() {
        val src = baseBitmap ?: return
        val b = (seekBrightness.progress - 100).toFloat()
        val c = seekContrast.progress / 100f
        val s = seekSharpen.progress / 100f
        val d = seekDenoise.progress / 100f

        bgExecutor.execute {
            var res = adjustColors(src, b, c, 1.08f)
            if (d > 0.05f) res = edgePreservingDenoise(res, d)
            if (s > 0.05f) res = sharpenBitmap(res, s)
            editedBitmap = res
            runOnUiThread {
                ivEditorPreview.setImageBitmap(res)
                tvResInfo.text = "LIQUID AI ENGINE • ${res.width} x ${res.height} px"
            }
        }
    }

    private fun runAiUpscaler() {
        val src = editedBitmap ?: return
        progressOverlay.visibility = View.VISIBLE
        bgExecutor.execute {
            val w = min(src.width * 2, 4096)
            val h = min(src.height * 2, 4096)
            val cleaned = edgePreservingDenoise(src, 0.45f)
            val midW = (src.width + w) / 2
            val midH = (src.height + h) / 2
            val midBmp = Bitmap.createScaledBitmap(cleaned, midW, midH, true)
            val scaled = Bitmap.createScaledBitmap(midBmp, w, h, true)
            val sharp = sharpenBitmap(scaled, 0.82f)
            val finalBmp = adjustColors(sharp, 3f, 1.06f, 1.10f)
            baseBitmap = finalBmp
            editedBitmap = finalBmp
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
        val cropped = Bitmap.createBitmap(src, (src.width - cw) / 2, (src.height - ch) / 2, cw, ch)
        return Bitmap.createScaledBitmap(cropped, src.width, src.height, true)
    }

    private fun autoHdrToneMapping(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val pix = IntArray(w * h)
        src.getPixels(pix, 0, w, 0, 0, w, h)
        var sumLum = 0L
        val step = max(1, (w * h) / 10000)
        var samples = 0
        for (i in pix.indices step step) {
            val c = pix[i]
            val r = c shr 16 and 0xFF
            val g = c shr 8 and 0xFF
            val b = c and 0xFF
            sumLum += (r * 3 + g * 6 + b) / 10
            samples++
        }
        val avgLum = if (samples > 0) (sumLum / samples).toFloat() else 128f
        val autoBright = ((132f - avgLum) * 0.25f).coerceIn(-18f, 24f)
        return adjustColors(src, autoBright, 1.12f, 1.14f)
    }

    private fun adjustColors(src: Bitmap, brightness: Float, contrast: Float, saturation: Float = 1.0f): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val t = (-0.5f * contrast + 0.5f) * 255f + brightness
        val cm = ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, t,
            0f, contrast, 0f, 0f, t,
            0f, 0f, contrast, 0f, t,
            0f, 0f, 0f, 1f, 0f
        ))
        if (abs(saturation - 1.0f) > 0.01f) {
            val satMatrix = ColorMatrix().apply { setSaturation(saturation) }
            cm.postConcat(satMatrix)
        }
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    private fun edgePreservingDenoise(src: Bitmap, strength: Float): Bitmap {
        val w = src.width
        val h = src.height
        val pix = IntArray(w * h)
        val out = pix.clone()
        src.getPixels(pix, 0, w, 0, 0, w, h)
        val threshold = (18 + strength * 42).toInt()
        val blend = strength.coerceIn(0.05f, 0.85f)

        f
