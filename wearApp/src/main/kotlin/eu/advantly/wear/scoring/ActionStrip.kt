package eu.advantly.wear.scoring

/**
 * Aktionsleiste des Scoring-Screens (W2 „Bedienung“): ein waagerechter Streifen um das Mittelband (das ohnehin nicht
 * zählt, [TapZones.MIDDLE_BAND]). Er liegt zwischen den Ziffern beider Hälften und ist auf runden Displays an dieser
 * Stelle am breitesten. Nur solange dort eine Aktion angeboten wird (Aufschlag-Leiste nach dem Punkt, „Rückgängig“ nach
 * „Punkt abziehen“, Hinweis „Aufschläger wählen“), gehen Taps im Streifen an die Aktion statt an [TapZones]; sonst
 * bleibt alles wie bisher.
 *
 * Reine Geometrie in Bruchteilen von Breite/Höhe (JVM-getestet); die Composables nutzen dieselben Werte.
 */
object ActionStrip {
    /** Oberkante (Anteil der Höhe): knapp unter den Ziffern der oberen Hälfte. */
    const val TOP = 0.42f

    /** Unterkante (Anteil der Höhe): knapp über den Ziffern der unteren Hälfte. */
    const val BOTTOM = 0.58f

    /** Seitenrand links und rechts (Anteil der Breite); innerhalb davon liegen die Segmente. */
    const val SIDE_MARGIN = 0.06f

    /** Höhe des Streifens als Anteil der Displayhöhe. */
    const val HEIGHT = BOTTOM - TOP

    /** Breite des Streifens als Anteil der Displaybreite. */
    const val WIDTH = 1f - 2 * SIDE_MARGIN

    /** Liegt ([x], [y]) im Streifen? Einheiten wie [width]/[height] (px). */
    fun contains(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
    ): Boolean {
        if (width <= 0f || height <= 0f) return false
        val relX = x / width
        val relY = y / height
        return relY in TOP..BOTTOM && relX in SIDE_MARGIN..(1f - SIDE_MARGIN)
    }

    /**
     * Segment (0 until [count]) unter [x] bei [count] gleich breiten Segmenten im Streifen. Taps auf Lücken zählen zum
     * nächstgelegenen Segment (große Trefferflächen); außerhalb des Streifens `null`.
     */
    fun segment(
        x: Float,
        width: Float,
        count: Int,
    ): Int? {
        if (width <= 0f || count <= 0) return null
        val rel = (x / width - SIDE_MARGIN) / WIDTH
        if (rel < 0f || rel > 1f) return null
        return (rel * count).toInt().coerceIn(0, count - 1)
    }
}
