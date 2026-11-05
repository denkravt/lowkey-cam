package com.justan.lowkeycam.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.justan.lowkeycam.camera.CameraActivity
import com.justan.lowkeycam.data.Clip
import com.justan.lowkeycam.data.Project
import com.justan.lowkeycam.data.MediaStorePublisher
import com.justan.lowkeycam.data.Store
import com.justan.lowkeycam.data.VlogExporter
import com.justan.lowkeycam.data.formatDuration
import com.justan.lowkeycam.widget.WidgetUpdater
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableStateOf(0 to 0) }
    var exportNote by remember { mutableStateOf<String?>(null) }
    var exportedFile by remember { mutableStateOf<File?>(null) }
    var exportedUri by remember { mutableStateOf<Uri?>(null) }
    var pendingDelete by remember { mutableStateOf<Clip?>(null) }
    var editingClip by remember { mutableStateOf<Clip?>(null) }
    var musicName by remember { mutableStateOf(Store.musicFor(context, project.id)) }

    val musicPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val copied = withContext(Dispatchers.IO) { copyMusic(context, uri) }
                if (copied != null) {
                    Store.setMusic(context, project.id, copied)
                    musicName = copied
                }
            }
        }
    }

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

        Surface(
            shape = RoundedCornerShape(100.dp),
            color = Card,
            shadowElevation = 3.dp,
            modifier = Modifier
                .padding(top = 10.dp)
                .fillMaxWidth()
                .clickable(enabled = !exporting && clips.isNotEmpty()) {
                    exporting = true
                    exportNote = null
                    exportedFile = null
                    exportedUri = null
                    exportProgress = 0 to clips.size
                    scope.launch {
                        val outcome = VlogExporter.export(
                            context = context,
                            projectId = project.id,
                            projectName = project.name,
                            musicFile = musicName?.let { File(Store.musicDir(context), it) },
                        ) { done, totalClips -> exportProgress = done to totalClips }
                        exporting = false
                        exportedFile = outcome.file

                        var published: Uri? = null
                        outcome.file?.let { file ->
                            published = withContext(Dispatchers.IO) {
                                MediaStorePublisher.publish(
                                    context,
                                    file,
                                    MediaStorePublisher.displayName(project.name),
                                )
                            }
                            exportedUri = published
                        }

                        val summary = when {
                            outcome.error != null -> "Export failed: " + outcome.error
                            outcome.skippedIncompatible > 0 ->
                                "Exported " + outcome.used + " clips · " +
                                    outcome.skippedIncompatible + " couldn't be joined"
                            outcome.skippedOrientation > 0 ->
                                "Exported " + outcome.used + " clips · " +
                                    outcome.skippedOrientation + " of the other orientation left out"
                            else -> "Exported " + outcome.used + " clips as one video"
                        }
                        exportNote = summary + when {
                            published != null -> " · saved to gallery (Movies/LowKey)"
                            outcome.file != null -> " · gallery save failed"
                            else -> ""
                        }
                    }
                },
        ) {
            Column(
                modifier = Modifier.padding(vertical = 13.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = when {
                        exporting && exportProgress.second > 0 ->
                            "Exporting " + exportProgress.first + "/" + exportProgress.second + "\u2026"
                        exporting -> "Exporting\u2026"
                        else -> "Export vlog as one video"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                )
                Text(
                    text = clips.size.toString() + " clips \u2192 one video \u00b7 goes to your gallery",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }
        }

        exportNote?.let { note ->
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = if (note.startsWith("Export failed")) RecordRed else Moss,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        val shareTarget = exportedUri ?: exportedFile?.let { clipUri(context, it) }
        if (shareTarget != null) {
            Row(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(100.dp))
                    .background(Forest)
                    .clickable { shareUri(context, shareTarget) }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Share the vlog \u2197",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = Cream,
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("MUSIC", style = MaterialTheme.typography.labelSmall, color = Moss)
                Text(
                    text = musicName?.let { trackLabel(it) } ?: "No track — the vlog uses clip audio",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (musicName != null) Ink else Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (musicName != null) {
                Text(
                    text = "Remove",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                    modifier = Modifier
                        .padding(end = 12.dp)
                        .clickable {
                            Store.setMusic(context, project.id, null)
                            musicName = null
                        },
                )
            }
            Surface(
                shape = RoundedCornerShape(100.dp),
                color = Sand,
                modifier = Modifier.clickable { musicPicker.launch(arrayOf("audio/*")) },
            ) {
                Text(
                    text = if (musicName == null) "Add track" else "Change",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = Forest,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        Text("CLIPS", style = MaterialTheme.typography.labelSmall, color = Moss)
        Text(
            text = "Tap a clip to trim it",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
        )
        Spacer(Modifier.height(8.dp))

        if (clips.isEmpty()) {
            Text(
                text = "Everything you shoot in this project lands here — kept out of your gallery.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        }

        clips.asReversed().forEach { clip ->
            ClipRow(
                clip = clip,
                onEdit = { editingClip = clip },
                onPlay = { playClip(context, clip) },
                onShare = { share(context, File(Store.clipsDir(context), clip.fileName)) },
                onMoveUp = {
                    Store.moveClip(context, project.id, clip.id, -1)
                    tick++
                },
                onMoveDown = {
                    Store.moveClip(context, project.id, clip.id, 1)
                    tick++
                },
                onDelete = { pendingDelete = clip },
            )
        }

        Spacer(Modifier.height(30.dp))
    }

    editingClip?.let { clip ->
        Dialog(
            onDismissRequest = { editingClip = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            ClipEditorScreen(
                clip = clip,
                onClose = {
                    editingClip = null
                    exportNote = null
                    exportedFile = null
                    exportedUri = null
                    tick++
                },
            )
        }
    }

    pendingDelete?.let { clip ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this clip?", style = MaterialTheme.typography.headlineMedium) },
            text = {
                Text(
                    text = "The " + formatDuration(clip.durationMs) +
                        " clip is removed from this vlog and deleted from storage.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val file = File(Store.clipsDir(context), clip.fileName)
                        runCatching { file.delete() }
                        Store.removeClip(context, clip.id)
                        WidgetUpdater.update(context)
                        exportedFile = null
                        exportNote = null
                        pendingDelete = null
                        tick++
                    }
                ) {
                    Text("Delete", fontWeight = FontWeight.Bold, color = RecordRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel", color = Ink) }
            },
        )
    }
}

@Composable
private fun ClipRow(
    clip: Clip,
    onEdit: () -> Unit,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val file = remember(clip.fileName) { File(Store.clipsDir(context), clip.fileName) }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Card,
        shadowElevation = 3.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onEdit),
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
                SmallGlyph("▶") { onPlay() }
                Box {
                    SmallGlyph("\u22EE") { menuOpen = true }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Trim clip", style = MaterialTheme.typography.bodyMedium) },
                            onClick = { menuOpen = false; onEdit() },
                        )
                        DropdownMenuItem(
                            text = { Text("Move up", style = MaterialTheme.typography.bodyMedium) },
                            onClick = { menuOpen = false; onMoveUp() },
                        )
                        DropdownMenuItem(
                            text = { Text("Move down", style = MaterialTheme.typography.bodyMedium) },
                            onClick = { menuOpen = false; onMoveDown() },
                        )
                        DropdownMenuItem(
                            text = { Text("Share", style = MaterialTheme.typography.bodyMedium) },
                            onClick = { menuOpen = false; onShare() },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Delete",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = RecordRed,
                                )
                            },
                            onClick = { menuOpen = false; onDelete() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SmallGlyph(glyph: String, destructive: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(100.dp))
            .background(Sand)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, fontSize = 13.sp, color = if (destructive) RecordRed else Ink)
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

private fun copyMusic(context: Context, uri: Uri): String? = runCatching {
    val display = context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        ?: "track.audio"
    val clean = display.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9 ]"), "").trim()
        .ifBlank { "track" }
    val ext = display.substringAfterLast('.', "m4a").take(5)
    val target = File(Store.musicDir(context), System.currentTimeMillis().toString() + "__" + clean + "." + ext)
    context.contentResolver.openInputStream(uri)?.use { input ->
        target.outputStream().use { output -> input.copyTo(output) }
    }
    target.name
}.getOrNull()

private fun trackLabel(stored: String): String =
    stored.substringAfter("__").substringBeforeLast('.')

private fun stamp(ms: Long): String =
    SimpleDateFormat("d MMM · HH:mm", Locale.getDefault()).format(Date(ms))

private fun clipUri(context: Context, file: File) =
    FileProvider.getUriForFile(context, "${context.packageName}.files", file)

private fun shareUri(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "Share vlog")) }
}

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
