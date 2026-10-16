package eu.advantly.wear.scoring

import eu.advantly.core.model.Side
import eu.advantly.match.session.OperationTimings
import kotlin.math.abs

/**
 * Tap-Zonen des Scoring-Screens nach 04 §4.3.2/§4.3.3: obere Hälfte = Wir (Side A), untere = Gegner (Side B),
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
            rel < bandStart -> Side.A
            rel <= bandEnd -> null
            rel <= 1f -> Side.B
            else -> null
        }
    }
}

/** Ergebnis einer abgeschlossenen Ein-Finger-Geste auf dem Scoring-Screen. */
enum class ScoringGesture { TAP, LONG_PRESS, SWIPE_DOWN, SWIPE_LEFT, NONE }

/**
 * Klassifiziert eine Geste aus Gesamtverschiebung und Dauer. Wischen nach unten = Undo,
 * Wischen nach links = Steuerseite (rechts ist System-Back, 04 §4.3.2). Tap gilt erst beim Loslassen.
 * Langes Halten ([longPressMillis]) entsperrt die Touch-Sperre (04 §4.3.2 „Sperre“), sonst passiert nichts.
 * [maxTravelPx] = weiteste Entfernung vom Aufsetzpunkt während der Geste: Wer einmal über die Touch-Slop gewandert
 * ist, tippt bzw. hält nicht mehr – auch wenn der Finger zum Ausgangspunkt zurückkehrt (Review PR #46).
 */
object GestureClassifier {
    fun classify(
        dx: Float,
        dy: Float,
        durationMillis: Long,
        touchSlopPx: Float,
        swipeThresholdPx: Float,
        maxTapMillis: Long = 500,
        longPressMillis: Long = LONG_PRESS_MILLIS,
        maxTravelPx: Float = 0f,
    ): ScoringGesture {
        val isTapDistance = abs(dx) <= touchSlopPx && abs(dy) <= touchSlopPx && maxTravelPx <= touchSlopPx
        return when {
            isTapDistance && durationMillis <= maxTapMillis -> ScoringGesture.TAP
            isTapDistance && durationMillis >= longPressMillis -> ScoringGesture.LONG_PRESS
            isTapDistance -> ScoringGesture.NONE
            dy >= swipeThresholdPx && abs(dy) > abs(dx) * DIRECTION_RATIO -> ScoringGesture.SWIPE_DOWN
            dx <= -swipeThresholdPx && abs(dx) > abs(dy) * DIRECTION_RATIO -> ScoringGesture.SWIPE_LEFT
            else -> ScoringGesture.NONE
        }
    }

    /** Entsperren bzw. Punkt abziehen: 1 s gedrückt halten (wie am Handy, 04 H6; gemeinsame Zeit aller Geräte). */
    const val LONG_PRESS_MILLIS = OperationTimings.HOLD_TO_REMOVE_MILLIS

    /** Hauptrichtung muss deutlich überwiegen, sonst ist die Geste mehrdeutig und wird ignoriert. */
    private const val DIRECTION_RATIO = 1.5f
}
