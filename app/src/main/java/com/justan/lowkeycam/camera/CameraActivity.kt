package com.justan.lowkeycam.camera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.view.Surface
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.justan.lowkeycam.data.Store
import com.justan.lowkeycam.data.formatDuration
import com.justan.lowkeycam.ui.LowKeyTheme
import com.justan.lowkeycam.widget.WidgetUpdater
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay
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
        setContent {
            LowKeyTheme {
                CameraScreen(lowKey, autoStart) { finish() }
            }
        }
    }
}

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

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
                    if (!event.hasError() && file.exists() && file.length() > 0L) {
                        Store.addClip(
                            context,
                            Clip(
                                id = UUID.randomUUID().toString(),
                                projectId = project.id,
                                fileName = name,
                                durationMs = durMs,
                                lowKey = lowAtStart,
                                createdAt = System.currentTimeMillis(),
                            ),
                        )
                        WidgetUpdater.update(context)
                        note = "Saved · " + formatDuration(durMs) + " · " + if (lowAtStart) "landscape" else "portrait"
                    } else {
                        error = "Recording failed (code " + event.error + ")"
                        file.delete()
                    }
                    recording = null
                    paused = false
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

    LaunchedEffect(cameraGranted, lensFacing) {
        if (!cameraGranted) return@LaunchedEffect
        bound = false
        runCatching {
            val provider = awaitCameraProvider(context)
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.fromOrderedList(
                        listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD),
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
                    )
                )
                .build()
            val vc = VideoCapture.withOutput(recorder)
            vc.targetRotation = if (lowKey) Surface.ROTATION_90 else Surface.ROTATION_0
            val preview = Preview.Builder()
                .setTargetRotation(if (lowKey) Surface.ROTATION_90 else Surface.ROTATION_0)
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
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
        val rotation = if (lowKey) Surface.ROTATION_90 else Surface.ROTATION_0
        videoCapture?.targetRotation = rotation
        previewUseCase?.targetRotation = rotation
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

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {

            // ---- top bar ----
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleButton("\u2715", onClose)
                Spacer(Modifier.weight(1f))
                Surface(shape = RoundedCornerShape(100.dp), color = Color(0x33FFFFFF)) {
                    Text(
                        text = if (lowKey) "LOW-KEY · LANDSCAPE" else "NORMAL · PORTRAIT",
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

            // ---- preview ----
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (lowKey) {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val screenW = maxWidth
                        val frameH = screenW * (9f / 16f)
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .width(screenW)
                                .height(frameH)
                                .clip(RoundedCornerShape(26.dp))
                                .background(Color(0xFF111110))
                                .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(26.dp)),
                        ) {
                            AndroidView(
                                factory = { previewView },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                } else {
                    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
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
                        text = "hold portrait · shoot landscape",
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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Chip("Low-key", lowKey) {
                        if (!isRecording) {
                            lowKey = !lowKey
                            Store.setLowKeyDefault(context, lowKey)
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
}

@Composable
private fun CircleButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color(0x33FFFFFF))
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
        color = if (active) Color.White else Color(0x2EFFFFFF),
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
