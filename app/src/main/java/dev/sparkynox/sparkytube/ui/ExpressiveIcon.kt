package dev.sparkynox.sparkytube.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** An icon on a soft cookie-shaped tile, the Material 3 Expressive look. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ShapedIconBox(
    size: Dp = 44.dp,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        Modifier.size(size).clip(MaterialShapes.Cookie4Sided.toShape()).background(container),
        contentAlignment = Alignment.Center,
        content = content,
    )
}
