package eu.advantly.ui.phone.designsystem

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.barlow_condensed_bold
import eu.advantly.ui.phone.resources.barlow_condensed_medium
import eu.advantly.ui.phone.resources.barlow_condensed_semibold
import eu.advantly.ui.phone.resources.inter_bold
import eu.advantly.ui.phone.resources.inter_medium
import eu.advantly.ui.phone.resources.inter_regular
import eu.advantly.ui.phone.resources.inter_semibold
import org.jetbrains.compose.resources.Font

/** Tabellarische Ziffern überall, wo Werte sich ändern (04 §5.4) – kein Springen. */
const val TABULAR_FIGURES = "tnum"

/**
 * Typo-Rollen aus 04 §5.4. Ziffern: **Barlow Condensed**, Text: **Inter** – beide OFL, gebündelt als
 * Compose-Resources (Lizenzen unter `composeResources/files/licenses/`).
 */
@Immutable
data class AdvantlyTypography(
    /** Live-Punkte, 96–120 pt Bold. Größe wird im Panel an die Fläche angepasst. */
    val scoreXl: TextStyle,
    /** Games, Satztabelle: 28–40 pt SemiBold, tabellarisch. */
    val scoreM: TextStyle,
    /** Kleine Ziffern (Satztabelle, Matchdauer). */
    val scoreS: TextStyle,
    val headline: TextStyle,
    val title: TextStyle,
    val body: TextStyle,
    val label: TextStyle,
    /** Team-Labels WIR/GEGNER, Hinweis-Pill: Versalien, weit gesperrt. */
    val overline: TextStyle,
    val caption: TextStyle,
)

@Composable
fun barlowCondensedFamily(): FontFamily =
    FontFamily(
        Font(Res.font.barlow_condensed_medium, FontWeight.Medium),
        Font(Res.font.barlow_condensed_semibold, FontWeight.SemiBold),
        Font(Res.font.barlow_condensed_bold, FontWeight.Bold),
    )

@Composable
fun interFamily(): FontFamily =
    FontFamily(
        Font(Res.font.inter_regular, FontWeight.Normal),
        Font(Res.font.inter_medium, FontWeight.Medium),
        Font(Res.font.inter_semibold, FontWeight.SemiBold),
        Font(Res.font.inter_bold, FontWeight.Bold),
    )

@Composable
fun advantlyTypography(): AdvantlyTypography {
    val digits = barlowCondensedFamily()
    val text = interFamily()
    return AdvantlyTypography(
        scoreXl =
            TextStyle(
                fontFamily = digits,
                fontWeight = FontWeight.Bold,
                fontSize = 112.sp,
                lineHeight = 112.sp,
                letterSpacing = (-0.01).em,
                fontFeatureSettings = TABULAR_FIGURES,
            ),
        scoreM =
            TextStyle(
                fontFamily = digits,
                fontWeight = FontWeight.SemiBold,
                fontSize = 28.sp,
                lineHeight = 30.sp,
                fontFeatureSettings = TABULAR_FIGURES,
            ),
        scoreS =
            TextStyle(
                fontFamily = digits,
                fontWeight = FontWeight.SemiBold,
                fontSize = 20.sp,
                lineHeight = 22.sp,
                letterSpacing = 0.02.em,
                fontFeatureSettings = TABULAR_FIGURES,
            ),
        headline = TextStyle(fontFamily = text, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp),
        title = TextStyle(fontFamily = text, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
        body = TextStyle(fontFamily = text, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp),
        label = TextStyle(fontFamily = text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp),
        overline =
            TextStyle(
                fontFamily = text,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.09.em,
            ),
        caption =
            TextStyle(
                fontFamily = text,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontFeatureSettings = TABULAR_FIGURES,
            ),
    )
}

/** Material-3-Typografie mit Inter, damit M3-Komponenten (Menüs, Buttons) zur Marke passen. */
fun AdvantlyTypography.toMaterial(): Typography =
    Typography(
        displayLarge = headline.copy(fontSize = 57.sp, lineHeight = 64.sp),
        displayMedium = headline.copy(fontSize = 45.sp, lineHeight = 52.sp),
        displaySmall = headline.copy(fontSize = 36.sp, lineHeight = 44.sp),
        headlineLarge = headline.copy(fontSize = 32.sp, lineHeight = 40.sp),
        headlineMedium = headline,
        headlineSmall = headline.copy(fontSize = 24.sp, lineHeight = 30.sp),
        titleLarge = title.copy(fontSize = 22.sp, lineHeight = 28.sp),
        titleMedium = title.copy(fontSize = 16.sp, lineHeight = 22.sp),
        titleSmall = label,
        bodyLarge = body,
        bodyMedium = body.copy(fontSize = 14.sp, lineHeight = 20.sp),
        bodySmall = caption.copy(fontFeatureSettings = null),
        labelLarge = label,
        labelMedium = label.copy(fontSize = 13.sp, lineHeight = 16.sp),
        labelSmall = caption.copy(fontFeatureSettings = null),
    )
