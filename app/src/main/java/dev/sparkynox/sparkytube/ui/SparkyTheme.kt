/*
 * Ported from LunoMusic's theme, which follows the ArchiveTune approach (GPL-3.0, (c) Rukamori,
 * github.com/rukamori): Material You colors, expressive motion scheme and Poppins typography.
 * The palette is SparkyTube's own (see res/values/colors.xml and values-night/colors.xml).
 */
package dev.sparkynox.sparkytube.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import dev.sparkynox.sparkytube.settings.SettingsPrefs
import dev.sparkynox.sparkytube.ui.at.AppTypography

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFFF3B3B),
    onPrimary = Color(0xFF3A0000),
    primaryContainer = Color(0xFF7A1F1F),
    onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = Color(0xFFE7BDB8),
    onSecondary = Color(0xFF442926),
    secondaryContainer = Color(0xFF5D3F3B),
    onSecondaryContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0A0A0A),
    onBackground = Color(0xFFF5F5F5),
    surface = Color(0xFF0A0A0A),
    onSurface = Color(0xFFF5F5F5),
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFF9A9A9A),
    surfaceContainerLowest = Color(0xFF050505),
    surfaceContainerLow = Color(0xFF111111),
    surfaceContainer = Color(0xFF141414),
    surfaceContainerHigh = Color(0xFF1C1C1C),
    surfaceContainerHighest = Color(0xFF262626),
    outline = Color(0xFF6E6E6E),
    outlineVariant = Color(0xFF2E2E2E),
    error = Color(0xFFFFB4AB),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFFD92626),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF3C4C4),
    onPrimaryContainer = Color(0xFF410002),
    secondary = Color(0xFF775652),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDAD6),
    onSecondaryContainer = Color(0xFF2C1512),
    background = Color(0xFFF2F4F7),
    onBackground = Color(0xFF121214),
    surface = Color(0xFFF2F4F7),
    onSurface = Color(0xFF121214),
    surfaceVariant = Color(0xFFE8EBF0),
    onSurfaceVariant = Color(0xFF565B65),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F8FA),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFE8EBF0),
    surfaceContainerHighest = Color(0xFFDDE1E7),
    outline = Color(0xFF7B8089),
    outlineVariant = Color(0xFFD6DAE1),
    error = Color(0xFFBA1A1A),
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SparkyTheme(fill: Boolean = true, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    // Settings > Appearance sets the app's night mode, which isSystemInDarkTheme() follows
    val dark = isSystemInDarkTheme()
    // same rule as the rest of the app: dynamic color only when the user opted in (Android 12+)
    val dynamic = SettingsPrefs.isDynamicColorEnabled(ctx) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val scheme = when {
        dynamic && dark -> dynamicDarkColorScheme(ctx)
        dynamic -> dynamicLightColorScheme(ctx)
        dark -> DarkScheme
        else -> LightScheme
    }

    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = MotionScheme.expressive(),
        typography = AppTypography,
    ) {
        Surface(
            modifier = if (fill) Modifier.fillMaxSize() else Modifier,
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            content = content,
        )
    }
}
