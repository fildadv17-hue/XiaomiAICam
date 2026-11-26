package com.fildadv.xiaomiaicam

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
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

enum class ReviewMode { WITH_AI, WITHOUT_AI, SPLIT_COMPARE }

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
            LiquidGlassButton("Enable Camera Permission", isActive = true) {
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
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
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
            onSave = { bmp, label ->
                saveBitmapToGallery(context, bmp, selectedQuality.jpegQuality, label)
                Toast.makeText(context, "Saved $label (${selectedQuality.label})", Toast.LENGTH_SHORT).show()
                capturedPair = null
            }
        )
        return
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = digitalMultiplier
                scaleY = digitalMultiplier
            },
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val future = ProcessCameraProvider.getInstance(ctx)
                future.addListener({
                    val provider = future.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    try {
                        provider.unbindAll()
                        val cam = provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageCapture
                        )
                        cameraControl = cam.cameraControl
                        hardwareMaxZoom = cam.cameraInfo.zoomState.value?.maxZoomRatio ?: 20f
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            }
        )

        Column(
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                LiquidGlassButton("Quality: ${selectedQuality.label}", isActive = true) {
                    val vals = PhotoQuality.entries
                    selectedQuality = vals[(selectedQuality.ordinal + 1) % vals.size]
                }
                LiquidGlassButton(
                    text = if (aiEnhanceActive) "AI Liquid: ON" else "AI Liquid: OFF",
                    isActive = aiEnhanceActive,
                    accentColor = Color(0xFF64D2FF)
                ) { aiEnhanceActive = !aiEnhanceActive }
            }

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LeicaProfile.entries.forEach { profile ->
                    val accent = when (profile) {
                        LeicaProfile.LEICA_WARM -> Color(0xFFFF9E44)
                        LeicaProfile.LEICA_COLD -> Color(0xFF56CCF2)
                        LeicaProfile.LEICA_VIBRANT -> Color(0xFFFF4B4B)
                        LeicaProfile.LEICA_NATURAL -> Color(0xFFE0E0E0)
                    }
                    LiquidGlassButton(profile.label, selectedLeica == profile, accent) {
                        selectedLeica = profile
                    }
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
            LiquidGlassContainer(Modifier.padding(bottom = 8.dp)) {
                val modeTag = if (totalZoom > hardwareMaxZoom) "AI 250x ENGINE" else "OPTICAL/HYBRID"
                Text(
                    text = "${totalZoom.roundToInt()}x  •  $modeTag",
                    color = if (totalZoom >= 100f) Color(0xFFFF5252) else Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }

            Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                listOf(1f, 5f, 10f, 50f, 100f, 250f).forEach { preset ->
                    LiquidGlassButton(
                        text = "${preset.toInt()}x",
                        isActive = totalZoom.roundToInt() == preset.toInt(),
                        accentColor = Color(0xFFFF3B30)
                    ) { totalZoom = preset }
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
                            listOf(Color.White.copy(alpha = 0.35f), Color.White.copy(alpha = 0.12f))
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
                                        val raw = image.toBitmapRotated()
                                        capturedPair = RealAiEngine.processCapturePair(
                                            source = raw,
                                            digitalMultiplier = digitalMultiplier,
                                            leicaProfile = selectedLeica,
                                            quality = selectedQuality
                                        )
                                    } finally {
                                        image.close()
                                        isProcessing = false
                                    }
                                }

                                override fun onError(exc: ImageCaptureException) {
                                    isProcessing = false
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
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

    val withoutAiImg = remember(photoPair.withoutAi) { photoPair.withoutAi.asImageBitmap() }
    val withAiImg = remember(photoPair.withAi) { photoPair.withAi.asImageBitmap() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (reviewMode) {
            ReviewMode.WITH_AI -> Image(withAiImg, "With AI", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            ReviewMode.WITHOUT_AI -> Image(withoutAiImg, "Without AI", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            ReviewMode.SPLIT_COMPARE -> {
                Box(
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        detectHorizontalDragGestures { _, drag ->
                            val w = size.width.toFloat().coerceAtLeast(1f)
                            splitFraction = (splitFraction + drag / w).coerceIn(0.1f, 0.9f)
                        }
                    }
                ) {
                    Image(withoutAiImg, "Without AI", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    Image(
                        bitmap = withAiImg,
                        contentDescription = "With AI",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().drawWithContent {
                            val splitX = size.width * splitFraction
                            clipRect(left = splitX) { this@drawWithContent.drawContent() }
                            drawLine(Color(0xFF64D2FF), Offset(splitX, 0f), Offset(splitX, size.height), 5f)
                        }
                    )
                }
            }
        }

        LiquidGlassContainer(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(16.dp)) {
            val title = when (reviewMode) {
                ReviewMode.WITH_AI -> "WITH AI • ${leicaProfile.label}"
                ReviewMode.WITHOUT_AI -> "WITHOUT AI • Raw Sensor"
                ReviewMode.SPLIT_COMPARE -> "SPLIT: Left Without AI | Right With AI"
            }
            Text(
                text = "${zoomLevel.roundToInt()}x  |  $title",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp)
                .width(94.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.60f), Color.White.copy(alpha = 0.14f))
                    )
                )
                .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(24.dp))
                .padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("AI SIDEBAR", color = Color.White.copy(alpha = 0.75f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            SidebarCard("Without AI", withoutAiImg, reviewMode == ReviewMode.WITHOUT_AI, Color(0xFFFFB84D)) {
                reviewMode = ReviewMode.WITHOUT_AI
            }
            SidebarCard("With AI", withAiImg, reviewMode == ReviewMode.WITH_AI, Color(0xFF64D2FF)) {
                reviewMode = ReviewMode.WITH_AI
            }
            LiquidGlassButton("50/50", reviewMode == ReviewMode.SPLIT_COMPARE, Color(0xFF64D2FF)) {
                reviewMode = ReviewMode.SPLIT_COMPARE
            }
        }

        Row(
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().fillMaxWidth().padding(24.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            LiquidGlassButton("↺ Retake", true, Color(0xFFFF453A), onRetake)
            val saveRaw = reviewMode == ReviewMode.WITHOUT_AI
            LiquidGlassButton(
                text = if (saveRaw) "✓ Save (Without AI)" else "✓ Save (With AI)",
                isActive = true,
                accentColor = Color(0xFF32D74B)
            ) {
                if (saveRaw) onSave(photoPair.withoutAi, "WithoutAI")
                else onSave(photoPair.withAi, "WithAI")
            }
        }
    }
}

@Composable
fun SidebarCard(title: String, thumb: ImageBitmap, selected: Boolean, accent: Color, onClick: () -> Unit) {
    val bg = if (selected) accent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.08f)
    val stroke = if (selected) accent else Color.White.copy(alpha = 0.25f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .border(if (selected) 1.5.dp else 1.dp, stroke, RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(thumb, title, Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
        Spacer(Modifier.height(4.dp))
        Text(title, color = if (selected) accent else Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
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
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.25f), Color.White.copy(alpha = 0.08f))))
            .border(1.dp, Brush.verticalGradient(listOf(borderColor, Color.White.copy(alpha = 0.12f))), RoundedCornerShape(50)),
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
    val bmp = toBitmap()
    val matrix = android.graphics.Matrix().apply { postRotate(imageInfo.rotationDegrees.toFloat()) }
    return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
}

fun saveBitmapToGallery(context: Context, bitmap: Bitmap, quality: Int, tag: String) {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "XiaomiAICam_${tag}_${System.currentTimeMillis()}.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/XiaomiAICam")
        }
    }
    context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)?.let { uri ->
        context.contentResolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
    }
}
// END OF FILE

