package dev.sparkynox.sparkytube.lunokit.stats

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sparkynox.sparkytube.ui.SparkyTheme

class StatsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SparkyTheme {
                StatsScreen(onBack = { finish() })
            }
        }
    }
}

private fun fmt(ms: Long): String {
    val m = ms / 60_000
    return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
}

@Composable
private fun StatsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    val s = remember(tick) { StatsStore.snapshot(ctx) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "\u2190",
                    Modifier.clickable(onClick = onBack).padding(end = 16.dp, top = 4.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.headlineSmall
                )
                Text("Stats", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile("Watched", fmt(s.totalMs), Modifier.weight(1f))
                Tile("Music mode", fmt(s.musicMs), Modifier.weight(1f))
                Tile("Videos", s.videos.toString(), Modifier.weight(1f))
            }

            Text("Last 7 days", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            WeekChart(s.week)

            if (s.topChannels.isNotEmpty()) {
                Text("Top channels", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                s.topChannels.forEachIndexed { i, (name, ms) -> Line("${i + 1}", name, fmt(ms)) }
            }
            if (s.topVideos.isNotEmpty()) {
                Text("Most watched", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                s.topVideos.forEachIndexed { i, v -> Line("${i + 1}", v.title, fmt(v.ms)) }
            }
            if (s.totalMs == 0L) {
                Text(
                    "Nothing yet, watch something and come back.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { StatsStore.reset(ctx); tick++ }) { Text("Reset stats") }
        }
    }
}

@Composable
private fun Tile(label: String, value: String, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(14.dp),
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Line(rank: String, title: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(rank, Modifier.padding(end = 12.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Text(title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun WeekChart(week: List<StatsStore.Day>) {
    val max = (week.maxOfOrNull { it.ms } ?: 0L).coerceAtLeast(1L)
    val bar = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    Column(Modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val slot = size.width / week.size
            val w = slot * 0.55f
            week.forEachIndexed { i, d ->
                val x = i * slot + (slot - w) / 2
                drawRoundRect(track, Offset(x, 0f), Size(w, size.height), CornerRadius(8f, 8f))
                val h = size.height * (d.ms.toFloat() / max)
                if (h > 0f) drawRoundRect(bar, Offset(x, size.height - h), Size(w, h), CornerRadius(8f, 8f))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            week.forEach {
                Text(
                    it.label,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
