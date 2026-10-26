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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import dev.sparkynox.sparkytube.ui.SparkyTheme
import java.util.Locale

// Audio FX screen: presets + every knob of the native engine
class AudioFxActivity : AppCompatActivity() {

    private var p by mutableStateOf(LunoFx.params)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LunoFx.init(this)
        p = LunoFx.params
        setContentView(ComposeView(this).apply {
            setContent { SparkyTheme { Screen() } }
        })
    }

    private fun set(n: LunoFx.Params) {
        p = n
        LunoFx.update(this, n)
    }

    private fun f(v: Float, unit: String = "") = String.format(Locale.US, "%.1f", v) + unit

    @Composable
    private fun Card(title: String, content: @Composable () -> Unit) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                content()
            }
        }
    }

    @Composable
    private fun Knob(label: String, value: Float, range: ClosedFloatingPointRange<Float>, unit: String = "", onChange: (Float) -> Unit) {
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                Text(f(value, unit), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Slider(value = value, onValueChange = onChange, valueRange = range)
        }
    }

    @Composable
    private fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = on, onCheckedChange = onChange)
        }
    }

    @Composable
    private fun Screen() {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    Text("Audio FX", Modifier.padding(start = 4.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                }
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Card("Engine") {
                            Toggle("Enable effects", p.on) { set(p.copy(on = it)) }
                            Text(
                                if (NativeFx.loaded) "Native C++ engine, runs on the audio itself" else "Native library didn't load, effects are off",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (NativeFx.loaded) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(LunoFx.presets, key = { it.name }) { pr ->
                                FilterChip(
                                    selected = p == pr.params,
                                    onClick = {
                                        set(pr.params)
                                        pr.speed?.let { LunoFx.speedHook?.invoke(it, it) }
                                    },
                                    label = { Text(pr.name) },
                                )
                            }
                        }
                    }
                    item {
                        Card("Equalizer") {
                            LunoFx.BANDS.forEachIndexed { i, hz ->
                                Knob("$hz Hz", p.eq[i], -12f..12f, " dB") { v ->
                                    set(p.copy(on = true, eq = p.eq.toMutableList().also { it[i] = v }))
                                }
                            }
                        }
                    }
                    item {
                        Card("Sound") {
                            Knob("Bass boost", p.bass, 0f..12f, " dB") { set(p.copy(on = true, bass = it)) }
                            Knob("Stereo width", p.width, 0f..2.5f, "x") { set(p.copy(on = true, width = it)) }
                        }
                    }
                    item {
                        Card("Reverb") {
                            Knob("Amount", p.rvMix, 0f..1f) { set(p.copy(on = true, rvMix = it)) }
                            Knob("Room size", p.rvRoom, 0f..1f) { set(p.copy(on = true, rvRoom = it)) }
                            Knob("Damping", p.rvDamp, 0f..1f) { set(p.copy(on = true, rvDamp = it)) }
                        }
                    }
                    item {
                        Card("8D audio") {
                            Toggle("Sound circles around your head", p.p8On) { set(p.copy(on = true, p8On = it)) }
                            Knob("Seconds per turn", p.p8Period, 3f..20f, " s") { set(p.copy(p8Period = it)) }
                            Knob("Depth", p.p8Depth, 0f..1f) { set(p.copy(p8Depth = it)) }
                        }
                    }
                    item {
                        Card("Volume normalizer") {
                            Toggle("Even out loud and quiet videos", p.normOn) { set(p.copy(on = true, normOn = it)) }
                            Knob("Target level", p.normTarget, -24f..-8f, " dB") { set(p.copy(normTarget = it)) }
                            Text(
                                "A limiter always runs at the end so nothing clips.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
