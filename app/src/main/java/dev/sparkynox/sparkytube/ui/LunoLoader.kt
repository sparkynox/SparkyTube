package dev.sparkynox.sparkytube.ui

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The Material 3 Expressive loading indicator (the shape-morphing one the Play Store uses). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LunoLoader(modifier: Modifier = Modifier, size: Dp = 48.dp, color: Color = MaterialTheme.colorScheme.primary) {
    LoadingIndicator(modifier = modifier.then(Modifier.size(size)), color = color)
}
