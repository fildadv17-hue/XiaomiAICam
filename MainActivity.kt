package com.fildadv.xiaomiaicam

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                XiaomiAiCamScreen()
            }
        }
    }
}

@Composable
fun XiaomiAiCamScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    if (!hasPermission) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            LiquidGlassButton(text = "Enable Camera Permission", isActive = true) {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
        return
    }

    // Camera & AI States
    var totalZoom by remember { mutableFloatStateOf(1f) } // 1x to 250x
    var hardwareMaxZoom by remember { mutableFloatStateOf(20f) }
    var selectedLeica by remember { mutableStateOf(LeicaProfile.LEICA_WARM) }
    var selectedQuality by remember { mutableStateOf(PhotoQuality.AI_ULTRA) }
    var aiEnhanceActive by remember { mutableStateOf(true) }
    var isProcessing by remember { mutableStateOf(false) }

    // Retake State
    var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }

    var cameraControl by remember { mutableStateOf<CameraControl?>(null) }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
    }

    val (hwZoom, digitalMultiplier) = RealAiEngine.calculateZoomSplit(totalZoom, hardwareMaxZoom)

    LaunchedEffect(hwZoom, cameraControl) {
        cameraControl?.setZoomRatio(hwZoom)
    }

    // RETAKE REVIEW SCREEN
    if (capturedBitmap != null) {
        RetakeReviewScreen(
            bitmap = capturedBitmap!!,
            zoomLevel = totalZoom,
            leicaProfile = selectedLeica,
            quality = selectedQuality,
            onRetake = { capturedBitmap = null },
            onSave = {
                saveBitmapToGallery(context, capturedBitmap!!, selectedQuality.jpegQuality)
                Toast.makeText(context, "Saved (${selectedQuality.label})", Toast.LENGTH_SHORT).show()
                capturedBitmap = null
            }
        )
        return
    }

    // LIVE CAMERA VIEWFINDER
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = digitalMultiplier
                    scaleY = digitalMultiplier
                },
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    try {
                        cameraProvider.unbindAll()
                        val camera = cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageCapture
                        )
                        cameraControl = camera.cameraControl
                        hardwareMaxZoom = camera.cameraInfo.zoomState.value?.maxZoomRatio ?: 20f
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            }
        )

        // TOP LIQUID GLASS BAR: Quality, AI Toggle & Leica Settings
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                LiquidGlassButton(
                    text = "Quality: ${selectedQuality.label}",
                    isActive = true,
                    onClick = {
                        val values = PhotoQuality.entries
                        selectedQuality = values[(selectedQuality.ordinal + 1) % values.size]
                    }
                )

                LiquidGlassButton(
                    text = if (aiEnhanceActive) "AI Liquid: ON" else "AI Liquid: OFF",
                    isActive = aiEnhanceActive,
                    accentColor = Color(0xFF64D2FF),
                    onClick = { aiEnhanceActive = !aiEnhanceActive }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // LEICA SETTINGS: Warm / Cold / Vibrant / Natural
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LeicaProfile.entries.forEach { profile ->
                    LiquidGlassButton(
                        text = profile.label,
                        isActive = selectedLeica == profile,
                        accentColor = when (profile) {
                            LeicaProfile.LEICA_WARM -> Color(0xFFFF9E44)
                            LeicaProfile.LEICA_COLD -> Color(0xFF56CCF2)
                            LeicaProfile.LEICA_VIBRANT -> Color(0xFFFF4B4B)
                            LeicaProfile.LEICA_NATURAL -> Color(0xFFE0E0E0)
                        },
                        onClick = { selectedLeica = profile }
                    )
                }
            }
        }

        // BOTTOM LIQUID GLASS CONTROLS: 250x Zoom + Shutter
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            LiquidGlassContainer(modifier = Modifier.padding(bottom = 8.dp)) {
                Text(
                    text = "${totalZoom.roundToInt()}x  ${if (totalZoom > hardwareMaxZoom) "• AI 250x ENGINE" else "• OPTICAL/HYBRID"}",
                    color = if (totalZoom >= 100f) Color(0xFFFF5252) else Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }

            // Zoom Preset Buttons up to 250x
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                listOf(1f, 5f, 10f, 50f, 100f, 250f).forEach { preset ->
                    LiquidGlassButton(
                        text = "${preset.toInt()}x",
                        isActive = totalZoom.roundToInt() == preset.toInt(),
                        accentColor = Color(0xFFFF3B30),
                        onClick = { totalZoom = preset }
                    )
                }
            }

            Slider(
                value = totalZoom,
                onValueChange = { totalZoom = it },
                valueRange = 1f..250f,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color(0xFFFF3B30),
                    inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                ),
                modifier = Modifier.padding(vertical = 6.dp)
            )

            // Liquid Glass Shutter Button
            Box(
                modifier = Modifier
                    .size(78.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = 0.35f),
                                Color.White.copy(alpha = 0.12f)
                            )
                        )
                    )
                    .border(2.dp, Color.White.copy(alpha = 0.85f), CircleShape)
                    .clickable(enabled = !isProcessing) {
                        isProcessing = true
                        imageCapture.takePicture(
                            cameraExecutor,
                            object : ImageCapture.OnImageCapturedCallback() {
                                override fun onCaptureSuccess(image: ImageProxy) {
                                    val rawBitmap = image.toBitmapRotated()
                                    image.close()
                                    val processed = RealAiEngine.processCapturedImage(
                                        source = rawBitmap,
                                        digitalMultiplier = digitalMultiplier,
                                        leicaProfile = selectedLeica,
                                        quality = selectedQuality,
                                        aiLiquidEnhanceEnabled = aiEnhanceActive
                                    )
                                    capturedBitmap = processed
                                    isProcessing = false
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    isProcessing = false
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(if (isProcessing) Color.Gray else Color.White)
                )
            }
        }
    }
}

@Composable
fun RetakeReviewScreen(
    bitmap: Bitmap,
    zoomLevel: Float,
    leicaProfile: LeicaProfile,
    quality: PhotoQuality,
    onRetake: () -> Unit,
    onSave: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageBitmap(bitmap)
                }
            }
        )

        LiquidGlassContainer(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(16.dp)
        ) {
            Text(
                text = "${zoomLevel.roundToInt()}x  |  ${leicaProfile.label}  |  ${quality.label}",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .fillMaxWidth()
                .padding(24.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            LiquidGlassButton(
                text = "↺ Retake",
                isActive = true,
                accentColor = Color(0xFFFF453A),
                onClick = onRetake
            )
            LiquidGlassButton(
                text = "✓ Save Photo",
                isActive = true,
                accentColor = Color(0xFF32D74B),
                onClick = onSave
            )
        }
    }
}

@Composable
fun LiquidGlassContainer(
    modifier: Modifier = Modifier,
    borderColor: Color = Color.White.copy(alpha = 0.45f),
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.25f),
                        Color.White.copy(alpha = 0.08f)
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        borderColor,
                        Color.White.copy(alpha = 0.12f)
                    )
                ),
                shape = RoundedCornerShape(50)
            ),
        content = content
    )
}

@Composable
fun LiquidGlassButton(
    text: String,
    isActive: Boolean = false,
    accentColor: Color = Color.White,
    onClick: () -> Unit
) {
    LiquidGlassContainer(
        borderColor = if (isActive) accentColor else Color.White.copy(alpha = 0.35f),
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            text = text,
            color = if (isActive) accentColor else Color.White.copy(alpha = 0.9f),
            fontSize = 12.sp,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

fun ImageProxy.toBitmapRotated(): Bitmap {
    val bitmap = toBitmap()
    val matrix = Matrix().apply { postRotate(imageInfo.rotationDegrees.toFloat()) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

fun saveBitmapToGallery(context: Context, bitmap: Bitmap, quality: Int) {
    val filename = "XiaomiAICam_${System.currentTimeMillis()}.jpg"
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, filename)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/XiaomiAICam")
        }
    }
    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    uri?.let {
        context.contentResolver.openOutputStream(it)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
    }
}
