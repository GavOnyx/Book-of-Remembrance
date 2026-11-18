package com.calonyx.app.ui.theme

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.calonyx.app.data.CalOnyxRepository
import com.calonyx.app.data.ThemeSettings

/**
 * Launcher icon follows the theme (v0.21.6). The launcher reads static
 * APK resources, so the app can't paint its icon at runtime; instead
 * the manifest declares three launcher components — the main activity
 * (Light icon) plus aliases for Dark and Original — and the app enables
 * exactly one of them. The art is 78dp wide on the 108dp layer: bigger
 * than the 66dp safe zone, so a strict circle mask clips the page
 * corners (accepted: the clipped parts are background).
 */

/** The three launcher components, one per built-in theme. */
enum class LauncherIconVariant { LIGHT, DARK, ORIGINAL }

/**
 * Which icon variant a theme maps to. Presets map directly; a Custom
 * theme takes the most similar icon (see [mostSimilarIcon]).
 */
fun launcherIconVariantFor(theme: ThemeSettings): LauncherIconVariant =
    when (theme.selectedThemeId) {
        CalOnyxRepository.THEME_LIGHT -> LauncherIconVariant.LIGHT
        CalOnyxRepository.THEME_DARK -> LauncherIconVariant.DARK
        CalOnyxRepository.THEME_ORIGINAL -> LauncherIconVariant.ORIGINAL
        else -> mostSimilarIcon(theme.background, theme.primary)
    }

/**
 * Custom theme: the icon most similar to it. A light background takes
 * the Light icon; a dark background takes Dark or Original, whichever
 * accent color the custom accent is closer to. Background light/dark
 * uses the same 0.5 luminance threshold as the status-bar contrast in
 * [CalOnyxTheme].
 */
fun mostSimilarIcon(backgroundArgb: Int, accentArgb: Int): LauncherIconVariant {
    if (Color(backgroundArgb).luminance() > 0.5f) return LauncherIconVariant.LIGHT
    val darkAccent = CalOnyxRepository.DARK_THEME_PRESET.primary
    val originalAccent = CalOnyxRepository.ORIGINAL_THEME_PRESET.primary
    return if (colorDistanceSq(accentArgb, darkAccent) <= colorDistanceSq(accentArgb, originalAccent))
        LauncherIconVariant.DARK else LauncherIconVariant.ORIGINAL
}

/** Squared Euclidean distance in RGB (alpha ignored); smaller is closer. */
private fun colorDistanceSq(a: Int, b: Int): Long {
    val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
    val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
    val db = (a and 0xFF) - (b and 0xFF)
    return (dr * dr + dg * dg + db * db).toLong()
}

/**
 * Enables the component carrying [variant]'s icon and disables the
 * other two. Idempotent — calling it with the already-active variant
 * just re-applies the same states. DONT_KILL_APP: switching never
 * restarts the app; the launcher picks the new icon up on its own.
 * Note the usual alias caveat: switching can flicker or duplicate the
 * icon on some launchers, and the icon may need a re-add if the
 * launcher drops it.
 */
fun applyLauncherIcon(context: Context, variant: LauncherIconVariant) {
    val pm = context.packageManager
    val pkg = context.packageName
    val components = mapOf(
        ComponentName(pkg, "$pkg.ui.MainActivity") to (variant == LauncherIconVariant.LIGHT),
        ComponentName(pkg, "$pkg.IconDark") to (variant == LauncherIconVariant.DARK),
        ComponentName(pkg, "$pkg.IconOriginal") to (variant == LauncherIconVariant.ORIGINAL)
    )
    components.forEach { (component, enabled) ->
        pm.setComponentEnabledSetting(
            component,
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
    }
}
