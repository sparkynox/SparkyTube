package dev.sparkynox.sparkytube.lunokit.glass

import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/** Same colour, but see-through while Luno Glass is on. */
@Composable
fun glassColor(base: Color, alpha: Float = 0.38f): Color = if (LunoGlass.active) base.copy(alpha = alpha) else base

/** The thin light edge glass panels get. Null when glass is off. */
@Composable
fun glassBorder(): BorderStroke? = if (LunoGlass.active) BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)) else null

/** The video's own thumbnail, blown up and blurred, sitting behind the whole watch page. */
@Composable
fun GlassBackdrop(videoId: String?) {
    if (!LunoGlass.active || videoId.isNullOrBlank() || dev.sparkynox.sparkytube.lunokit.ambient.AmbientPrefs.enabled) return
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AsyncImage(
            model = "https://i.ytimg.com/vi/$videoId/mqdefault.jpg",
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().blur(56.dp, BlurredEdgeTreatment.Unbounded),
        )
        // keeps text readable on bright thumbnails
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.5f)))
    }
}

/** Blurs everything behind the window this is called from (a sheet runs in its own window). */
@Composable
fun GlassWindowBlur(radiusDp: Int = 32) {
    if (!LunoGlass.active) return
    val view = LocalView.current
    LaunchedEffect(view) {
        val root = view.rootView
        val lp = root.layoutParams as? WindowManager.LayoutParams ?: return@LaunchedEffect
        val wm = view.context.getSystemService(WindowManager::class.java) ?: return@LaunchedEffect
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        lp.blurBehindRadius = (radiusDp * view.resources.displayMetrics.density).toInt()
        runCatching { wm.updateViewLayout(root, lp) }
    }
}

/** ModalBottomSheet that turns into frosted glass when Luno Glass is on. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LunoSheet(onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = glassColor(MaterialTheme.colorScheme.surfaceContainerLow, 0.62f),
        scrimColor = if (LunoGlass.active) Color.Black.copy(alpha = 0.16f) else BottomSheetDefaults.ScrimColor,
    ) {
        GlassWindowBlur()
        content()
    }
}
