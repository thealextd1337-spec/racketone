package eu.advantly.ui.phone.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Farb-Tokens aus 04 §5.2 (Dark-first). Teamfarbe = *wer* punktet, Sportfarbe = *was* gespielt wird.
 * Live-Screens nutzen unabhängig vom System-Theme [liveBackground] (#000).
 */
@Immutable
data class AdvantlyColors(
    val isDark: Boolean,
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val line: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    /** Marke „Clay“ = Team Wir (UI). */
    val brand: Color,
    /** Label auf Marken-/CTA-Flächen (dunkel, 6.8:1). */
    val onBrand: Color,
    val teamUs: Color,
    val teamUsChart: Color,
    val teamThem: Color,
    val teamThemChart: Color,
    val sportTennis: Color,
    val sportPadel: Color,
    val sportPickleball: Color,
    val sportBadminton: Color,
    val sportTableTennis: Color,
    /** Breakball/Satzball/Matchball (10.7:1 auf Schwarz). */
    val hint: Color,
    val onHint: Color,
    val success: Color,
    val danger: Color,
    val liveBackground: Color = Color.Black,
) {
    /** Getönte Team-Fläche (~13 %, 04 §4.3.1) – nie vollflächig gesättigt. */
    val teamUsTint: Color get() = teamUs.copy(alpha = 0.13f)
    val teamThemTint: Color get() = teamThem.copy(alpha = 0.13f)
}

val DarkAdvantlyColors =
    AdvantlyColors(
        isDark = true,
        bg = Color(0xFF0B0C0F),
        surface = Color(0xFF15171C),
        surface2 = Color(0xFF1E2128),
        line = Color(0xFF2A2E37),
        text = Color(0xFFF5F6F7),
        text2 = Color(0xFF9AA1AD),
        text3 = Color(0xFF6E7581),
        brand = Color(0xFFFF6A1A),
        onBrand = Color(0xFF0B0C0F),
        teamUs = Color(0xFFFF6A1A),
        teamUsChart = Color(0xFFE2621B),
        teamThem = Color(0xFF5B9BFF),
        teamThemChart = Color(0xFF4A88F2),
        sportTennis = Color(0xFFC8E62E),
        sportPadel = Color(0xFF22C3E6),
        sportPickleball = Color(0xFFFF5FA2),
        sportBadminton = Color(0xFFA78BFA),
        sportTableTennis = Color(0xFF34D399),
        hint = Color(0xFFFFB020),
        onHint = Color(0xFF000000),
        success = Color(0xFF3DDC84),
        danger = Color(0xFFFF4D4F),
    )

val LightAdvantlyColors =
    AdvantlyColors(
        isDark = false,
        bg = Color(0xFFF6F6F4),
        surface = Color(0xFFFFFFFF),
        surface2 = Color(0xFFEEEEEA),
        line = Color(0xFFDADAD4),
        text = Color(0xFF121317),
        text2 = Color(0xFF50555F),
        text3 = Color(0xFF6E7581),
        brand = Color(0xFFC2410C),
        onBrand = Color(0xFFFFFFFF),
        teamUs = Color(0xFFC2410C),
        teamUsChart = Color(0xFFC2410C),
        teamThem = Color(0xFF2563EB),
        teamThemChart = Color(0xFF2563EB),
        sportTennis = Color(0xFF6B7F00),
        sportPadel = Color(0xFF0E7490),
        sportPickleball = Color(0xFFBE185D),
        sportBadminton = Color(0xFF6D28D9),
        sportTableTennis = Color(0xFF047857),
        hint = Color(0xFFB45309),
        onHint = Color(0xFFFFFFFF),
        success = Color(0xFF15803D),
        danger = Color(0xFFDC2626),
    )
