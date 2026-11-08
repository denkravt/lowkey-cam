package com.justan.lowkeycam.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import kotlin.math.roundToInt
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
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
import com.justan.lowkeycam.data.ClipProcessor
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
    var musicVolume by remember { mutableStateOf(Store.musicVolumeFor(context, project.id)) }
    var playingVlog by remember { mutableStateOf(false) }
    var displayName by remember { mutableStateOf(project.name) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var recapRunning by remember { mutableStateOf(false) }
    var recapProgress by remember { mutableStateOf(0 to 0) }
    var menuOpen by remember { mutableStateOf(false) }
    var pendingClearAll by remember { mutableStateOf(false) }
    var pendingWipeProject by remember { mutableStateOf(false) }
    var dragFrom by remember { mutableStateOf(-1) }
    var dragOffset by remember { mutableStateOf(0f) }
    var rowHeightPx by remember { mutableStateOf(0f) }

    fun dropTarget(index: Int): Int {
        if (rowHeightPx <= 0f) return index
        return (index + (dragOffset / rowHeightPx).roundToInt()).coerceIn(0, (clips.size - 1).coerceAtLeast(0))
    }

    fun runRecap(seconds: Int) {
        if (recapRunning || clips.isEmpty()) return
        val limitMs = seconds * 1000L
        scope.launch {
            recapRunning = true
            var done = 0
            for (clip in clips) {
                if (clip.durationMs > limitMs + 250L) {
                    val file = File(Store.clipsDir(context), clip.fileName)
                    val cut = ClipProcessor.trim(context, file, 0L, limitMs)
                    if (cut != null) {
                        file.delete()
                        Store.updateClip(context, clip.copy(fileName = cut.name, durationMs = limitMs))
                    }
                }
                done++
                recapProgress = done to clips.size
            }
            recapRunning = false
            WidgetUpdater.update(context)
            tick++
        }
    }

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
                text = displayName,
                style = MaterialTheme.typography.headlineMedium,
                color = Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clickable { renaming = displayName },
            )
            Box {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(100.dp))
                        .background(Sand)
                        .clickable { menuOpen = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("\u22EE", fontSize = 16.sp, color = Ink)
                }
                DropdownMenu(containerColor = Card, expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Delete all clips", color = if (clips.isEmpty()) Muted else RecordRed) },
                        enabled = clips.isNotEmpty(),
                        onClick = {
                            menuOpen = false
                            pendingClearAll = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete project", color = RecordRed) },
                        onClick = {
                            menuOpen = false
                            pendingWipeProject = true
                        },
                    )
                }
            }
        }

        Text(
            text = if (clips.isEmpty()) "No shots yet" else
                (if (clips.size == 1) "1 clip" else "${clips.size} clips") +
                    " \u00b7 " + formatDuration(total),
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

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = Sand,
            modifier = Modifier
                .weight(1f)
                .clickable(enabled = clips.isNotEmpty()) { playingVlog = true },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "\u25B6 Play",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                    maxLines = 1,
                )
                Text(
                    text = clips.size.toString() + (if (clips.size == 1) " clip in order" else " clips in order"),
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(22.dp),
            color = if (clips.isEmpty()) Sand else Forest,
            modifier = Modifier
                .weight(1f)
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
                            musicVolume = musicVolume,
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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = when {
                        exporting && exportProgress.second > 0 ->
                            "Exporting " + exportProgress.first + "/" + exportProgress.second + "\u2026"
                        exporting -> "Exporting\u2026"
                        else -> "Export vlog"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (clips.isEmpty()) Muted else Cream,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = clips.size.toString() + (if (clips.size == 1) " clip \u00b7 one video" else " clips \u00b7 one video"),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (clips.isEmpty()) Muted.copy(alpha = 0.7f) else Cream.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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

        if (musicName != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Level",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                    modifier = Modifier.width(44.dp),
                )
                Slider(
                    value = musicVolume,
                    onValueChange = { musicVolume = it },
                    onValueChangeFinished = { Store.setMusicVolume(context, project.id, musicVolume) },
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = Forest,
                        activeTrackColor = Forest,
                        inactiveTrackColor = Sand,
                    ),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = (musicVolume * 100).roundToInt().toString() + "%",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink,
                    modifier = Modifier.width(44.dp),
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        Text("CLIPS", style = MaterialTheme.typography.labelSmall, color = Moss)
        Text(
            text = if (recapRunning) {
                "Trimming every clip to " + recapProgress.first + "/" + recapProgress.second + "\u2026"
            } else {
                "Drag to reorder · tap to trim"
            },
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
        )

        if (!recapRunning && clips.size > 1) {
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "RECAP",
                    style = MaterialTheme.typography.labelSmall,
                    color = Moss,
                )
                listOf(2, 3, 5).forEach { seconds ->
                    Surface(
                        shape = RoundedCornerShape(100.dp),
                        color = Sand,
                        modifier = Modifier.clickable { runRecap(seconds) },
                    ) {
                        Text(
                            text = seconds.toString() + "s all",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = Forest,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        if (clips.isEmpty()) {
            Text(
                text = "Everything you shoot in this project lands here — kept out of your gallery.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        }

        clips.forEachIndexed { index, clip ->
            val dragging = dragFrom == index
            ClipRow(
                clip = clip,
                index = index,
                dragging = dragging,
                dragOffset = if (dragging) dragOffset else 0f,
                dropMarker = dragFrom >= 0 && dragFrom != index && index == dropTarget(dragFrom),
                onHeight = { height -> if (rowHeightPx <= 0f) rowHeightPx = height },
                onDragStart = {
                    dragFrom = index
                    dragOffset = 0f
                },
                onDrag = { delta -> dragOffset += delta },
                onDragEnd = {
                    val target = dropTarget(dragFrom)
                    if (target != dragFrom) {
                        Store.moveClipTo(context, project.id, dragFrom, target)
                        WidgetUpdater.update(context)
                    }
                    dragFrom = -1
                    dragOffset = 0f
                    tick++
                },
                onEdit = { editingClip = clip },
                onPlay = { playClip(context, clip) },
                onShare = { share(context, File(Store.clipsDir(context), clip.fileName)) },
                onMoveUp = {
                    Store.moveClip(context, project.id, clip.id, -1)
                    WidgetUpdater.update(context)
                    tick++
                },
                onMoveDown = {
                    Store.moveClip(context, project.id, clip.id, 1)
                    WidgetUpdater.update(context)
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

    if (playingVlog) {
        Dialog(
            onDismissRequest = { playingVlog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            VlogPlayerScreen(
                clips = clips,
                projectName = displayName,
                onClose = { playingVlog = false },
            )
        }
    }

    renaming?.let { current ->
        var text by remember(current) { mutableStateOf(current) }
        AlertDialog(
            containerColor = Cream,
            onDismissRequest = { renaming = null },
            title = { Text("Rename project", style = MaterialTheme.typography.headlineMedium) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text("Name") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val clean = text.trim().ifBlank { current }
                        Store.renameProject(context, project.id, clean)
                        WidgetUpdater.update(context)
                        displayName = clean
                        renaming = null
                    }
                ) { Text("Save", color = Forest, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text("Cancel", color = Ink) }
            },
        )
    }

    pendingDelete?.let { clip ->
        AlertDialog(
            containerColor = Cream,
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

    if (pendingClearAll) {
        AlertDialog(
            containerColor = Cream,
            onDismissRequest = { pendingClearAll = false },
            title = { Text("Delete all clips?", style = MaterialTheme.typography.headlineMedium) },
            text = {
                Text(
                    text = if (clips.size == 1) {
                        "The only clip in this project is deleted from this phone. The project itself stays."
                    } else {
                        "All " + clips.size + " clips in this project are deleted from this phone. " +
                            "The project itself stays."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        Store.deleteAllClips(context, project.id)
                        WidgetUpdater.update(context)
                        exportedFile = null
                        exportNote = null
                        pendingClearAll = false
                        tick++
                    }
                ) {
                    Text("Delete all", fontWeight = FontWeight.Bold, color = RecordRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingClearAll = false }) { Text("Cancel", color = Ink) }
            },
        )
    }

    if (pendingWipeProject) {
        AlertDialog(
            containerColor = Cream,
            onDismissRequest = { pendingWipeProject = false },
            title = { Text("Delete this project?", style = MaterialTheme.typography.headlineMedium) },
            text = {
                Text(
                    text = if (clips.size == 1) {
                        "\"" + displayName + "\", its 1 clip and the music track are deleted from this phone. " +
                            "This can't be undone."
                    } else {
                        "\"" + displayName + "\", its " + clips.size + " clips and the music track are " +
                            "deleted from this phone. This can't be undone."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        Store.deleteProject(context, project.id)
                        Store.ensureProject(context)
                        WidgetUpdater.update(context)
                        pendingWipeProject = false
                        onBack()
                    }
                ) {
                    Text("Delete", fontWeight = FontWeight.Bold, color = RecordRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingWipeProject = false }) { Text("Cancel", color = Ink) }
            },
        )
    }
}

@Composable
private fun ClipRow(
    clip: Clip,
    index: Int,
    dragging: Boolean,
    dragOffset: Float,
    dropMarker: Boolean,
    onHeight: (Float) -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
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
        shadowElevation = if (dragging) 14.dp else 3.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer {
                translationY = if (dragging) dragOffset else 0f
                scaleX = if (dragging) 1.02f else 1f
                scaleY = if (dragging) 1.02f else 1f
                alpha = if (dragging) 0.96f else 1f
            }
            .onSizeChanged { if (index == 0) onHeight(it.height.toFloat()) }
            .drawWithContent {
                drawContent()
                if (dropMarker) {
                    drawRect(
                        color = androidx.compose.ui.graphics.Color(0xFF2B4B3A),
                        topLeft = Offset(0f, 0f),
                        size = Size(size.width, 6f),
                    )
                }
            }
            .clickable(onClick = onEdit)
            .pointerInput(clip.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { onDragStart() },
                    onDrag = { change, amount ->
                        change.consume()
                        onDrag(amount.y)
                    },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() },
                )
            },
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
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.path)
                // Decode small: a full 1080p frame is ~8MB per row and they all stay resident.
                retriever.getScaledFrameAtTime(
                    1_000_000,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    264,
                    168,
                )?.asImageBitmap()
            } catch (t: Throwable) {
                null
            } finally {
                runCatching { retriever.release() }
            }
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
