package eu.advantly.ui.phone.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

private val LocalAdvantlyColors = staticCompositionLocalOf { DarkAdvantlyColors }
private val LocalAdvantlyTypography = staticCompositionLocalOf<AdvantlyTypography?> { null }

/**
 * Advantly-Theme: eigene Tokens ([AdvantlyTheme.colors], [AdvantlyTheme.typography]) plus passendes
 * Material-3-Schema für Standardkomponenten. Live-Screens rufen es mit `darkTheme = true` auf (04 §5.2).
 */
@Composable
fun AdvantlyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkAdvantlyColors else LightAdvantlyColors
    val typography = advantlyTypography()
    val scheme =
        remember(colors) {
            if (colors.isDark) {
                darkColorScheme(
                    primary = colors.brand,
                    onPrimary = colors.onBrand,
                    secondary = colors.teamThem,
                    background = colors.bg,
                    onBackground = colors.text,
                    surface = colors.surface,
                    onSurface = colors.text,
                    surfaceVariant = colors.surface2,
                    onSurfaceVariant = colors.text2,
                    surfaceContainer = colors.surface2,
                    outline = colors.line,
                    error = colors.danger,
                )
            } else {
                lightColorScheme(
                    primary = colors.brand,
                    onPrimary = colors.onBrand,
                    secondary = colors.teamThem,
                    background = colors.bg,
                    onBackground = colors.text,
                    surface = colors.surface,
                    onSurface = colors.text,
                    surfaceVariant = colors.surface2,
                    onSurfaceVariant = colors.text2,
                    surfaceContainer = colors.surface2,
                    outline = colors.line,
                    error = colors.danger,
                )
            }
        }
    CompositionLocalProvider(
        LocalAdvantlyColors provides colors,
        LocalAdvantlyTypography provides typography,
    ) {
        MaterialTheme(colorScheme = scheme, typography = typography.toMaterial(), content = content)
    }
}

object AdvantlyTheme {
    val colors: AdvantlyColors
        @Composable @ReadOnlyComposable
        get() = LocalAdvantlyColors.current

    val typography: AdvantlyTypography
        @Composable @ReadOnlyComposable
        get() = checkNotNull(LocalAdvantlyTypography.current) { "AdvantlyTheme { … } fehlt" }
}
