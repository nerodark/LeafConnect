package leafconnect

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The brand palette, in Compose instead of Android color resources. The values
 * are the ones the app shipped with (`values/colors.xml` and
 * `values-night/colors.xml`), so the night palette keeps the same relationships:
 * brighter greens on a near-black green-cast surface, and mode-aware banners.
 */
@Immutable
data class LeafPalette(
    val green: Color,
    val greenDark: Color,
    val accent: Color,
    val windowBackground: Color,
    val iconTint: Color,
    val textSecondary: Color,
    val bannerOkBackground: Color,
    val bannerOkForeground: Color,
    val bannerFailBackground: Color,
    val bannerFailForeground: Color,
)

private val DayPalette = LeafPalette(
    green = Color(0xFF1B5E20),
    greenDark = Color(0xFF10331A),
    accent = Color(0xFF66BB6A),
    windowBackground = Color(0xFFFFFFFF),
    iconTint = Color(0xFF1B5E20),
    textSecondary = Color(0xFF757575),
    bannerOkBackground = Color(0xFFC8E6C9),
    bannerOkForeground = Color(0xFF1B5E20),
    bannerFailBackground = Color(0xFFFFCDD2),
    bannerFailForeground = Color(0xFFC62828),
)

private val NightPalette = LeafPalette(
    // Slightly brighter greens: #1B5E20 is too dark to read on the night surface.
    green = Color(0xFF2E7D32),
    greenDark = Color(0xFF16301F),
    accent = Color(0xFF81C784),
    windowBackground = Color(0xFF0E1411),
    iconTint = Color(0xFF8FD08A),
    textSecondary = Color(0xFF9EB0A6),
    bannerOkBackground = Color(0xFF22452C),
    bannerOkForeground = Color(0xFFA5D6A7),
    bannerFailBackground = Color(0xFF4B2724),
    bannerFailForeground = Color(0xFFF3B6AE),
)

val LocalLeafPalette = staticCompositionLocalOf { DayPalette }

@Composable
fun LeafConnectTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val palette = if (darkTheme) NightPalette else DayPalette
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = palette.green,
            onPrimary = Color(0xFF06140B),
            primaryContainer = palette.greenDark,
            onPrimaryContainer = palette.accent,
            secondary = palette.accent,
            onSecondary = palette.greenDark,
            background = palette.windowBackground,
            onBackground = Color(0xFFE6EFE8),
            surface = palette.windowBackground,
            onSurface = Color(0xFFE6EFE8),
            onSurfaceVariant = palette.textSecondary,
            error = Color(0xFFEFB8B0),
        )
    } else {
        lightColorScheme(
            primary = palette.green,
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = palette.accent,
            onPrimaryContainer = palette.greenDark,
            secondary = palette.accent,
            onSecondary = palette.greenDark,
            background = palette.windowBackground,
            onBackground = Color(0xFF1B241F),
            surface = palette.windowBackground,
            onSurface = Color(0xFF1B241F),
            onSurfaceVariant = palette.textSecondary,
            error = palette.bannerFailForeground,
        )
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalLeafPalette provides palette) {
        // Typography and shapes stay at the Material 3 defaults; the palette is
        // the app's own.
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
