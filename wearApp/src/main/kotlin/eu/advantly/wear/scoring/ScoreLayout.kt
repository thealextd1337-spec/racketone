package eu.advantly.wear.scoring

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Geometrie der Score-Seite auf runden Displays (reine Rechnung, Einheit beliebig – dp oder px –, JVM-getestet): Wie
 * breit darf eine Zeile an einer bestimmten Höhe sein, damit der runde Rand nichts abschneidet? Genutzt für die
 * Team-/Namenszeilen über bzw. unter den Ziffern (Feldtest 2026-10-16: lange Namen liefen über den Rand).
 */
object ScoreLayout {
    /** Abstand zum runden Rand je Seite. */
    const val EDGE_MARGIN_DP = 4f

    /** Breite einer waagerechten Linie auf Höhe [y] (von oben) in einem runden Display mit [diameter], abzüglich [margin] je Seite. */
    fun chordWidth(
        diameter: Float,
        y: Float,
        margin: Float = EDGE_MARGIN_DP,
    ): Float {
        val r = diameter / 2
        val dy = min(abs(y - r), r)
        return max(0f, 2 * sqrt(r * r - dy * dy) - 2 * margin)
    }
}
