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
    private lateinit var prog: ProgressBar; private lateinit var sbSplit: SeekBar; private lateinit var sbS: SeekBar
    private lateinit var sbC: SeekBar; private lateinit var sbD: SeekBar
    private var imgCap: ImageCapture? = null; private var cam: androidx.camera.core.Camera? = null
    private val exec = Executors.newSingleThreadExecutor()
    private var curZoom = 1f; private var maxHw = 20f; private var swZoom = 1f; private var leicaAuth = false
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
        tvZoom = TextView(this).apply { text = "14T LEICA • 1.0x"; setTextColor(Color.WHITE); textSize = 15f; typeface = Typeface.DEFAULT_BOLD }
        val btnL = Button(this).apply {
            text = "LEICA VIBRANT"; setTextColor(Color.WHITE); textSize = 11f; background = glass(0xCCDF0024, 0x99990018, 0xAAFFFFFF, 36f)
            setOnClickListener { leicaAuth = !leicaAuth; text = if (leicaAuth) "LEICA AUTHENTIC" else "LEICA VIBRANT" }
        }
        top.addView(tvZoom, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)); top.addView(btnL, LinearLayout.LayoutParams(WRAP_CONTENT, 84))
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
            text = "CAPTURE • BICUBIC HD AI"; setTextColor(Color.WHITE); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
            background = glass(0xEEFF6900, 0xBBCC2200, 0xCCFFFFFF, 60f); setOnClickListener { takePhoto() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, 136))
        camLay.addView(bot, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply { setMargins(24, 0, 24, 36) })

        edLay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#090C12")); visibility = View.GONE; setPadding(24, 44, 24, 28) }
        tvStatus = TextView(this).apply { setTextColor(Color.CYAN); textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setPadding(24, 16, 24, 16); background = glass(0x38FFFFFF, 0x14FFFFFF, 0x66FFFFFF, 40f) }
        edLay.addView(tvStatus, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 10 })
        ivEd = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        edLay.addView(ivEd, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { bottomMargin = 10 })

        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 20, 28, 20); background = glass(0x40FFFFFF, 0x16FFFFFF, 0x88FFFFFF, 52f) }
        card.addView(TextView(this).apply { text = "↔ VLEVO Bez AI | VPRAVO Bicubic-AI"; setTextColor(Color.YELLOW); textSize = 12f; typeface = Typeface.DEFAULT_BOLD })
        sbSplit = SeekBar(this).apply {
            max = 100; progress = 50
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) { renderSplit() }
                override fun onStartTrackingTouch(s: SeekBar?) {}; override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        card.addView(sbSplit, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 6 })
        card.addView(Button(this).apply {
            text = "✨ True-Bicubic 2x Super-Res"; setTextColor(Color.WHITE); textSize = 12f; background = glass(0xDD00C6FF, 0xBB0062FF, 0xBBFFFFFF, 40f); setOnClickListener { upscale2x() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, 108).apply { bottomMargin = 6 })

        sbS = slider(card, "Directional Edge Sharpness (Ostrost)", 100, 75)
        sbD = slider(card, "Surface Denoise (Vyhlazení ploch)", 100, 50)
        sbC = slider(card, "Leica Contrast", 200, 112)

        val act = LinearLayout(this)
        act.addView(Button(this).apply { text = "Retake"; setTextColor(Color.WHITE); background = glass(0x38FFFFFF, 0x14FFFFFF, 0x66FFFFFF, 40f); setOnClickListener { edLay.visibility = View.GONE; camLay.visibility = View.VISIBLE } }, LinearLayout.LayoutParams(0, 110, 1f).apply { rightMargin = 8 })
        act.addView(Button(this).apply { text = "Save AI"; setTextColor(Color.WHITE); background = glass(0xDD00E676, 0xAA00B0FF, 0xBBFFFFFF, 40f); setOnClickListener { saveImg() } }, LinearLayout.LayoutParams(0, 110, 1f).apply { leftMargin = 8 })
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
        pv.scaleX = swZoom; pv.scaleY = swZoom; tvZoom.text = String.format("14T %s • %.1fx", if (curZoom >= 2f) "TELE 50MP" else "IMX906", curZoom)
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
                    val sc = (1600f / max(cw, ch)).coerceIn(1f, 2.5f); val tw = (cw * sc).roundToInt(); val th = (ch * sc).roundToInt()
                    rawBmp = Bitmap.createScaledBitmap(crop, tw, th, false)
                    aiBmp = directionalAi(bicubicScale(directionalAi(crop, 0.55f, 0.5f, 1f, false), tw, th), 0.75f, 0.5f, 1.12f, leicaAuth)
                    runOnUiThread { prog.visibility = View.GONE; sbSplit.progress = 50; camLay.visibility = View.GONE; edLay.visibility = View.VISIBLE; renderSplit() }
                }
            }
            override fun onError(e: ImageCaptureException) { prog.visibility = View.GONE }
        })
    }

    private fun recalcAi() {
        val orig = rawBmp ?: return; prog.visibility = View.VISIBLE
        val s = sbS.progress / 100f; val d = sbD.progress / 100f; val c = sbC.progress / 100f
        exec.execute {
            val down = Bitmap.createScaledBitmap(orig, max(120, orig.width / 2), max(120, orig.height / 2), true)
            aiBmp = directionalAi(bicubicScale(directionalAi(down, s * 0.7f, d, 1f, false), orig.width, orig.height), s, d, c, leicaAuth)
            runOnUiThread { prog.visibility = View.GONE; renderSplit() }
        }
    }

    private fun renderSplit() {
        val orig = rawBmp ?: return; val ai = aiBmp ?: return
        val w = ai.width; val h = ai.height; val sx = (w * (sbSplit.progress / 100f)).roundToInt().coerceIn(0, w)
        val comp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val cv = Canvas(comp)
        cv.drawBitmap(ai, 0f, 0f, null)
        if (sx > 0) { cv.save(); cv.clipRect(0, 0, sx, h); cv.drawBitmap(orig, Rect(0, 0, orig.width, orig.height), Rect(0, 0, w, h), null); cv.restore() }
        if (sx in 2..(w - 2)) cv.drawLine(sx.toFloat(), 0f, sx.toFloat(), h.toFloat(), Paint().apply { color = Color.CYAN; strokeWidth = 6f })
        ivEd.setImageBitmap(comp); tvStatus.text = "14T BICUBIC HD • ${w}x${h} px (AI: ${100 - sbSplit.progress}%)"
    }

    private fun cubic(v0: Float, v1: Float, v2: Float, v3: Float, t: Float) =
        ((-0.5f * v0 + 1.5f * v1 - 1.5f * v2 + 0.5f * v3) * t + (v0 - 2.5f * v1 + 2f * v2 - 0.5f * v3)) * t * t + (-0.5f * v0 + 0.5f * v2) * t + v1

    private fun bicubicScale(src: Bitmap, dstW: Int, dstH: Int): Bitmap {
        val sw = src.width; val sh = src.height; val sp = IntArray(sw * sh); src.getPixels(sp, 0, sw, 0, 0, sw, sh)
        val dp = IntArray(dstW * dstH); val xR = sw.toFloat() / dstW; val yR = sh.toFloat() / dstH
        val rR = FloatArray(4); val gR = FloatArray(4); val bR = FloatArray(4)
        for (y in 0 until dstH) {
            val fy = (y + 0.5f) * yR - 0.5f; val iy = floor(fy).toInt(); val ty = fy - iy
            for (x in 0 until dstW) {
                val fx = (x + 0.5f) * xR - 0.5f; val ix = floor(fx).toInt(); val tx = fx - ix
                for (m in -1..2) {
                    val py = (iy + m).coerceIn(0, sh - 1) * sw
                    val p0 = sp[py + (ix - 1).coerceIn(0, sw - 1)]; val p1 = sp[py + ix.coerceIn(0, sw - 1)]
                    val p2 = sp[py + (ix + 1).coerceIn(0, sw - 1)]; val p3 = sp[py + (ix + 2).coerceIn(0, sw - 1)]
                    rR[m + 1] = cubic((p0 shr 16 and 0xFF).toFloat(), (p1 shr 16 and 0xFF).toFloat(), (p2 shr 16 and 0xFF).toFloat(), (p3 shr 16 and 0xFF).toFloat(), tx)
                    gR[m + 1] = cubic((p0 shr 8 and 0xFF).toFloat(), (p1 shr 8 and 0xFF).toFloat(), (p2 shr 8 and 0xFF).toFloat(), (p3 shr 8 and 0xFF).toFloat(), tx)
                    bR[m + 1] = cubic((p0 and 0xFF).toFloat(), (p1 and 0xFF).toFloat(), (p2 and 0xFF).toFloat(), (p3 and 0xFF).toFloat(), tx)
                }
                dp[y * dstW + x] = (0xFF shl 24) or (cubic(rR[0], rR[1], rR[2], rR[3], ty).roundToInt().coerceIn(0, 255) shl 16) or (cubic(gR[0], gR[1], gR[2], gR[3], ty).roundToInt().coerceIn(0, 255) shl 8) or cubic(bR[0], bR[1], bR[2], bR[3], ty).roundToInt().coerceIn(0, 255)
            }
        }
        return Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888).apply { setPixels(dp, 0, dstW, 0, 0, dstW, dstH) }
    }

    private fun directionalAi(src: Bitmap, sh: Float, dn: Float, co: Float, auth: Boolean): Bitmap {
        val w = src.width; val h = src.height; val p = IntArray(w * h); src.getPixels(p, 0, w, 0, 0, w, h); val out = p.clone()
        val lum = IntArray(w * h) { ((p[it] shr 16 and 0xFF) * 77 + (p[it] shr 8 and 0xFF) * 150 + (p[it] and 0xFF) * 29) shr 8 }
        val gain = sh * 1.6f; val fThr = (12 + dn * 28).toInt(); val fBl = (dn * 0.7f).coerceIn(0f, 0.75f)
        for (y in 1 until h - 1) {
            val r = y * w
            for (x in 1 until w - 1) {
                val i = r + x; val c = p[i]; val lC = lum[i]
                val lT = lum[i - w]; val lB = lum[i + w]; val lL = lum[i - 1]; val lR = lum[i + 1]
                val lTL = lum[i - w - 1]; val lTR = lum[i - w + 1]; val lBL = lum[i + w - 1]; val lBR = lum[i + w + 1]
                val gx = abs((lTR + 2 * lR + lBR) - (lTL + 2 * lL + lBL)); val gy = abs((lBL + 2 * lB + lBR) - (lTL + 2 * lT + lTR))
                val lo = min(min(lT, lB), min(lL, lR)); val hi = max(max(lT, lB), max(lL, lR))
                val avg = (lT + lB + lL + lR + lTL + lTR + lBL + lBR) shr 3
                val nL = if ((gx + gy) shr 2 > 16) {
                    val al = if (gx > gy) (lT + lB + lC * 2) shr 2 else (lL + lR + lC * 2) shr 2
                    (al + (lC - avg) * gain).roundToInt().coerceIn(lo, hi)
                } else if (abs(lC - avg) < fThr) (lC * (1f - fBl) + avg * fBl).roundToInt() else lC
                val d = nL - lC
                out[i] = (0xFF shl 24) or ((c shr 16 and 0xFF) + d).coerceIn(0, 255).shl(16) or ((c shr 8 and 0xFF) + d).coerceIn(0, 255).shl(8) or ((c and 0xFF) + d).coerceIn(0, 255)
            }
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(out, 0, w, 0, 0, w, h) }
        val t = (-0.5f * co + 0.5f) * 255f + (if (auth) -2f else 3f)
        val cm = ColorMatrix(floatArrayOf(co, 0f, 0f, 0f, t, 0f, co, 0f, 0f, t, 0f, 0f, co, 0f, t, 0f, 0f, 0f, 1f, 0f)).apply { postConcat(ColorMatrix().apply { setSaturation(if (auth) 0.96f else 1.12f) }) }
        val res = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(res).drawBitmap(bmp, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(cm) }); return res
    }

    private fun upscale2x() {
        val ai = aiBmp ?: return; val orig = rawBmp ?: return; prog.visibility = View.VISIBLE
        val s = sbS.progress / 100f; val d = sbD.progress / 100f; val c = sbC.progress / 100f
        exec.execute {
            val sc = min(1.7f, 2800f / max(ai.width, ai.height)); val tw = (ai.width * sc).roundToInt(); val th = (ai.height * sc).roundToInt()
            rawBmp = Bitmap.createScaledBitmap(orig, tw, th, false)
            aiBmp = directionalAi(bicubicScale(ai, tw, th), (s + 0.15f).coerceAtMost(1f), d, c, leicaAuth)
            runOnUiThread { prog.visibility = View.GONE; renderSplit() }
        }
    }

    private fun saveImg() {
        val b = aiBmp ?: return; prog.visibility = View.VISIBLE
        exec.execute {
            val cv = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, "14T_${System.currentTimeMillis()}.jpg"); put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg") }
            contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)?.let { contentResolver.openOutputStream(it)?.use { s -> b.compress(Bitmap.CompressFormat.JPEG, 100, s) } }
            runOnUiThread { prog.visibility = View.GONE; Toast.makeText(this, "Uloženo!", Toast.LENGTH_LONG).show() }
        }
    }
}
