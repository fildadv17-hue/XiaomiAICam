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
    private var leicaAuthentic = false

    private var rawBmp: Bitmap? = null
    private var aiBmp: Bitmap? = null

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
            text = " HD AI "; isChecked = true; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        }
        top.addView(tvZoom, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        top.addView(btnLeica, LinearLayout.LayoutParams(WRAP_CONTENT, 84).apply { rightMargin = 12 })
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
            text = "CAPTURE • TRUE-DETAIL AI"; setTextColor(Color.WHITE); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
            background = glass(0xEEFF6900, 0xBBCC2200, 0xCCFFFFFF, 60f)
            setOnClickListener { takePhoto() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, 138))
        camLay.addView(bot, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply { setMargins(24, 0, 24, 36) })

        edLay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#090C12")); visibility = View.GONE; setPadding(24, 44, 24, 28)
        }
        tvStatus = TextView(this).apply {
            setTextColor(Color.parseColor("#70F3FF")); textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setPadding(24, 16, 24, 16)
            background = glass(0x38FFFFFF, 0x14FFFFFF, 0x66FFFFFF, 40f)
        }
        edLay.addView(tvStatus, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 10 })
        ivEd = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        edLay.addView(ivEd, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { bottomMargin = 10 })

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(28, 20, 28, 20)
            background = glass(0x40FFFFFF, 0x16FFFFFF, 0x88FFFFFF, 52f)
        }

        // Posuvník PŘED / PO (Split-Screen Compare)
        val tvSplit = TextView(this).apply {
            text = "↔ Posuvník porovnání: VLEVO Bez AI | VPRAVO S AI"
            setTextColor(Color.YELLOW); textSize = 12f; typeface = Typeface.DEFAULT_BOLD
        }
        card.addView(tvSplit)
        sbSplit = SeekBar(this).apply {
            max = 100; progress = 50
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) { renderSplitView() }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        card.addView(sbSplit, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 8 })

        card.addView(Button(this).apply {
            text = "✨ True-Detail 2x Upscale (Zachovat poměr stran)"
            setTextColor(Color.WHITE); textSize = 12f; typeface = Typeface.DEFAULT_BOLD
            background = glass(0xDD00C6FF, 0xBB0062FF, 0xBBFFFFFF, 40f)
            setOnClickListener { upscale2x() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, 110).apply { bottomMargin = 8 })

        sbS = slider(card, "AI Crisp Micro-Edges (Ostrost)", 100, 55)
        sbC = slider(card, "Local Clarity & Contrast", 200, 112)
        sbD = slider(card, "Smart Surface Denoise (Bez rozmazání)", 100, 25)

        val act = LinearLayout(this)
        act.addView(Button(this).apply {
            text = "Retake"; setTextColor(Color.WHITE); background = glass(0x38FFFFFF, 0x14FFFFFF, 0x66FFFFFF, 40f)
            setOnClickListener { edLay.visibility = View.GONE; camLay.visibility = View.VISIBLE }
        }, LinearLayout.LayoutParams(0, 112, 1f).apply { rightMargin = 10 })
        act.addView(Button(this).apply {
            text = "Save AI Photo"; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            background = glass(0xDD00E676, 0xAA00B0FF, 0xBBFFFFFF, 40f)
            setOnClickListener { saveImg() }
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
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { recalculateAi() }
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
                exec.execute {
                    var b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (rot != 0) b = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
                    b = cropAspectPreserved(b, swZoom)
                    rawBmp = b
                    aiBmp = runSobelCrispAi(b, 0.55f, 1.12f, 0.25f, leicaAuthentic)
                    runOnUiThread {
                        prog.visibility = View.GONE
                        sbSplit.progress = 50; sbS.progress = 55; sbC.progress = 112; sbD.progress = 25
                        camLay.visibility = View.GONE; edLay.visibility = View.VISIBLE
                        renderSplitView()
                    }
                }
            }
            override fun onError(e: ImageCaptureException) { prog.visibility = View.GONE }
        })
    }

    private fun recalculateAi() {
        val orig = rawBmp ?: return
        prog.visibility = View.VISIBLE
        val s = sbS.progress / 100f
        val c = sbC.progress / 100f
        val d = sbD.progress / 100f
        val auth = leicaAuthentic
        exec.execute {
            aiBmp = runSobelCrispAi(orig, s, c, d, auth)
            runOnUiThread {
                prog.visibility = View.GONE
                renderSplitView()
            }
        }
    }

    // Vykreslí vlevo originál (Bez AI), vpravo upravenou fotku (S AI) a posuvnou dělicí čáru
    private fun renderSplitView() {
        val orig = rawBmp ?: return
        val ai = aiBmp ?: return
        val w = ai.width; val h = ai.height
        val splitRatio = sbSplit.progress / 100f
        val splitX = (w * splitRatio).roundToInt().coerceIn(0, w)

        val comp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(comp)
        // Vpravo (nebo na celé ploše) vykreslíme AI verzi
        canvas.drawBitmap(ai, 0f, 0f, null)

        // Vlevo od posuvníku ořízneme a vykreslíme původní fotku BEZ AI
        if (splitX > 0) {
            canvas.save()
            canvas.clipRect(0, 0, splitX, h)
            val srcRect = Rect(0, 0, orig.width, orig.height)
            val dstRect = Rect(0, 0, w, h)
            canvas.drawBitmap(orig, srcRect, dstRect, null)
            canvas.restore()
        }

        // Vykreslení posuvné čáry a štítků BEZ AI / S AI
        if (splitX in 2..(w - 2)) {
            val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#00E5FF")
                strokeWidth = max(4f, w * 0.004f)
            }
            canvas.drawLine(splitX.toFloat(), 0f, splitX.toFloat(), h.toFloat(), linePaint)

            val txtPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = max(28f, w * 0.028f)
                typeface = Typeface.DEFAULT_BOLD
                setShadowLayer(8f, 0f, 2f, Color.BLACK)
            }
            if (splitX > w * 0.18f) canvas.drawText("◀ BEZ AI", 32f, h * 0.08f, txtPaint)
            if (splitX < w * 0.82f) canvas.drawText("S AI ▶", splitX + 28f, h * 0.08f, txtPaint)
        }

        ivEd.setImageBitmap(comp)
        val pct = (100 - sbSplit.progress)
        tvStatus.text = "14T TRUE-DETAIL • ${w}x${h} px (AI krytí: $pct%)"
    }

    // Pravý Pixel-Level Sobel Edge-Guided AI (nikdy nerozmazává fotku zmenšováním!)
    private fun runSobelCrispAi(src: Bitmap, sharpAmt: Float, contrast: Float, denoiseAmt: Float, authentic: Boolean): Bitmap {
        val w = src.width; val h = src.height
        val p = IntArray(w * h)
        src.getPixels(p, 0, w, 0, 0, w, h)
        val out = p.clone()

        // Rychlý převod jasu (Luma) pro přesnou detekci hran
        val luma = IntArray(w * h)
        for (i in p.indices) {
            val c = p[i]
            luma[i] = ((c shr 16 and 0xFF) * 77 + (c shr 8 and 0xFF) * 150 + (c and 0xFF) * 29) shr 8
        }

        val sharpGain = sharpAmt * 1.35f
        val denoiseThr = (14 + denoiseAmt * 38).toInt()
        val denoiseStr = (denoiseAmt * 0.65f).coerceIn(0f, 0.7f)

        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                val c = p[i]
                val r = c shr 16 and 0xFF; val g = c shr 8 and 0xFF; val b = c and 0xFF
                val lum = luma[i]

                // 3x3 okolí pro Sobelův gradient a detailní Unsharp Masku
                val lT = luma[i - w]; val lB = luma[i + w]; val lL = luma[i - 1]; val lR = luma[i + 1]
                val lTL = luma[i - w - 1]; val lTR = luma[i - w + 1]; val lBL = luma[i + w - 1]; val lBR = luma[i + w + 1]

                // Sobelův horizontální a vertikální gradient (pozná skutečnou hranu od šumu)
                val gx = (lTR + 2 * lR + lBR) - (lTL + 2 * lL + lBL)
                val gy = (lBL + 2 * lB + lBR) - (lTL + 2 * lT + lTR)
                val edgeMag = (abs(gx) + abs(gy)) shr 2

                val avgLum = (lT + lB + lL + lR + lTL + lTR + lBL + lBR) shr 3
                val detail = lum - avgLum

                var delta = 0
                if (edgeMag > 16) {
                    // Jsme na skutečné hraně nebo textuře -> čisté mikro-doostření bez rozmazání
                    val edgeWeight = ((edgeMag - 16) / 48f).coerceIn(0.2f, 1.0f)
                    delta = (detail * sharpGain * edgeWeight).roundToInt().coerceIn(-28, 28)
                } else if (denoiseAmt > 0.05f && abs(detail) < denoiseThr) {
                    // Jsme na zcela hladké ploše mimo hrany -> jemné vyhlazení šumu senzoru
                    delta = (-detail * denoiseStr).roundToInt()
                }

                val nr = (r + delta).coerceIn(0, 255)
                val ng = (g + delta).coerceIn(0, 255)
                val nb = (b + delta).coerceIn(0, 255)
                out[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }

        val crispBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(out, 0, w, 0, 0, w, h) }
        val sat = if (authentic) 0.95f else 1.12f
        val br = if (authentic) -2f else 3f
        return color(crispBmp, br, contrast, sat)
    }

    // 2x Upscale, který striktně zachovává poměr stran 4:3 (žádné deformování na čtverec 4096x4096!)
    private fun upscale2x() {
        val orig = rawBmp ?: return
        prog.visibility = View.VISIBLE
        Toast.makeText(this, "True-Detail 2x Upscaling...", Toast.LENGTH_SHORT).show()
        val s = sbS.progress / 100f; val c = sbC.progress / 100f; val d = sbD.progress / 100f; val auth = leicaAuthentic
        exec.execute {
            val scale = min(2.0f, 4096f / max(orig.width, orig.height))
            val targetW = (orig.width * scale).roundToInt()
            val targetH = (orig.height * scale).roundToInt()
            val upRaw = Bitmap.createScaledBitmap(orig, targetW, targetH, true)
            rawBmp = upRaw
            aiBmp = runSobelCrispAi(upRaw, (s + 0.15f).coerceAtMost(1.0f), c, d, auth)
            runOnUiThread {
                prog.visibility = View.GONE
                renderSplitView()
                Toast.makeText(this, "Upscale ${targetW}x${targetH} px hotov!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun cropAspectPreserved(s: Bitmap, f: Float): Bitmap {
        if (f <= 1.02f) return s
        val cw = max(64, (s.width / f).roundToInt())
        val ch = max(64, (s.height / f).roundToInt())
        val cropped = Bitmap.createBitmap(s, (s.width - cw) / 2, (s.height - ch) / 2, cw, ch)
        return Bitmap.createScaledBitmap(cropped, s.width, s.height, true)
