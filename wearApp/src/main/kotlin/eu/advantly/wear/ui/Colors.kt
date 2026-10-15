package eu.advantly.wear.ui

import androidx.compose.ui.graphics.Color
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport

// Farb-Tokens aus 04 §5.2 (Dark): Team Wir = Orange, Team Gegner = Blau, Live-Screen schwarz, Hinweise Amber.
val TeamUs = Color(0xFFFF6A1A)
val TeamThem = Color(0xFF5B9BFF)
val HintAmber = Color(0xFFFFB020)
val TextSecondary = Color(0xFF9AA1AD)
val AmbientDigits = Color.White.copy(alpha = 0.65f)
val ErrorRed = Color(0xFFFF4D4F)

fun teamColor(side: Side): Color = if (side == Side.A) TeamUs else TeamThem

/** Sport-Akzent (04 §5.2) – nur zusammen mit Name, nie allein zur Unterscheidung. */
fun sportColor(sport: Sport): Color =
    when (sport) {
        Sport.TENNIS -> Color(0xFFC8E62E)
        Sport.PADEL -> Color(0xFF22C3E6)
        Sport.PICKLEBALL -> Color(0xFFFF5FA2)
        Sport.BADMINTON -> Color(0xFFA78BFA)
        Sport.TABLE_TENNIS -> Color(0xFF34D399)
    }
