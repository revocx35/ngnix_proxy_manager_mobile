package io.github.revocx35.npmmobile.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.github.revocx35.npmmobile.data.ThemeMode

// Brand scheme generated around NPM's orange-red (#F15833); used when dynamic color is off or unavailable.
private val Light = lightColorScheme(
    primary = Color(0xFFA43A1A), onPrimary = Color.White, primaryContainer = Color(0xFFFFDBD1), onPrimaryContainer = Color(0xFF3B0900),
    secondary = Color(0xFF77574E), onSecondary = Color.White, secondaryContainer = Color(0xFFFFDBD1), onSecondaryContainer = Color(0xFF2C150F),
    tertiary = Color(0xFF6C5D2F), onTertiary = Color.White, tertiaryContainer = Color(0xFFF5E1A7), onTertiaryContainer = Color(0xFF231B00),
    background = Color(0xFFFFF8F6), onBackground = Color(0xFF231917), surface = Color(0xFFFFF8F6), onSurface = Color(0xFF231917),
    surfaceVariant = Color(0xFFF5DED8), onSurfaceVariant = Color(0xFF53433F), outline = Color(0xFF85736E), outlineVariant = Color(0xFFD8C2BC),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFFFF1ED), surfaceContainer = Color(0xFFFCEAE5),
    surfaceContainerHigh = Color(0xFFF6E4DF), surfaceContainerHighest = Color(0xFFF1DFDA),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFFB5A0), onPrimary = Color(0xFF5F1500), primaryContainer = Color(0xFF862207), onPrimaryContainer = Color(0xFFFFDBD1),
    secondary = Color(0xFFE7BDB2), onSecondary = Color(0xFF442A22), secondaryContainer = Color(0xFF5D4037), onSecondaryContainer = Color(0xFFFFDBD1),
    tertiary = Color(0xFFD8C58D), onTertiary = Color(0xFF3B2F05), tertiaryContainer = Color(0xFF534619), onTertiaryContainer = Color(0xFFF5E1A7),
    background = Color(0xFF1A110F), onBackground = Color(0xFFF1DFDA), surface = Color(0xFF1A110F), onSurface = Color(0xFFF1DFDA),
    surfaceVariant = Color(0xFF53433F), onSurfaceVariant = Color(0xFFD8C2BC), outline = Color(0xFFA08C87), outlineVariant = Color(0xFF53433F),
    surfaceContainerLowest = Color(0xFF140C0A), surfaceContainerLow = Color(0xFF231917), surfaceContainer = Color(0xFF271D1B),
    surfaceContainerHigh = Color(0xFF322825), surfaceContainerHighest = Color(0xFF3D3230),
)

/** Colors for states that Material has no role for (online/offline/warning). */
@Immutable
data class StatusColors(val online: Color, val offline: Color, val warning: Color, val disabled: Color, val onlineContainer: Color, val warningContainer: Color)

private val LightStatus = StatusColors(
    online = Color(0xFF1E7D32), offline = Color(0xFFBA1A1A), warning = Color(0xFF9A6200), disabled = Color(0xFF85736E),
    onlineContainer = Color(0xFFD4F5D6), warningContainer = Color(0xFFFFE8C2),
)
private val DarkStatus = StatusColors(
    online = Color(0xFF7FD98A), offline = Color(0xFFFFB4AB), warning = Color(0xFFFFC56B), disabled = Color(0xFFA08C87),
    onlineContainer = Color(0xFF1F3B22), warningContainer = Color(0xFF4A3300),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

@Composable
fun NpmTheme(mode: ThemeMode = ThemeMode.System, dynamicColor: Boolean = true, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val scheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
        dark -> Dark
        else -> Light
    }
    CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
