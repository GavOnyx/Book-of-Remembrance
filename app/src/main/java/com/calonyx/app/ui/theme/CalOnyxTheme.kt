package com.calonyx.app.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.isSpecified
import androidx.core.view.WindowCompat
import com.calonyx.app.data.CalOnyxRepository
import com.calonyx.app.data.ThemeSettings

/**
 * Scales every Material3 text style by [scale], preserving the relative
 * size relationships (headers stay proportionally larger than body text).
 */
fun scaledTypography(scale: Float): Typography {
    if (scale == 1f) return Typography()
    val base = Typography()
    fun TextStyle.scaled(): TextStyle =
        if (fontSize.isSpecified) copy(fontSize = fontSize * scale) else this
    return Typography(
        displayLarge = base.displayLarge.scaled(),
        displayMedium = base.displayMedium.scaled(),
        displaySmall = base.displaySmall.scaled(),
        headlineLarge = base.headlineLarge.scaled(),
        headlineMedium = base.headlineMedium.scaled(),
        headlineSmall = base.headlineSmall.scaled(),
        titleLarge = base.titleLarge.scaled(),
        titleMedium = base.titleMedium.scaled(),
        titleSmall = base.titleSmall.scaled(),
        bodyLarge = base.bodyLarge.scaled(),
        bodyMedium = base.bodyMedium.scaled(),
        bodySmall = base.bodySmall.scaled(),
        labelLarge = base.labelLarge.scaled(),
        labelMedium = base.labelMedium.scaled(),
        labelSmall = base.labelSmall.scaled()
    )
}

/**
 * Dark "onyx" theme. Colors come from the seeded ThemeSettings row in the
 * database (user-customizable in a later milestone); falls back to the
 * default palette before the first seed completes.
 */
@Composable
fun CalOnyxTheme(
    repository: CalOnyxRepository,
    content: @Composable () -> Unit
) {
    val theme by repository.observeTheme().collectAsState(initial = null)
    val appSettings by repository.observeAppSettings().collectAsState(initial = null)
    val scale = appSettings?.textScale ?: 1f
    val t = theme ?: ThemeSettings(
        background = 0xFF121212.toInt(),
        foreground = 0xFF1E1E1E.toInt(),
        primary = 0xFFBB86FC.toInt(),
        secondary = 0xFF03DAC6.toInt(),
        tertiary = 0xFFCF6679.toInt(),
        font = 0xFFE0E0E0.toInt()
    )
    val scheme = darkColorScheme(
        background = Color(t.background),
        surface = Color(t.foreground),
        surfaceVariant = Color(t.foreground),
        surfaceContainerLow = Color(t.foreground),
        primary = Color(t.primary),
        secondary = Color(t.secondary),
        // Tertiary is retired: everything that used it now renders as Accent.
        tertiary = Color(t.primary),
        onBackground = Color(t.font),
        onSurface = Color(t.font),
        onSurfaceVariant = Color(t.font).copy(alpha = 0.7f),
        onPrimary = Color(0xFF000000),
        onSecondary = Color(0xFF000000),
        outline = Color(t.font).copy(alpha = 0.25f)
    )
    // Edge-to-edge (M14): the system bars are transparent, so drive the bar
    // icon contrast from the theme background's luminance — not the system
    // night mode, which the user-customizable app theme is independent of.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val lightBackground = Color(t.background).luminance() > 0.5f
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = lightBackground
                isAppearanceLightNavigationBars = lightBackground
            }
        }
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = scaledTypography(scale),
        content = content
    )
}
