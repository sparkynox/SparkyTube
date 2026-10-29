package dev.sparkynox.sparkytube.fx

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import dev.sparkynox.sparkytube.net.RemoteConfig
import dev.sparkynox.sparkytube.ui.SparkyTheme
import java.util.Locale

// Anime upscaler screen: Off / Light / Strong plus the two knobs
@UnstableApi
class VideoFxActivity : AppCompatActivity() {

    private var p by mutableStateOf(LunoVideoFx.params)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LunoVideoFx.init(this)
        p = LunoVideoFx.params
        setContentView(ComposeView(this).apply {
            setContent { SparkyTheme { Screen() } }
        })
        RemoteConfig.gate(this, "video_fx") { finish() }
    }

    private fun set(n: LunoVideoFx.Params) {
        p = n
        LunoVideoFx.update(this, n)
    }

    @Composable
    private fun Card(title: String, content: @Composable () -> Unit) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                content()
            }
        }
    }

    @Composable
    private fun Knob(label: String, value: Float, onChange: (Float) -> Unit) {
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                Text(String.format(Locale.US, "%.2f", value), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Slider(value = value, onValueChange = onChange, valueRange = 0f..1f)
        }
    }

    @Composable
    private fun Screen() {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    Text("Anime upscaler", Modifier.padding(start = 4.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                }
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Card("Mode") {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                LunoVideoFx.Mode.values().forEach { m ->
                                    FilterChip(
                                        selected = p.mode == m,
                                        onClick = { set(LunoVideoFx.Params(m, m.push, m.sharp)) },
                                        label = { Text(m.label) },
                                    )
                                }
                            }
                            Text(
                                "Makes low-resolution anime look sharper: the video is scaled up, then every frame goes through a GPU pass that keeps line art thin and crisp. Light is 1.5x, Strong is 2x (never above 1080p).",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    item {
                        Card("Fine tuning") {
                            Knob("Line push", p.push) { set(p.copy(push = it)) }
                            Knob("Sharpen", p.sharp) { set(p.copy(sharp = it)) }
                        }
                    }
                    item {
                        Card("Good to know") {
                            Text(
                                "For anime and cartoons. Live action can look over-processed. It uses the GPU, so the phone gets warmer and weaker phones may stutter, use Light there. It does nothing on 1080p video, HDR video or audio-only playback. If your phone can't run it, it turns itself off. Changes apply to the video that is playing.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
