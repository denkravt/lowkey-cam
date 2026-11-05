package com.justan.lowkeycam.ui

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.justan.lowkeycam.camera.CameraActivity
import com.justan.lowkeycam.data.Clip
import com.justan.lowkeycam.data.Project
import com.justan.lowkeycam.data.Store
import com.justan.lowkeycam.data.formatDuration
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ProjectScreen(project: Project, onBack: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current

    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    val clips = remember(tick, project.id) { Store.clips(context, project.id) }
    val total = clips.sumOf { it.durationMs }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Cream)
            .windowInsetsPadding(WindowInsets.systemBars)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(100.dp))
                    .background(Sand)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Text("‹", fontSize = 22.sp, color = Ink)
            }
            Spacer(Modifier.width(14.dp))
            Text(
                text = project.name,
                style = MaterialTheme.typography.headlineMedium,
                color = Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Text(
            text = if (clips.isEmpty()) "No shots yet" else
                "${clips.size} clips · ${formatDuration(total)}",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
            modifier = Modifier.padding(top = 6.dp, start = 54.dp),
        )

        Surface(
            shape = RoundedCornerShape(100.dp),
            color = Forest,
            modifier = Modifier
                .padding(top = 18.dp)
                .fillMaxWidth()
                .clickable {
                    Store.setCurrent(context, project.id)
                    context.startActivity(CameraActivity.intent(context, lowKey = true))
                },
        ) {
            Column(
                modifier = Modifier.padding(vertical = 15.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "● Record into this project",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = Cream,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        Text("CLIPS", style = MaterialTheme.typography.labelSmall, color = Moss)
        Spacer(Modifier.height(8.dp))

        if (clips.isEmpty()) {
            Text(
                text = "Everything you shoot in this project lands here — kept out of your gallery.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        }

        clips.asReversed().forEach { clip ->
            ClipRow(clip = clip) { playClip(context, clip) }
        }

        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun ClipRow(clip: Clip, onClick: () -> Unit) {
    val context = LocalContext.current
    val file = remember(clip.fileName) { File(Store.clipsDir(context), clip.fileName) }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Card,
        shadowElevation = 3.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ClipThumb(file)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = formatDuration(clip.durationMs),
                    style = MaterialTheme.typography.titleMedium,
                    color = Ink,
                )
                Text(
                    text = stamp(clip.createdAt) + " · " + if (clip.lowKey) "low-key" else "portrait",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SmallGlyph("▶") { onClick() }
                SmallGlyph("↗") { share(context, file) }
            }
        }
    }
}

@Composable
private fun SmallGlyph(glyph: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(100.dp))
            .background(Sand)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, fontSize = 13.sp, color = Ink)
    }
}

@Composable
private fun ClipThumb(file: File) {
    var bitmap by remember(file.path) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(file.path) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(file.path)
                val frame = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                retriever.release()
                frame?.asImageBitmap()
            }.getOrNull()
        }
    }

    Box(
        modifier = Modifier
            .size(width = 88.dp, height = 56.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Sand),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun stamp(ms: Long): String =
    SimpleDateFormat("d MMM · HH:mm", Locale.getDefault()).format(Date(ms))

private fun clipUri(context: Context, file: File) =
    FileProvider.getUriForFile(context, "${context.packageName}.files", file)

private fun playClip(context: Context, clip: Clip) {
    val file = File(Store.clipsDir(context), clip.fileName)
    if (!file.exists()) return
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(clipUri(context, file), "video/mp4")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(intent) }
}

private fun share(context: Context, file: File) {
    if (!file.exists()) return
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, clipUri(context, file))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "Share clip")) }
}
