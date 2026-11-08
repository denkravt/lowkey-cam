package com.justan.lowkeycam.ui

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.justan.lowkeycam.data.Store

private data class QualityOption(
    val key: String,
    val title: String,
    val blurb: String,
)

private val QUALITIES = listOf(
    QualityOption("UHD", "4K \u00b7 2160p", "Biggest files \u00b7 best detail"),
    QualityOption("FHD", "1080p \u00b7 Full HD", "Recommended \u00b7 crisp on any screen"),
    QualityOption("HD", "720p \u00b7 HD", "Smallest files \u00b7 fastest exports"),
)

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var quality by remember { mutableStateOf(Store.videoQuality(context)) }

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
                Text("\u2039", fontSize = 22.sp, color = Ink)
            }
            Spacer(Modifier.width(14.dp))
            Text("Settings", style = MaterialTheme.typography.headlineMedium, color = Ink)
        }

        Spacer(Modifier.height(26.dp))
        Text("VIDEO", style = MaterialTheme.typography.labelSmall, color = Moss)
        Text(
            text = "Recording resolution",
            style = MaterialTheme.typography.titleMedium,
            color = Ink,
            modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
        )

        QUALITIES.forEach { option ->
            val selected = option.key == quality
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = if (selected) Card else Sand.copy(alpha = 0.5f),
                shadowElevation = if (selected) 3.dp else 0.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable {
                        quality = option.key
                        Store.setVideoQuality(context, option.key)
                    },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = option.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (selected) Forest else Ink,
                        )
                        Text(
                            text = option.blurb,
                            style = MaterialTheme.typography.bodySmall,
                            color = Muted,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(if (selected) Forest else Sand),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(Cream))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        Text(
            text = "Low-key mode crops a 16:9 band out of the portrait frame, so its file matches " +
                "this resolution instead of being blown up afterwards. Standard mode records at the " +
                "highest quality your camera offers up to the setting above.",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
        )

        Spacer(Modifier.height(14.dp))
        Text(
            text = "Higher resolutions make the 16:9 crop and the \u201cexport as one video\u201d step take " +
                "longer. 1080p is the sweet spot for a phone vlog.",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
        )

        Spacer(Modifier.height(28.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "LowKey \u00b7 clips stay on this phone until you export",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}
