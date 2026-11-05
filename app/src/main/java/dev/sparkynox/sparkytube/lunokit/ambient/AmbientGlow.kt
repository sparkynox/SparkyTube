package dev.sparkynox.sparkytube.lunokit.ambient

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import dev.sparkynox.sparkytube.lunokit.lyrics.coverColor
import dev.sparkynox.sparkytube.settings.SettingsPrefs

// soft glow in the cover's colour behind the watch page, breathes slowly. Settings > Ambient glow turns it off
@Composable
fun Modifier.ambientGlow(videoId: String?): Modifier {
    val ctx = LocalContext.current
    val on = remember(videoId) { SettingsPrefs.isAmbientEnabled(ctx) }
    if (!on || videoId == null) return this

    var tint by remember(videoId) { mutableStateOf(Color.Transparent) }
    LaunchedEffect(videoId) {
        coverColor(ctx, "https://i.ytimg.com/vi/$videoId/mqdefault.jpg")?.let { tint = it }
    }
    val shown by animateColorAsState(tint, tween(900), label = "ambient")
    val breathe by rememberInfiniteTransition(label = "breathe").animateFloat(
        initialValue = 0.40f, targetValue = 0.58f,
        animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Reverse),
        label = "breatheAlpha",
    )
    return this.drawBehind {
        drawRect(
            Brush.verticalGradient(
                listOf(shown.copy(alpha = breathe), shown.copy(alpha = breathe * 0.35f), Color.Transparent),
                endY = size.height * 0.75f,
            )
        )
    }
}
