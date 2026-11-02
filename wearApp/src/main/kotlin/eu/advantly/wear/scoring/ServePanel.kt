package eu.advantly.wear.scoring

import androidx.compose.ui.geometry.Rect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Lage der Aufschlag-Auswahl in px (aus [ServePanel.geometry]).
 *
 * @property buttons sichtbare Tasten von oben nach unten (je Wert aus `ServeBarUi.options`)
 * @property scoreRight rechter Rand der Zählflächen „Wir“/„Gegner“ und des Spielstands links
 * @property hitTop Oberkante der Trefferzone der Tasten (oberste Taste + Schutz)
 * @property hitBottom Unterkante der Trefferzone der Tasten (unterste Taste + Schutz)
 */
data class ServePanelGeometry(
    val buttons: List<Rect>,
    val scoreRight: Float,
    val hitTop: Float,
    val hitBottom: Float,
) {
    /** Linker Rand der Tastenspalte (alle Tasten sind gleich breit). */
    val buttonsLeft: Float get() = buttons.firstOrNull()?.left ?: scoreRight
}

/**
 * Aufschlag-Auswahl nach dem Punkt (Plan 0.5.1 §4a): Der Spielstand rückt nach links, rechts stehen die Werte als große
 * Tasten übereinander (je [BUTTON_WIDTH_FRACTION] der Breite, mindestens [MIN_BUTTON_DP] bzw. [BUTTON_HEIGHT_FRACTION]
 * der Höhe). Links zählen die Hälften weiter wie bisher ([TapZones]: oben „Wir“, unten „Gegner“, gleiche Höhen), nur
 * schmaler; zwischen Zählflächen und Tasten liegt ein Schutzstreifen ([GUARD_DP]). Die Tastenspalte sitzt so weit rechts,
 * wie der runde Rand an ihren äußeren Ecken erlaubt (abgerundete Ecken [CORNER_DP] berücksichtigt). Ihre waagerechte
 * Lage hängt nicht von der Zahl der Tasten ab – der Spielstand gleitet immer an dieselbe Stelle. Reine Geometrie
 * (JVM-getestet); Darstellung und Trefferprüfung nutzen dieselbe Funktion.
 */
object ServePanel {
    const val BUTTON_WIDTH_FRACTION = 0.45f
    const val BUTTON_HEIGHT_FRACTION = 0.25f
    const val MIN_BUTTON_DP = 56f
    const val BUTTON_GAP_DP = 4f
    const val GUARD_DP = 6f
    const val CORNER_DP = 12f

    /** Geometrie für [count] Tasten auf einem Display von [width] × [height] px bei [density] px/dp. */
    fun geometry(
        width: Float,
        height: Float,
        density: Float,
        count: Int,
    ): ServePanelGeometry {
        val radius = min(width, height) / 2
        val cx = width / 2
        val cy = height / 2
        val buttonHeight = max(height * BUTTON_HEIGHT_FRACTION, MIN_BUTTON_DP * density)
        val gap = BUTTON_GAP_DP * density
        // waagerechte Lage immer für zwei Tasten: äußerster Punkt der Eckrundung liegt im Kreis (abzüglich Randabstand)
        val outerY = cy - buttonHeight - gap / 2
        val cornerInset = CORNER_DP * density * (1 - 1 / sqrt(2f))
        val inner = radius - ScoreLayout.EDGE_MARGIN_DP * density
        val dy = cy - outerY - cornerInset
        val right = min(width, cx + sqrt(max(0f, inner * inner - dy * dy)) + cornerInset)
        val left = right - width * BUTTON_WIDTH_FRACTION
        val n = count.coerceAtLeast(0)
        val top = cy - (n * buttonHeight + (n - 1).coerceAtLeast(0) * gap) / 2
        val buttons = List(n) { i -> Rect(left, top + i * (buttonHeight + gap), right, top + i * (buttonHeight + gap) + buttonHeight) }
        val guard = GUARD_DP * density
        return ServePanelGeometry(
            buttons = buttons,
            scoreRight = left - guard,
            hitTop = (buttons.firstOrNull()?.top ?: cy) - guard,
            hitBottom = (buttons.lastOrNull()?.bottom ?: cy) + guard,
        )
    }

    /** Wohin gehört eine Berührung bei ([x], [y]): Taste, Schutz (rechte Spalte, Streifen davor) oder Zählflächen links. */
    fun hit(
        x: Float,
        y: Float,
        g: ServePanelGeometry,
    ): StripHit =
        when {
            x < g.scoreRight -> StripHit.OUTSIDE
            x >= g.buttonsLeft && y in g.hitTop..g.hitBottom && g.buttons.isNotEmpty() -> StripHit.STRIP
            else -> StripHit.GUARD
        }

    /** Taste (0 until Anzahl) unter ([x], [y]); Lücken zwischen zwei Tasten werden in der Mitte geteilt; sonst `null`. */
    fun button(
        x: Float,
        y: Float,
        g: ServePanelGeometry,
    ): Int? {
        if (hit(x, y, g) != StripHit.STRIP) return null
        val index = g.buttons.zipWithNext().indexOfFirst { (upper, lower) -> y <= (upper.bottom + lower.top) / 2 }
        return if (index < 0) g.buttons.lastIndex else index
    }
}
