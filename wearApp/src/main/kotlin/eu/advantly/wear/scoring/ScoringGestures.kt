package eu.advantly.wear.scoring

import kotlin.math.abs

/**
 * Tap-Zonen des Scoring-Screens nach 04 §4.3.2/§4.3.3: obere Hälfte = Wir, untere = Gegner,
 * oberste [TOP_DEAD_ZONE] (System-Wischgesten) und das Mittelband [MIDDLE_BAND] um die Grenze sind nicht scorend.
 */
object TapZones {
    const val TOP_DEAD_ZONE = 0.12f
    const val MIDDLE_BAND = 0.10f

    /** [y] und [height] in derselben Einheit (px). Liefert `null` für Totzonen. */
    fun classify(
        y: Float,
        height: Float,
    ): Side? {
        if (height <= 0f) return null
        val rel = y / height
        val bandStart = 0.5f - MIDDLE_BAND / 2
        val bandEnd = 0.5f + MIDDLE_BAND / 2
        return when {
            rel < TOP_DEAD_ZONE -> null
            rel < bandStart -> Side.US
            rel <= bandEnd -> null
            rel <= 1f -> Side.THEM
            else -> null
        }
    }
}

/** Ergebnis einer abgeschlossenen Ein-Finger-Geste auf dem Scoring-Screen. */
enum class ScoringGesture { TAP, SWIPE_DOWN, SWIPE_LEFT, NONE }

/**
 * Klassifiziert eine Geste aus Gesamtverschiebung und Dauer. Wischen nach unten = Undo,
 * Wischen nach links = Steuerseite (rechts ist System-Back, 04 §4.3.2). Tap gilt erst beim Loslassen.
 */
object GestureClassifier {
    fun classify(
        dx: Float,
        dy: Float,
        durationMillis: Long,
        touchSlopPx: Float,
        swipeThresholdPx: Float,
        maxTapMillis: Long = 500,
    ): ScoringGesture {
        val isTapDistance = abs(dx) <= touchSlopPx && abs(dy) <= touchSlopPx
        return when {
            isTapDistance && durationMillis <= maxTapMillis -> ScoringGesture.TAP
            isTapDistance -> ScoringGesture.NONE
            dy >= swipeThresholdPx && abs(dy) > abs(dx) * DIRECTION_RATIO -> ScoringGesture.SWIPE_DOWN
            dx <= -swipeThresholdPx && abs(dx) > abs(dy) * DIRECTION_RATIO -> ScoringGesture.SWIPE_LEFT
            else -> ScoringGesture.NONE
        }
    }

    /** Hauptrichtung muss deutlich überwiegen, sonst ist die Geste mehrdeutig und wird ignoriert. */
    private const val DIRECTION_RATIO = 1.5f
}
