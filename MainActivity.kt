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
    private var cameraentSwZoom
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
