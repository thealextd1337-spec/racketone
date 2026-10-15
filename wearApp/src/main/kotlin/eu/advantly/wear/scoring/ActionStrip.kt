package eu.advantly.wear.scoring

import kotlin.math.max

/**
 * Lage der Aktionsleiste in px (aus [ActionStrip.geometry]).
 *
 * @property top Oberkante der Trefferzone
 * @property bottom Unterkante der Trefferzone (`bottom - top` ≥ [ActionStrip.MIN_TOUCH_DP])
 * @property left linker Rand der Trefferzone
 * @property right rechter Rand der Trefferzone
 * @property guard Schutzstreifen ober- und unterhalb: Berührungen dort lösen weder Leiste noch Punkt aus
 * @property visualHeight Höhe der sichtbaren Leiste (mittig in der Trefferzone, etwas kleiner, damit die Ziffern frei
 *   bleiben – die Trefferzone ist größer als die Darstellung, wie bei Material-Mindestflächen üblich)
 */
data class StripGeometry(
    val top: Float,
    val bottom: Float,
    val left: Float,
    val right: Float,
    val guard: Float,
    val visualHeight: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
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
 * Aktionsleiste des Scoring-Screens (W2 „Bedienung“): ein waagerechter Streifen mittig über dem Mittelband (das ohnehin
 * nicht zählt, [TapZones.MIDDLE_BAND]), zwischen den Ziffern beider Hälften – auf runden Displays dort am breitesten.
 * Nur solange dort eine Aktion angeboten wird (Aufschlag-Leiste nach dem Punkt, „Rückgängig“ nach „Punkt abziehen“,
 * Hinweis „Aufschläger wählen“), gilt diese Geometrie; sonst bleibt alles wie bisher.
 *
 * Trefferzone mindestens [MIN_TOUCH_DP] hoch (04 §1: Mindestfläche 48 dp), darum ein Schutzstreifen von [GUARD_DP], in dem
 * eine Berührung gar nichts tut – knapp daneben zählt also kein Punkt. Reine Geometrie (JVM-getestet); Darstellung und
 * Trefferprüfung nutzen dieselbe Funktion.
 */
object ActionStrip {
    /** Mindesthöhe der Trefferzone. */
    const val MIN_TOUCH_DP = 48f

    /** Schutzstreifen ober- und unterhalb der Trefferzone. */
    const val GUARD_DP = 6f

    /** Die sichtbare Leiste ist je Seite so viel flacher als die Trefferzone. */
    const val VISUAL_INSET_DP = 6f

    /** Anteil der Höhe, den die Trefferzone mindestens hat (große Displays). */
    const val MIN_HEIGHT_FRACTION = 0.16f

    /** Seitenrand links und rechts (Anteil der Breite); innerhalb davon liegen die Segmente. */
    const val SIDE_MARGIN = 0.06f

    /** Geometrie für ein Display von [width] × [height] px bei [density] px/dp. */
    fun geometry(
        width: Float,
        height: Float,
        density: Float,
    ): StripGeometry {
        val touch = max(MIN_TOUCH_DP * density, height * MIN_HEIGHT_FRACTION)
        val top = (height - touch) / 2
        return StripGeometry(
            top = top,
            bottom = top + touch,
            left = width * SIDE_MARGIN,
            right = width * (1 - SIDE_MARGIN),
            guard = GUARD_DP * density,
            visualHeight = touch - 2 * VISUAL_INSET_DP * density,
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
