package com.justan.lowkeycam

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
import com.justan.lowkeycam.ui.Card
import com.justan.lowkeycam.ui.Cream
import com.justan.lowkeycam.ui.Forest
import com.justan.lowkeycam.ui.Ink
import com.justan.lowkeycam.ui.LowKeyTheme
import com.justan.lowkeycam.ui.Moss
import com.justan.lowkeycam.ui.Muted
import com.justan.lowkeycam.ui.ProjectScreen
import com.justan.lowkeycam.ui.Sand
import com.justan.lowkeycam.widget.QuickRecordWidgetReceiver
import com.justan.lowkeycam.widget.RecordWidgetReceiver
import com.justan.lowkeycam.widget.WidgetUpdater
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Store.ensureProject(this)
        setContent {
            LowKeyTheme {
                var openProjectId by remember { mutableStateOf<String?>(null) }
                val open = openProjectId?.let { id -> Store.projects(this).firstOrNull { it.id == id } }
                if (open != null) {
                    BackHandler { openProjectId = null }
                    ProjectScreen(
                        project = open,
                        onBack = { openProjectId = null },
                    )
                } else {
                    HomeScreen(onOpenProject = { openProjectId = it.id })
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(onOpenProject: (Project) -> Unit = {}) {
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

    val project = remember(tick) { Store.ensureProject(context) }
    val projects = remember(tick) { Store.projects(context) }
    val clips = remember(tick, project.id) { Store.clips(context, project.id) }
    var showNew by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Cream)
            .windowInsetsPadding(WindowInsets.systemBars)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 18.dp),
    ) {
        Text(
            text = "LowKey",
            style = MaterialTheme.typography.displayLarge,
            color = Ink,
        )
        Text(
            text = "Hold portrait. Shoot landscape.",
            style = MaterialTheme.typography.bodyMedium,
            color = Muted,
        )

        Surface(
            shape = RoundedCornerShape(28.dp),
            color = Card,
            shadowElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 22.dp),
        ) {
            Column(Modifier.padding(22.dp)) {
                Text(
                    text = "CURRENT PROJECT",
                    style = MaterialTheme.typography.labelSmall,
                    color = Moss,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = project.name,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Ink,
                )
                Text(
                    text = if (clips.isEmpty()) "No shots yet — first one's on you." else
                        "${clips.size} clips · ${formatDuration(clips.sumOf { it.durationMs })}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        label = "\u25CF Low-key",
                        filled = true,
                        modifier = Modifier.weight(1f),
                    ) {
                        context.startActivity(CameraActivity.intent(context, lowKey = true))
                    }
                    Button(
                        label = "Normal",
                        filled = false,
                        modifier = Modifier.weight(1f),
                    ) {
                        context.startActivity(CameraActivity.intent(context, lowKey = false))
                    }
                }
                if (clips.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "Share latest clip",
                        style = MaterialTheme.typography.bodySmall,
                        color = Forest,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { shareClip(context, clips.last()) },
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 28.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("PROJECTS", style = MaterialTheme.typography.labelSmall, color = Moss)
            Spacer(Modifier.weight(1f))
            Text(
                text = "+ New",
                style = MaterialTheme.typography.labelSmall,
                color = Forest,
                modifier = Modifier.clickable { showNew = true },
            )
        }

        projects.forEach { p ->
            ProjectRow(
                project = p,
                clipCount = if (p.id == project.id) clips.size else Store.clips(context, p.id).size,
                selected = p.id == project.id,
            ) {
                Store.setCurrent(context, p.id)
                WidgetUpdater.update(context)
                tick++
                onOpenProject(p)
            }
        }

        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PinPill("Pin widget · 3×2", Modifier.weight(1f)) { pinWidget(context, compact = false) }
            PinPill("Pin REC · 1×1", Modifier.weight(1f)) { pinWidget(context, compact = true) }
        }

        Spacer(Modifier.height(26.dp))
        Text(
            text = "Clips stay inside the app. Nothing lands in your gallery unless you share it.",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
        )
        Text(
            text = "Widget: long-press your home screen \u2192 Widgets \u2192 LowKey.",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
            modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.height(20.dp))
    }

    if (showNew) {
        NewProjectDialog(
            onDismiss = { showNew = false },
            onCreate = { name ->
                Store.newProject(context, name)
                WidgetUpdater.update(context)
                showNew = false
                tick++
            },
        )
    }
}

@Composable
private fun ProjectRow(
    project: Project,
    clipCount: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (selected) Card else Color.Transparent,
        shadowElevation = if (selected) 4.dp else 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(project.name, style = MaterialTheme.typography.titleMedium, color = Ink)
                Text(
                    if (clipCount == 1) "1 clip" else "$clipCount clips",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }
            if (selected) {
                Box(Modifier.size(10.dp).background(Forest, CircleShape)) {}
                Spacer(Modifier.width(8.dp))
            }
            Text("\u203A", fontSize = 20.sp, color = Muted)
        }
    }
}

@Composable
private fun Button(label: String, filled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(100.dp),
        color = if (filled) Forest else Sand,
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 15.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 14.sp,
                ),
                fontWeight = FontWeight.Bold,
                color = if (filled) Cream else Ink,
            )
        }
    }
}

@Composable
private fun NewProjectDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New project", style = MaterialTheme.typography.headlineMedium) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Name") },
                placeholder = { Text("Week in Japan") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name.ifBlank { "Untitled" }) }) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun pinWidget(context: android.content.Context, compact: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(AppWidgetManager::class.java) ?: return
    if (!manager.isRequestPinAppWidgetSupported) return
    val receiver = if (compact) QuickRecordWidgetReceiver::class.java else RecordWidgetReceiver::class.java
    manager.requestPinAppWidget(ComponentName(context, receiver), null, null)
}

@Composable
private fun PinPill(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(100.dp),
        color = Sand,
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = Forest,
            )
        }
    }
}

private fun shareClip(context: android.content.Context, clip: Clip) {
    val file = File(Store.clipsDir(context), clip.fileName)
    if (!file.exists()) return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share clip"))
}
