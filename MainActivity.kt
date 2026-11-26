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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors
import kotlin.math.roundToInt

enum class ReviewMode {
    WITH_AI,
    WITHOUT_AI,
    SPLIT_COMPARE
}

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

    var totalZoom by remember { mutableFloatStateOf(1f) }
    var hardwareMaxZoom by remember { mutableFloatStateOf(20f) }
    var selectedLeica by remember { mutableStateOf(LeicaProfile.LEICA_WARM) }
    var selectedQuality by remember { mutableStateOf(PhotoQuality.AI_ULTRA) }
    var aiEnhanceActive by remember { mutableStateOf(true) }
    var isProcessing by remember { mutableStateOf(false) }

    var capturedPair by remember { mutableStateOf<CapturedPhotoPair?>(null) }

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

    if (capturedPair != null) {
        RetakeReviewScreen(
            photoPair = capturedPair!!,
            initialAiDefault = aiEnhanceActive,
            zoomLevel = totalZoom,
            leicaProfile = selectedLeica,
            onRetake = { capturedPair = null },
            onSave = { bitmapToSave, label ->
                saveBitmapToGallery(context, bitmapToSave, selectedQuality.jpegQuality, label)
                Toast.makeText(context, "Saved $label (${selectedQuality.label})", Toast.LENGTH_SHORT).show()
                capturedPair = null
            }
        )
        return
    }

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
                                    try {
                                        val rawBitmap = image.toBitmapRotated()
                                        capturedPair = RealAiEngine.processCapturePair(
                                            source = rawBitmap,
                                            digitalMultiplier = digitalMultiplier,
                                            leicaProfile = selectedLeica,
                                            quality = selectedQuality
                                        )
                                    } finally {
                                        image.close()
                                        isProcessing = false
                                    }
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
    photoPair: CapturedPhotoPair,
    initialAiDefault: Boolean,
    zoomLevel: Float,
    leicaProfile: LeicaProfile,
    onRetake: () -> Unit,
    onSave: (Bitmap, String) -> Unit
) {
    var reviewMode by remember {
        mutableStateOf(if (initialAiDefault) ReviewMode.WITH_AI else ReviewMode.WITHOUT_AI)
    }
    var splitFraction by remember { mutableFloatStateOf(0.5f) }

    val withoutAiImage = remember(photoPair.withoutAi) { photoPair.withoutAi.asImageBitmap() }
    val withAiImage = remember(photoPair.withAi) { photoPair.withAi.asImageBitmap() }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        when (reviewMode) {
            ReviewMode.WITH_AI -> {
                Image(
                    bitmap = withAiImage,
                    contentDescription = "Photo With AI",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }

            ReviewMode.WITHOUT_AI -> {
                Image(
                    bitmap = withoutAiImage,
                    contentDescription = "Photo Without AI",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }

            ReviewMode.SPLIT_COMPARE -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures { _, dragAmount ->
                                val widthPx = size.width.toFloat().coerceAtLeast(1f)
                                splitFraction = (splitFraction + (dragAmount / widthPx)).coerceIn(0.1f, 0.9f)
                            }
                        }
                ) {
                    Image(
                        bitmap = withoutAiImage,
                        contentDescription = "Without AI Base",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                    Image(
                        bitmap = withAiImage,
                        contentDescription = "With AI Overlay",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .drawWithContent {
                                val splitX = size.width * splitFraction
                                clipRect(left = splitX) {
                                    this@drawWithContent.drawContent()
                                }
                                drawLine(
                                    color = Color(0xFF64D2FF),
                                    start = Offset(splitX, 0f),
                                    end = Offset(splitX, size.height),
                                    strokeWidth = 5f
                                )
                            }
                    )
                }
            }
        }

        LiquidGlassContainer(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(16.dp)
        ) {
            val modeTitle = when (reviewMode) {
                ReviewMode.WITH_AI -> "WITH AI • ${leicaProfile.label}"
                ReviewMode.WITHOUT_AI -> "WITHOUT AI • Raw Sensor"
                ReviewMode.SPLIT_COMPARE -> "SPLIT: Left Without AI | Right With AI"
            }
            Text(
                text = "${zoomLevel.roundToInt()}x  |  $modeTitle",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        // POST-PHOTO SIDEBAR: WITHOUT AI / WITH AI / 50-50 SPLIT
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp)
                .width(94.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.60f),
                            Color.White.copy(alpha = 0.14f)
                        )
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.50f),
                            Color.White.copy(alpha = 0.15f)
                        )
                    ),
                    shape = RoundedCornerShape(24.dp)
                )
                .padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "AI SIDEBAR",
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )

            SidebarPreviewCard(
                title = "Without AI",
                thumbnail = withoutAiImage,
                isSelected = reviewMode == ReviewMode.WITHOUT_AI,
                accentColor = Color(0xFFFFB84D),
                onClick = { reviewMode = ReviewMode.WITHOUT_AI }
            )

            SidebarPreviewCard(
                title = "With AI",
                thumbnail = withAiImage,
                isSelected = reviewMode == ReviewMode.WITH_AI,
                accentColor = Color(0xFF64D2FF),
                onClick = { reviewMode = ReviewMode.WITH_AI }
            )

            LiquidGlassButton(
                text = "50/50",
                isActive = reviewMode == ReviewMode.SPLIT_COMPARE,
                accentColor = Color(0xFF64D2FF),
                onClick = { reviewMode = ReviewMode.SPLIT_COMPARE }
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

            val savingWithoutAi = reviewMode == ReviewMode.WITHOUT_AI
            LiquidGlassButton(
                text = if (savingWithoutAi) "✓ Save (Without AI)" else "✓ Save (With AI)",
                isActive = true,
                accentColor = Color(0xFF32D74B),
                onClick = {
                    if (savingWithoutAi) {
                        onSave(photoPair.withoutAi, "WithoutAI")
                    } else {
                        onSave(photoPair.withAi, "WithAI")
                    }
                }
            )
        }
    }
}

@Composable
fun SidebarPreviewCard(
    title: String,
    thumbnail: ImageBitmap,
    isSelected: Boolean,
    accentColor: Color,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (isSelected) accentColor.copy(alpha = 0.22f)
                else Color.White.
