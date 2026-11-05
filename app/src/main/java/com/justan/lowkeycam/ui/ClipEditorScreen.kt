package com.justan.lowkeycam.ui

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.justan.lowkeycam.data.Clip
import com.justan.lowkeycam.data.ClipProcessor
import com.justan.lowkeycam.data.Store
import com.justan.lowkeycam.data.formatDuration
import com.justan.lowkeycam.widget.WidgetUpdater
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ClipEditorScreen(clip: Clip, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val file = remember(clip.fileName) { File(Store.clipsDir(context), clip.fileName) }

    var durationMs by remember { mutableStateOf(clip.durationMs) }
    var startMs by remember { mutableStateOf(0f) }
    var endMs by remember { mutableStateOf(clip.durationMs.toFloat()) }
    var saving by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(false) }

    LaunchedEffect(file.path) {
        val probed = withContext(Dispatchers.IO) {
            runCatching {
                val r = MediaMetadataRetriever()
                r.setDataSource(file.path)
                val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                r.release()
                d
            }.getOrDefault(0L)
        }
        if (probed > 0L) {
            durationMs = probed
            endMs = probed.toFloat()
        }
    }

    val exoPlayer = remember { ExoPlayer.Builder(context).build() }

    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer.pause()
            exoPlayer.release()
        }
    }

    LaunchedEffect(file.path) {
        exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        exoPlayer.prepare()
    }

    // keep playback inside the trim window
    LaunchedEffect(playing, startMs, endMs) {
        while (playing) {
            if (exoPlayer.currentPosition >= endMs.toLong() ||
                exoPlayer.currentPosition < startMs.toLong() - 120
            ) {
                exoPlayer.seekTo(startMs.toLong())
            }
            delay(120)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Cream)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(100.dp))
                    .background(Sand)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) { Text("‹", fontSize = 22.sp, color = Ink) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Trim clip", style = MaterialTheme.typography.headlineMedium, color = Ink)
                Text(
                    text = formatDuration(durationMs) + " · " + if (clip.lowKey) "low-key" else "portrait",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(20.dp))
                .background(androidx.compose.ui.graphics.Color.Black),
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        player = exoPlayer
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        Spacer(Modifier.height(18.dp))

        Text(
            text = formatDuration(startMs.toLong()) + "  →  " + formatDuration(endMs.toLong()) +
                "   (" + formatDuration((endMs - startMs).toLong()) + " kept)",
            style = MaterialTheme.typography.bodyMedium,
            color = Ink,
        )

        RangeSlider(
            value = startMs..endMs,
            onValueChange = { range ->
                startMs = range.start.coerceIn(0f, durationMs.toFloat())
                endMs = range.endInclusive.coerceIn(startMs + 200f, durationMs.toFloat())
            },
            valueRange = 0f..durationMs.toFloat().coerceAtLeast(1f),
            modifier = Modifier.padding(top = 4.dp),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Surface(
                shape = RoundedCornerShape(100.dp),
                color = Sand,
                modifier = Modifier
                    .weight(1f)
                    .clickable {
                        exoPlayer.seekTo(startMs.toLong())
                        if (exoPlayer.isPlaying) {
                            exoPlayer.pause()
                            playing = false
                        } else {
                            exoPlayer.play()
                            playing = true
                        }
                    },
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 13.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = if (playing) "Pause" else "Preview",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = Ink,
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(100.dp),
                color = Forest,
                modifier = Modifier
                    .weight(1.4f)
                    .clickable(enabled = !saving && endMs - startMs > 300f) {
                        saving = true
                        note = null
                        scope.launch {
                            exoPlayer.pause()
                            playing = false
                            val trimmed = ClipProcessor.trim(context, file, startMs.toLong(), endMs.toLong())
                            if (trimmed != null) {
                                file.delete()
                                val updated = clip.copy(
                                    fileName = trimmed.name,
                                    durationMs = (endMs - startMs).toLong(),
                                )
                                Store.updateClip(context, updated)
                                WidgetUpdater.update(context)
                                note = "Trimmed · " + formatDuration(updated.durationMs)
                                saving = false
                                onClose()
                            } else {
                                note = "Trim failed — clip left untouched"
                                saving = false
                            }
                        }
                    },
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 13.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = if (saving) "Trimming\\u2026" else "Save trim",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = Cream,
                    )
                }
            }
        }

        note?.let {
            Spacer(Modifier.height(10.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = if (it.startsWith("Trim failed")) RecordRed else Moss,
            )
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = "Drag the handles to pick the part you keep. The rest is cut for good.",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
