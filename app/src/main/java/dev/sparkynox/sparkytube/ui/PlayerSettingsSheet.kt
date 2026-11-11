package dev.sparkynox.sparkytube.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import dev.sparkynox.sparkytube.lunokit.glass.LunoSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import dev.sparkynox.sparkytube.R
import dev.sparkynox.sparkytube.sleep.SleepTimer
import dev.sparkynox.sparkytube.lunokit.ambient.AmbientPrefs

private enum class Page { ROOT, QUALITY, SPEED, MORE, AMBIENT }

/**
 * The player's own settings menu, opened from the gear on the player (like YouTube's).
 * Quality, speed, loop, music mode, sleep timer and the rest live here instead of in the top bar's ⋮ menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSettingsSheet(h: PlayerControlsHost, onDismiss: () -> Unit, onSleep: () -> Unit) {
    var page by remember { mutableStateOf(Page.ROOT) }
    val c = h.controller()
    var loop by remember { mutableStateOf(c?.repeatMode == Player.REPEAT_MODE_ONE) }
    var music by remember { mutableStateOf(h.musicOn()) }
    var speed by remember { mutableStateOf(h.speed()) }
    var quality by remember { mutableStateOf(h.qualityLabel()) }
    val onIcon = MaterialTheme.colorScheme.onSecondaryContainer

    LunoSheet(onDismissRequest = onDismiss) {
        AnimatedContent(
            targetState = page,
            transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(110)) },
            modifier = Modifier.animateContentSize(),
            label = "playerSettings",
        ) { p ->
            Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp).verticalScroll(rememberScrollState())) {
                if (p != Page.ROOT) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { page = Page.ROOT }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                        Text(
                            when (p) { Page.QUALITY -> "Quality"; Page.SPEED -> "Playback speed"; Page.AMBIENT -> "Ambient mode"; else -> "More" },
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                when (p) {
                    Page.ROOT -> {
                        Row0(R.drawable.ic_quality, onIcon, "Quality", quality, chevron = true) { page = Page.QUALITY }
                        Row0(R.drawable.ic_play, onIcon, "Playback speed", fmtSpeed(speed), chevron = true) { page = Page.SPEED }
                        if (h.audioTracks() > 1) Row0(R.drawable.ic_audio_track, onIcon, "Audio track", null, chevron = true) { onDismiss(); h.onAudio() }
                        SettingRow(
                            icon = { Icon(Icons.Filled.Refresh, null, tint = onIcon) }, label = "Loop video",
                            trailing = { Switch(checked = loop, onCheckedChange = null) },
                        ) {
                            loop = !loop
                            c?.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                        }
                        SettingRow(
                            icon = { ResIcon(R.drawable.ic_music_note, onIcon, 22.dp) }, label = "Music mode",
                            trailing = { Switch(checked = music, onCheckedChange = null) },
                        ) { music = !music; h.onMusic() }
                        SettingRow(
                            icon = { ResIcon(android.R.drawable.ic_menu_gallery, onIcon, 22.dp) }, label = "Ambient mode",
                            trailing = { Switch(checked = AmbientPrefs.enabled, onCheckedChange = null) },
                        ) { AmbientPrefs.changeEnabled(!AmbientPrefs.enabled) }
                        if (AmbientPrefs.enabled) Row0(android.R.drawable.ic_menu_preferences, onIcon, "Ambient settings", null, chevron = true) { page = Page.AMBIENT }
                        val sleepText = if (!SleepTimer.active) "Off" else if (SleepTimer.atVideoEnd) "End of video" else
                            "${((SleepTimer.endsAt - System.currentTimeMillis()) / 60000L).coerceAtLeast(0) + 1} min"
                        Row0(android.R.drawable.ic_lock_idle_alarm, onIcon, "Sleep timer", sleepText, chevron = true) { onSleep() }
                        Row0(R.drawable.ic_about, onIcon, "More", null, chevron = true) { page = Page.MORE }
                    }
                    Page.QUALITY -> {
                        val list = h.qualities()
                        if (list.isEmpty()) Text(
                            "No quality options yet, try again once playback has started.",
                            Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        list.forEachIndexed { i, label ->
                            ChoiceRow(label, label == quality) {
                                quality = label
                                h.onPickQuality(i)
                                onDismiss()
                            }
                        }
                    }
                    Page.SPEED -> h.speeds.forEach { s ->
                        ChoiceRow(fmtSpeed(s), s == speed) { speed = s; h.setSpeed(s); onDismiss() }
                    }
                    Page.AMBIENT -> {
                        AmbientSlider("Strength", AmbientPrefs.strength, 0..100, "%") { AmbientPrefs.changeStrength(it) }
                        AmbientSlider("Spread", AmbientPrefs.spreadDp, 40..800, " dp") { AmbientPrefs.changeSpread(it) }
                        AmbientSlider(
                            if (android.os.Build.VERSION.SDK_INT >= 31) "Blur" else "Blur (needs Android 12+)",
                            AmbientPrefs.blurDp, 0..60, " dp",
                        ) { AmbientPrefs.changeBlur(it) }
                        AmbientSlider("Saturation", AmbientPrefs.saturation, 50..200, "%") { AmbientPrefs.changeSaturation(it) }
                        AmbientSlider("Update rate", AmbientPrefs.fps, 4..24, " fps") { AmbientPrefs.changeFps(it) }
                        SettingRow(
                            icon = { ResIcon(R.drawable.ic_play, onIcon, 22.dp) }, label = "Smooth frames",
                            trailing = { Switch(checked = AmbientPrefs.smooth, onCheckedChange = null) },
                        ) { AmbientPrefs.changeSmooth(!AmbientPrefs.smooth) }
                    }
                    Page.MORE -> {
                        if (h.downloadEnabled()) Row0(R.drawable.ic_download, onIcon, "Download", null) { onDismiss(); h.onDownload() }
                        Row0(R.drawable.ic_library, onIcon, "Downloaded videos", null) { onDismiss(); h.onDownloadsList() }
                        Row0(R.drawable.ic_volume_up, onIcon, "Audio FX", null) { onDismiss(); h.onAudioFx() }
                    }
                }
            }
        }
    }
}

private fun fmtSpeed(s: Float) = if (s == s.toInt().toFloat()) "${s.toInt()}x" else "${s}x"

@Composable
private fun Row0(res: Int, tint: androidx.compose.ui.graphics.Color, label: String, value: String?, chevron: Boolean = false, onClick: () -> Unit) {
    SettingRow(
        icon = { ResIcon(res, tint, 22.dp) }, label = label,
        trailing = {
            if (value != null) Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            if (chevron) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        onClick = onClick,
    )
}

@Composable
private fun SettingRow(icon: @Composable () -> Unit, label: String, trailing: @Composable () -> Unit = {}, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShapedIconBox(44.dp, MaterialTheme.colorScheme.secondaryContainer) { icon() }
        Spacer(Modifier.width(16.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        trailing()
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
        if (selected) Icon(Icons.Filled.Check, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun AmbientSlider(label: String, value: Int, range: IntRange, unit: String, onChange: (Int) -> Unit) {
    var v by remember { mutableFloatStateOf(value.toFloat()) }
    Column(Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text("${v.toInt()}$unit", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = v,
            onValueChange = { v = it; onChange(it.toInt()) },
            onValueChangeFinished = { AmbientPrefs.save() },
            valueRange = range.first.toFloat()..range.last.toFloat(),
        )
    }
}
