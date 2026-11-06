package com.justan.lowkeycam.camera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.justan.lowkeycam.data.Clip
import com.justan.lowkeycam.data.ClipProcessor
import com.justan.lowkeycam.data.Store
import com.justan.lowkeycam.data.formatDuration
import com.justan.lowkeycam.ui.LowKeyTheme
import com.justan.lowkeycam.widget.WidgetUpdater
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CameraActivity : ComponentActivity() {

    companion object {
        const val EXTRA_LOWKEY = "lowkey"
        const val EXTRA_AUTOSTART = "autostart"

        fun intent(context: Context, lowKey: Boolean, autoStart: Boolean = false): Intent =
            Intent(context, CameraActivity::class.java)
                .putExtra(EXTRA_LOWKEY, lowKey)
                .putExtra(EXTRA_AUTOSTART, autoStart)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        val lowKey = intent.getBooleanExtra(EXTRA_LOWKEY, Store.lowKeyDefault(this))
        val autoStart = intent.getBooleanExtra(EXTRA_AUTOSTART, false)
        // Low-key frames a 16:9 band out of a portrait take, so it stays upright.
        // Standard mode follows the device instead.
        if (lowKey) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        setContent {
            LowKeyTheme {
                CameraScreen(lowKey, autoStart) { finish() }
            }
        }
    }
}

private fun buildClip(projectId: String, fileName: String, durationMs: Long, lowKey: Boolean) = Clip(
    id = UUID.randomUUID().toString(),
    projectId = projectId,
    fileName = fileName,
    durationMs = durationMs,
    lowKey = lowKey,
    createdAt = System.currentTimeMillis(),
)

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/** Display rotation of the phone itself — the preview view may not be attached yet at bind time. */
private fun currentRotation(context: Context): Int {
    val manager = context.getSystemService(DisplayManager::class.java)
    val display = manager?.getDisplay(Display.DEFAULT_DISPLAY) ?: return Surface.ROTATION_0
    return display.rotation
}

private suspend fun awaitCameraProvider(context: Context): ProcessCameraProvider =
    suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                try {
                    cont.resume(future.get())
                } catch (t: Throwable) {
                    cont.resumeWithException(t)
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

@Composable
private fun CameraScreen(lowKeyInitial: Boolean, autoStart: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    var lowKey by remember { mutableStateOf(lowKeyInitial) }
    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var torch by remember { mutableStateOf(false) }
    var stealth by remember { mutableStateOf(false) }

    var cameraGranted by remember { mutableStateOf(hasPermission(context, Manifest.permission.CAMERA)) }
    var audioGranted by remember { mutableStateOf(hasPermission(context, Manifest.permission.RECORD_AUDIO)) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        cameraGranted = hasPermission(context, Manifest.permission.CAMERA) ||
            result[Manifest.permission.CAMERA] == true
        audioGranted = hasPermission(context, Manifest.permission.RECORD_AUDIO) ||
            result[Manifest.permission.RECORD_AUDIO] == true
    }

    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var previewUseCase by remember { mutableStateOf<Preview?>(null) }
    var lensOptions by remember { mutableStateOf<List<Pair<Float, CameraSelector>>>(emptyList()) }
    var gravityX by remember { mutableFloatStateOf(0f) }

    DisposableEffect(Unit) {
        val sensorManager = context.getSystemService(SensorManager::class.java)
        val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                gravityX = event.values[0]
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (accelerometer != null) {
            sensorManager?.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
        }
        onDispose { sensorManager?.unregisterListener(listener) }
    }
    var lensIndex by remember { mutableStateOf(0) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var bound by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var paused by remember { mutableStateOf(false) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var startAt by remember { mutableLongStateOf(0L) }
    var pausedAt by remember { mutableLongStateOf(0L) }
    var pausedTotal by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var autoConsumed by remember { mutableStateOf(false) }

    val project = remember { Store.ensureProject(context) }

    fun startRecording() {
        val vc = videoCapture ?: return
        if (recording != null) return
        val lowAtStart = lowKey
        val name = "lowkey_" + System.currentTimeMillis() + ".mp4"
        val file = File(Store.clipsDir(context), name)
        var pending = vc.output.prepareRecording(context, FileOutputOptions.Builder(file).build())
        if (audioGranted) pending = pending.withAudioEnabled()
        note = null
        startAt = SystemClock.elapsedRealtime()
        pausedAt = 0L
        pausedTotal = 0L
        elapsedMs = 0L
        val started = pending.start(ContextCompat.getMainExecutor(context)) { event ->
            when (event) {
                is VideoRecordEvent.Finalize -> {
                    val durMs = event.recordingStats.recordedDurationNanos / 1_000_000L
                    recording = null
                    paused = false
                    if (!event.hasError() && file.exists() && file.length() > 0L) {
                        if (lowAtStart) {
                            // Keep the preview upright while shooting, then trim to the framed 16:9 band.
                            note = "Cropping to 16:9\u2026"
                            scope.launch {
                                val cropped = ClipProcessor.cropTo16x9(context, file)
                                val savedName = if (cropped != null) {
                                    file.delete()
                                    cropped.name
                                } else {
                                    name
                                }
                                Store.addClip(
                                    context,
                                    buildClip(project.id, savedName, durMs, lowAtStart),
                                )
                                WidgetUpdater.update(context)
                                note = (if (cropped != null) "Saved · landscape · " else "Cropping failed · kept portrait · ") +
                                    formatDuration(durMs)
                            }
                        } else {
                            Store.addClip(
                                context,
                                buildClip(project.id, name, durMs, lowAtStart),
                            )
                            WidgetUpdater.update(context)
                            note = "Saved · portrait · " + formatDuration(durMs)
                        }
                    } else {
                        error = "Recording failed (code " + event.error + ")"
                        file.delete()
                    }
                }
                else -> Unit
            }
        }
        recording = started
        paused = false
    }

    fun togglePause() {
        val r = recording ?: return
        if (paused) {
            r.resume()
            pausedTotal += SystemClock.elapsedRealtime() - pausedAt
            pausedAt = 0L
            paused = false
        } else {
            r.pause()
            pausedAt = SystemClock.elapsedRealtime()
            paused = true
        }
    }

    LaunchedEffect(Unit) {
        if (!cameraGranted) {
            launcher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            )
        }
    }

    // Standard mode follows the device: every display rotation re-points preview + recorder.
    DisposableEffect(Unit) {
        val displayManager = context.getSystemService(DisplayManager::class.java)
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                val rotation = currentRotation(context)
                if (lowKey) return
                previewUseCase?.targetRotation = rotation
                videoCapture?.targetRotation = rotation
            }
        }
        displayManager?.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        onDispose { displayManager?.unregisterDisplayListener(listener) }
    }

    LaunchedEffect(cameraGranted, lensFacing, lensIndex, lensOptions.size) {
        if (!cameraGranted) return@LaunchedEffect
        bound = false
        runCatching {
            val provider = awaitCameraProvider(context)

            // Every physical lens the device exposes (ultra-wide / wide / tele) becomes a chip.
            runCatching {
                val options = provider.availableCameraInfos
                    .filter { runCatching { it.lensFacing }.getOrNull() == lensFacing }
                    .mapNotNull { info ->
                        val raw = info.intrinsicZoomRatio
                        val ratio = if (raw <= 0f || raw == CameraInfo.INTRINSIC_ZOOM_RATIO_UNKNOWN) 1f else raw
                        runCatching { ratio to info.cameraSelector }.getOrNull()
                    }
                    .distinctBy { (ratio, _) -> (ratio * 100).roundToInt() }
                    .sortedBy { (ratio, _) -> ratio }
                if (options.isNotEmpty() && options.size != lensOptions.size) {
                    lensOptions = options
                }
            }
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.fromOrderedList(
                        listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD),
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
                    )
                )
                .build()
            val rotation = if (lowKey) Surface.ROTATION_0 else currentRotation(context)
            val vc = VideoCapture.withOutput(recorder)
            vc.targetRotation = rotation
            val preview = Preview.Builder()
                .setTargetRotation(rotation)
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val selector = lensOptions.getOrNull(lensIndex)?.second
                ?: CameraSelector.Builder().requireLensFacing(lensFacing).build()
            provider.unbindAll()
            val cam = provider.bindToLifecycle(owner, selector, preview, vc)
            previewUseCase = preview
            videoCapture = vc
            camera = cam
            error = null
            bound = true
        }.onFailure {
            error = it.message ?: "Camera unavailable"
        }
    }

    LaunchedEffect(lowKey, bound) {
        // Both stay portrait: the low-key crop happens after the take, so the preview is upright.
        videoCapture?.targetRotation = Surface.ROTATION_0
        previewUseCase?.targetRotation = Surface.ROTATION_0
    }

    LaunchedEffect(torch, bound) {
        runCatching {
            camera?.cameraControl?.enableTorch(torch && lensFacing == CameraSelector.LENS_FACING_BACK)
        }
    }

    LaunchedEffect(recording) {
        while (recording != null) {
            val now = SystemClock.elapsedRealtime()
            elapsedMs = if (paused && pausedAt > 0L) {
                (pausedAt - startAt - pausedTotal).coerceAtLeast(0L)
            } else {
                (now - startAt - pausedTotal).coerceAtLeast(0L)
            }
            delay(100)
        }
        if (startAt > 0L) elapsedMs = SystemClock.elapsedRealtime() - startAt - pausedTotal
    }

    LaunchedEffect(note) {
        if (note != null) {
            delay(2600)
            note = null
        }
    }

    LaunchedEffect(bound, autoStart) {
        if (bound && autoStart && !autoConsumed) {
            autoConsumed = true
            startRecording()
        }
    }

    DisposableEffect(Unit) {
        onDispose { runCatching { recording?.stop() } }
    }

    val isRecording = recording != null

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val landscape = maxWidth > maxHeight
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {

            if (!landscape) {
            // ---- top bar ----
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleButton("\u2715", onClose)
                Spacer(Modifier.weight(1f))
                Surface(shape = RoundedCornerShape(100.dp), color = Color(0x33FFFFFF)) {
                    Text(
                        text = if (lowKey) "LOW-KEY · 16:9" else "STANDARD · " + (if (landscape) "LANDSCAPE" else "PORTRAIT"),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = formatDuration(elapsedMs),
                    color = if (isRecording) Color(0xFFFF6B5E) else Color(0x99FFFFFF),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            }

            // ---- preview ----
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

                if (lowKey) {
                    // Everything stays upright; the bright band is exactly what gets kept.
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val bandHeight = maxWidth * (9f / 16f)
                        val shade = (maxHeight - bandHeight) / 2f
                        Box(
                            Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .height(shade)
                                .background(Color(0xB3000000))
                        )
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(shade)
                                .background(Color(0xB3000000))
                        )
                        Box(
                            Modifier
                                .align(Alignment.Center)
                                .fillMaxWidth()
                                .height(bandHeight)
                                .border(1.dp, Color(0x99FFFFFF))
                        )
                    }
                }

                if (stealth) {
                    Box(
                        Modifier.fillMaxSize().background(Color.Black),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isRecording) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier.size(8.dp)
                                        .background(Color(0xFFFF6B5E), CircleShape)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "REC " + formatDuration(elapsedMs),
                                    color = Color(0x55FFFFFF),
                                    fontSize = 12.sp,
                                    letterSpacing = 2.sp,
                                )
                            }
                        }
                    }
                }

                if (note != null) {
                    Text(
                        text = note.orEmpty(),
                        color = Color.White,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 14.dp)
                            .clip(RoundedCornerShape(100.dp))
                            .background(Color(0xE02B4B3A))
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                if (lowKey && !isRecording && !stealth) {
                    Text(
                        text = "upright preview · keeps the 16:9 band",
                        color = Color(0xCCFFFFFF),
                        fontSize = 12.sp,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 14.dp)
                            .clip(RoundedCornerShape(100.dp))
                            .background(Color(0x66000000))
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }

                if (error != null) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color(0xE6FFFFFF),
                        modifier = Modifier.padding(28.dp),
                    ) {
                        Column(Modifier.padding(20.dp)) {
                            Text("Camera problem", fontWeight = FontWeight.Bold, color = Color(0xFF1E1B16))
                            Spacer(Modifier.height(6.dp))
                            Text(error.orEmpty(), fontSize = 12.sp, color = Color(0xFF5A5A5A))
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "Close",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF2B4B3A),
                                modifier = Modifier.clickable {
                                    error = null
                                    onClose()
                                },
                            )
                        }
                    }
                }

                if (!cameraGranted) {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = Color(0xF2FFFFFF),
                        modifier = Modifier.padding(28.dp),
                    ) {
                        Column(Modifier.padding(22.dp)) {
                            Text(
                                "Camera access needed",
                                style = MaterialTheme.typography.headlineMedium,
                                color = Color(0xFF1E1B16),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "LowKey needs the camera (and mic for sound) to record your clips.",
                                fontSize = 13.sp,
                                color = Color(0xFF5A5A5A),
                            )
                            Spacer(Modifier.height(14.dp))
                            Text(
                                "Grant access",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF2B4B3A),
                                modifier = Modifier.clickable {
                                    launcher.launch(
                                        arrayOf(
                                            Manifest.permission.CAMERA,
                                            Manifest.permission.RECORD_AUDIO,
                                        )
                                    )
                                },
                            )
                        }
                    }
                }
            }

            if (!landscape) {
            // ---- controls ----
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircleButton(if (lensFacing == CameraSelector.LENS_FACING_BACK) "\u21BB" else "\u21BA") {
                        if (!isRecording) {
                            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                            lensOptions = emptyList()
                            lensIndex = 0
                            torch = false
                        }
                    }

                    Spacer(Modifier.width(26.dp))

                    Box(
                        modifier = Modifier
                            .size(84.dp)
                            .clip(CircleShape)
                            .border(4.dp, Color.White, CircleShape)
                            .clickable {
                                if (isRecording) {
                                    runCatching { recording?.stop() }
                                    paused = false
                                } else {
                                    startRecording()
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isRecording) {
                            Box(
                                Modifier
                                    .size(30.dp)
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(Color(0xFFFF5A4D))
                            )
                        } else {
                            Box(
                                Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFFF5A4D))
                            )
                        }
                    }

                    Spacer(Modifier.width(26.dp))

                    if (isRecording) {
                        CircleButton(if (paused) "\u25B6" else "\u2161") { togglePause() }
                    } else {
                        Spacer(Modifier.size(44.dp))
                    }
                }

                Spacer(Modifier.height(16.dp))

                if (lensOptions.size > 1) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        lensOptions.forEachIndexed { index, lens ->
                            Chip(zoomLabel(lens.first), index == lensIndex) {
                                if (!isRecording) lensIndex = index
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Chip("Low-key", lowKey) {
                        if (!isRecording) {
                            lowKey = !lowKey
                            Store.setLowKeyDefault(context, lowKey)
                            (context as? ComponentActivity)?.requestedOrientation =
                                if (lowKey) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                else ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                        }
                    }
                    Chip("Blackout", stealth) { stealth = !stealth }
                    Chip("Torch", torch) {
                        if (!isRecording && lensFacing == CameraSelector.LENS_FACING_BACK) torch = !torch
                    }
                }
            }
            }
        }

        // ---- landscape overlay: preview is full-bleed, the shutter rides the physical bottom edge ----
        if (landscape) {
            // Accelerometer X tells us which short edge of the phone is in the user's palm:
            // reading > 0 means the top edge points left, so the device bottom is on the right.
            val controlsOnRight = gravityX > 1f

            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(130.dp)
                    .background(Brush.verticalGradient(listOf(Color(0xAA000000), Color(0x00000000))))
            )
            Box(
                Modifier
                    .align(if (controlsOnRight) Alignment.CenterEnd else Alignment.CenterStart)
                    .fillMaxHeight()
                    .width(180.dp)
                    .background(
                        Brush.horizontalGradient(
                            if (controlsOnRight) listOf(Color(0x00000000), Color(0xCC000000))
                            else listOf(Color(0xCC000000), Color(0x00000000))
                        )
                    )
            )

            Box(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)
            ) {
                // top bar + chips
                Column(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircleButton("\u2715", onClose)
                        Spacer(Modifier.weight(1f))
                        Surface(shape = RoundedCornerShape(100.dp), color = Color(0x33FFFFFF)) {
                            Text(
                                text = if (lowKey) "LOW-KEY · 16:9" else "STANDARD · LANDSCAPE",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.2.sp,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = formatDuration(elapsedMs),
                            color = if (isRecording) Color(0xFFFF6B5E) else Color(0x99FFFFFF),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (lensOptions.size > 1) {
                            lensOptions.forEachIndexed { index, lens ->
                                Chip(zoomLabel(lens.first), index == lensIndex) {
                                    if (!isRecording) lensIndex = index
                                }
                            }
                        }
                        Chip("Low-key", lowKey) {
                            if (!isRecording) {
                                lowKey = !lowKey
                                Store.setLowKeyDefault(context, lowKey)
                                (context as? ComponentActivity)?.requestedOrientation =
                                    if (lowKey) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                    else ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                            }
                        }
                        Chip("Blackout", stealth) { stealth = !stealth }
                        Chip("Torch", torch) {
                            if (!isRecording && lensFacing == CameraSelector.LENS_FACING_BACK) torch = !torch
                        }
                    }
                }

                // shutter stack pinned to the physical bottom of the device
                Column(
                    modifier = Modifier
                        .align(if (controlsOnRight) Alignment.CenterEnd else Alignment.CenterStart)
                        .padding(horizontal = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircleButton(if (lensFacing == CameraSelector.LENS_FACING_BACK) "\u21BB" else "\u21BA") {
                        if (!isRecording) {
                            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                            lensOptions = emptyList()
                            lensIndex = 0
                            torch = false
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(78.dp)
                            .clip(CircleShape)
                            .border(4.dp, Color.White, CircleShape)
                            .clickable {
                                if (isRecording) {
                                    runCatching { recording?.stop() }
                                    paused = false
                                } else {
                                    startRecording()
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isRecording) {
                            Box(
                                Modifier
                                    .size(28.dp)
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(Color(0xFFFF5A4D))
                            )
                        } else {
                            Box(
                                Modifier
                                    .size(58.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFFF5A4D))
                            )
                        }
                    }

                    if (isRecording) {
                        CircleButton(if (paused) "\u25B6" else "\u2161") { togglePause() }
                    } else {
                        Spacer(Modifier.size(44.dp))
                    }
                }
            }
        }
    }
}

private fun zoomLabel(ratio: Float): String {
    val rounded = (ratio * 10f).roundToInt() / 10f
    return if (rounded % 1f == 0f) rounded.toInt().toString() + "\u00d7" else rounded.toString() + "\u00d7"
}

@Composable
private fun CircleButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color(0x59000000))
            .border(1.dp, Color(0x40FFFFFF), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = Color.White, fontSize = 17.sp)
    }
}

@Composable
private fun Chip(label: String, active: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(100.dp),
        color = if (active) Color.White else Color(0x73000000),
        border = if (active) null else BorderStroke(1.dp, Color(0x40FFFFFF)),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            color = if (active) Color(0xFF1E1B16) else Color(0xCCFFFFFF),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
