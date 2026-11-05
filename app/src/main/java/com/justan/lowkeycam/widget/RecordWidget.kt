package com.justan.lowkeycam.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.action.Action
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.justan.lowkeycam.camera.CameraActivity
import com.justan.lowkeycam.data.Store
import com.justan.lowkeycam.data.formatDuration

class RecordWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext
        val project = Store.ensureProject(app)
        val clips = Store.clips(app, project.id)
        val count = clips.size
        val total = clips.sumOf { it.durationMs }

        provideContent {
            WidgetBody(app, project.name, count, total)
        }
    }
}

private val Cream: androidx.glance.unit.ColorProvider = ColorProvider(day = Color(0xFFFAF6EF), night = Color(0xFF17181A))
private val Ink: androidx.glance.unit.ColorProvider = ColorProvider(day = Color(0xFF1E1B16), night = Color(0xFFF3EFE6))
private val Forest: androidx.glance.unit.ColorProvider = ColorProvider(day = Color(0xFF2B4B3A), night = Color(0xFF7FB294))
private val Muted: androidx.glance.unit.ColorProvider = ColorProvider(day = Color(0xFF8A8172), night = Color(0xFFA9A296))
private val SandChip: androidx.glance.unit.ColorProvider = ColorProvider(day = Color(0xFFEDE5D6), night = Color(0xFF2C2E31))

@Composable
private fun WidgetBody(
    context: Context,
    projectName: String,
    clipCount: Int,
    totalMs: Long,
) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(Cream)
            .cornerRadius(26.dp)
            .padding(14.dp),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Text(
            text = "LOWKEY",
            style = TextStyle(
                color = Forest,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.SansSerif,
            ),
            maxLines = 1,
        )
        Spacer(modifier = GlanceModifier.height(2.dp))
        Text(
            text = projectName,
            style = TextStyle(
                color = Ink,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Serif,
            ),
            maxLines = 1,
        )
        Text(
            text = if (clipCount == 0) "No shots yet" else "$clipCount clips · ${formatDuration(totalMs)}",
            style = TextStyle(color = Muted, fontSize = 11.sp, fontFamily = FontFamily.SansSerif),
            maxLines = 1,
        )

        Spacer(modifier = GlanceModifier.height(10.dp))

        val lowKeyAction = actionStartActivity(CameraActivity.intent(context, lowKey = true, autoStart = true))
        val normalAction = actionStartActivity(CameraActivity.intent(context, lowKey = false, autoStart = true))

        Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
            Pill(
                label = "\u25CF Low-key",
                background = Forest,
                textColor = Cream,
                onClick = lowKeyAction,
            )
            Spacer(modifier = GlanceModifier.width(8.dp))
            Pill(
                label = "Normal",
                background = SandChip,
                textColor = Ink,
                onClick = normalAction,
            )
        }
    }
}

@Composable
private fun Pill(
    label: String,
    background: androidx.glance.unit.ColorProvider,
    textColor: androidx.glance.unit.ColorProvider,
    onClick: Action,
) {
    Box(
        modifier = GlanceModifier
            .background(background)
            .cornerRadius(100.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = TextStyle(color = textColor, fontSize = 12.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
    }
}
