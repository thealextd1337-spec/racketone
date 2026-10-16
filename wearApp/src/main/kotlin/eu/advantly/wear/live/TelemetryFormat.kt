package eu.advantly.wear.live

import java.util.Locale
import kotlin.math.floor

/** Kompakte Telemetrie-Werte für die schmalen Randspalten des Scores (Galaxy Watch8 206 dp, große Schrift). */
object TelemetryFormat {
    /**
     * Schritte: bis 9.999 ausgeschrieben ohne Tausenderpunkt („9876“), bis 99.999 mit einer Nachkommastelle in Tausend
     * („12,3k“ bzw. en „12.3k“, abgerundet – nie mehr als gegangen), darüber ganze Tausend („123k“).
     */
    fun steps(
        steps: Long,
        locale: Locale,
    ): String =
        when {
            steps < 10_000 -> steps.coerceAtLeast(0).toString()
            steps < 100_000 -> String.format(locale, "%.1fk", floor(steps / 100.0) / 10.0)
            else -> "${steps / 1_000}k"
        }
}
