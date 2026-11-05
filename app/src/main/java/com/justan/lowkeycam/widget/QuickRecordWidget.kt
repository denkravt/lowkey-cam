package com.justan.lowkeycam.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
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
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.justan.lowkeycam.camera.CameraActivity

/**
 * 1x1 companion to [RecordWidget]: a single big record button.
 * One tap -> low-key camera, already rolling.
 */
class QuickRecordWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext
        provideContent { CompactBody(app) }
    }
}

private val ForestBg: androidx.glance.unit.ColorProvider =
    ColorProvider(day = Color(0xFF2B4B3A), night = Color(0xFF24392E))
private val DotRed: androidx.glance.unit.ColorProvider =
    ColorProvider(day = Color(0xFFFF5A4D), night = Color(0xFFFF7A6E))
private val LabelCream: androidx.glance.unit.ColorProvider =
    ColorProvider(day = Color(0xFFFAF6EF), night = Color(0xFFEDE5D6))

@Composable
private fun CompactBody(context: Context) {
    val recordAction = actionStartActivity(
        CameraActivity.intent(context, lowKey = true, autoStart = true)
    )

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ForestBg)
            .cornerRadius(20.dp)
            .clickable(recordAction),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            Box(
                modifier = GlanceModifier.size(18.dp).background(DotRed).cornerRadius(9.dp),
            ) {}
            Spacer(modifier = GlanceModifier.height(5.dp))
            Text(
                text = "REC",
                style = TextStyle(
                    color = LabelCream,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
        }
    }
}
