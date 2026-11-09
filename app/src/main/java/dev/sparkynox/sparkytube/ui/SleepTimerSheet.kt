package dev.sparkynox.sparkytube.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import dev.sparkynox.sparkytube.sleep.SleepTimer
import kotlinx.coroutines.delay

/** Slider for the sleep timer: pick minutes, press Start. Opens from the ⋮ menu. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerSheet(player: () -> Player?, pause: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var minutes by remember { mutableFloatStateOf(30f) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // keeps the "left" text moving while the sheet is open
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 20.dp)) {
            Text("Sleep timer", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)

            if (SleepTimer.active) {
                val leftSec = ((SleepTimer.endsAt - now) / 1000).coerceAtLeast(0)
                val label = if (SleepTimer.atVideoEnd) "Pausing when this video ends" else
                    "Pausing in %d:%02d".format(leftSec / 60, leftSec % 60)
                Text(
                    label, Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                )
                TextButton(onClick = { SleepTimer.cancel(); Toast.makeText(ctx, "Sleep timer off", Toast.LENGTH_SHORT).show(); onDismiss() }) {
                    Text("Turn off")
                }
            }

            Text(
                formatMinutes(minutes.toInt()), Modifier.padding(top = 20.dp),
                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
            )
            // 5 to 120 minutes in steps of 5
            Slider(
                value = minutes, onValueChange = { minutes = it },
                valueRange = 5f..120f, steps = 22,
            )
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        val m = minutes.toInt()
                        SleepTimer.startMinutes(m, pause)
                        Toast.makeText(ctx, "Pausing in ${formatMinutes(m)}", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Start") }
                OutlinedButton(
                    onClick = {
                        if (SleepTimer.startAtVideoEnd(player(), pause)) {
                            Toast.makeText(ctx, "Pausing when this video ends", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        } else Toast.makeText(ctx, "Play a video first", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("End of video") }
            }
        }
    }
}

private fun formatMinutes(m: Int): String =
    if (m < 60) "$m min" else if (m % 60 == 0) "${m / 60} h" else "${m / 60} h ${m % 60} min"
