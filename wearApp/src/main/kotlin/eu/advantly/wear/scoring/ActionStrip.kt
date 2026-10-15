package eu.advantly.wear.scoring

import kotlin.math.max
import kotlin.math.min

/**
 * Lage der Aktionsleiste in px (aus [ActionStrip.geometry]).
 *
 * @property top Oberkante der Trefferzone
 * @property bottom Unterkante der Trefferzone
 * @property left linker Rand der Trefferzone
 * @property right rechter Rand der Trefferzone
 * @property guard Schutzstreifen ober- und unterhalb: Berührungen dort lösen weder Leiste noch Punkt aus
 * @property visualHeight Höhe der sichtbaren Leiste (mittig in der Trefferzone, etwas kleiner, damit die Ziffern frei
 *   bleiben – die Trefferzone ist größer als die Darstellung, wie bei Material-Mindestflächen üblich)
 * @property usArea Höhe der Zählfläche „Wir“, solange die Leiste steht (Ende der oberen Totzone bis Schutzstreifen)
 * @property themArea Höhe der Zählfläche „Gegner“, solange die Leiste steht (Schutzstreifen bis Displayunterkante)
 */
data class StripGeometry(
    val top: Float,
    val bottom: Float,
    val left: Float,
    val right: Float,
    val guard: Float,
    val visualHeight: Float,
    val usArea: Float,
    val themArea: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    /** Oberkante der sichtbaren Leiste (mittig in der Trefferzone). */
    val visualTop: Float get() = top + (height - visualHeight) / 2
}

/** Wo eine Berührung bei sichtbarer Aktionsleiste beginnt. */
enum class StripHit {
    /** In der Trefferzone der Leiste: nur die Leistenaktion, nie zählen oder abziehen. */
    STRIP,

    /** Im Schutzstreifen um die Leiste (bzw. seitlich daneben): ignoriert – weder Leiste noch Punkt. */
    GUARD,

    /** Außerhalb: normale Zählflächen ([TapZones]). */
    OUTSIDE,
}

/**
 * Aktionsleiste des Scoring-Screens (W2 „Bedienung“): ein waagerechter Streifen um das Mittelband (das ohnehin nicht
 * zählt, [TapZones.MIDDLE_BAND]), zwischen den Ziffern beider Hälften – auf runden Displays dort am breitesten.
 * Nur solange dort eine Aktion angeboten wird (Aufschlag-Leiste nach dem Punkt, „Rückgängig“ nach „Punkt abziehen“,
 * Hinweis „Aufschläger wählen“), gilt diese Geometrie; sonst bleibt alles wie bisher.
 *
 * Mindesthöhen (04 §1: 48 dp) gelten für **drei** Flächen gleichzeitig: die Trefferzone der Leiste und die beiden
 * Zählflächen „Wir“ (unter der oberen Totzone [TapZones.TOP_DEAD_ZONE]) und „Gegner“. Dazwischen liegt je ein
 * Schutzstreifen ([GUARD_DP]), in dem eine Berührung nichts tut – knapp neben der Leiste zählt also kein Punkt. Die
 * Leiste sitzt mittig; reicht oben der Platz nicht (kleine Displays, die Totzone kostet oben Höhe), rückt sie so weit
 * nach unten wie nötig. Reine Geometrie (JVM-getestet); Darstellung und Trefferprüfung nutzen dieselbe Funktion.
 */
object ActionStrip {
    /** Mindesthöhe der Trefferzone und beider Zählflächen. */
    const val MIN_TOUCH_DP = 48f

    /** Schutzstreifen ober- und unterhalb der Trefferzone (wird nur auf sehr kleinen Displays schmaler). */
    const val GUARD_DP = 6f

    /** Die sichtbare Leiste ist je Seite so viel flacher als die Trefferzone. */
    const val VISUAL_INSET_DP = 6f

    /** Anteil der Höhe, den die Trefferzone auf großen Displays hat. */
    const val MIN_HEIGHT_FRACTION = 0.16f

    /** Seitenrand links und rechts (Anteil der Breite); innerhalb davon liegen die Segmente. */
    const val SIDE_MARGIN = 0.06f

    /**
     * Geometrie für ein Display von [width] × [height] px bei [density] px/dp. Auf Displays ab etwa 178 dp Höhe sind alle
     * drei Flächen ≥ 48 dp; darunter werden erst die Schutzstreifen, dann die Leiste schmaler (die Zählflächen behalten
     * ihre 48 dp).
     */
    fun geometry(
        width: Float,
        height: Float,
        density: Float,
    ): StripGeometry {
        val min = MIN_TOUCH_DP * density
        val deadTop = height * TapZones.TOP_DEAD_ZONE
        // Platz für Schutz + Leiste + Schutz zwischen den beiden Mindest-Zählflächen
        val room = max(0f, height - deadTop - 2 * min)
        var touch = min(max(min, height * MIN_HEIGHT_FRACTION), max(min, room - 2 * GUARD_DP * density))
        val guard = max(0f, min(GUARD_DP * density, (room - touch) / 2))
        touch = min(touch, room - 2 * guard).coerceAtLeast(0f)
        val lowest = height - min - guard - touch // tiefste Lage: Gegner behält 48 dp
        val highest = deadTop + min + guard // höchste Lage: Wir behält 48 dp
        val top = ((height - touch) / 2).coerceAtLeast(highest).coerceAtMost(max(highest, lowest))
        val bottom = top + touch
        return StripGeometry(
            top = top,
            bottom = bottom,
            left = width * SIDE_MARGIN,
            right = width * (1 - SIDE_MARGIN),
            guard = guard,
            visualHeight = max(0f, touch - 2 * VISUAL_INSET_DP * density),
            usArea = top - guard - deadTop,
            themArea = height - bottom - guard,
        )
    }

    /** Wohin gehört eine Berührung, die bei ([x], [y]) beginnt? */
    fun hit(
        x: Float,
        y: Float,
        g: StripGeometry,
    ): StripHit =
        when {
            y in g.top..g.bottom && x in g.left..g.right -> StripHit.STRIP
            y in (g.top - g.guard)..(g.bottom + g.guard) -> StripHit.GUARD
            else -> StripHit.OUTSIDE
        }

    /**
     * Segment (0 until [count]) unter [x] bei [count] gleich breiten Segmenten der Leiste. Lücken zählen zum
     * nächstgelegenen Segment (große Trefferflächen); außerhalb `null`.
     */
    fun segment(
        x: Float,
        g: StripGeometry,
        count: Int,
    ): Int? {
        if (g.width <= 0f || count <= 0 || x < g.left || x > g.right) return null
        return ((x - g.left) / g.width * count).toInt().coerceIn(0, count - 1)
    }
}
