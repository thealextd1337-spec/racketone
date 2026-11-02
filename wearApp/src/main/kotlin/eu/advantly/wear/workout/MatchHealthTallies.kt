package eu.advantly.wear.workout

import eu.advantly.core.model.MatchId
import eu.advantly.health.HealthSummary
import eu.advantly.health.HealthTally

/**
 * Live-Werte des Workouts je Match (höchster Puls, Schritte in der Partie) nur im Arbeitsspeicher des Prozesses – für
 * die Matchende-Bilanz, wenn kein Pulsverlauf aufgezeichnet wurde. Wird nie gespeichert; nach einem Prozess-Neustart
 * fehlen die Werte. Schreibt der Workout-Dienst (nur bei erlaubter Telemetrie), liest der Controller.
 */
object MatchHealthTallies {
    private val tallies = HashMap<MatchId, HealthTally>()

    fun record(
        matchId: MatchId,
        heartRates: List<Int>,
        stepsInMatch: Long?,
    ) = synchronized(tallies) {
        val tally = tallies.getOrPut(matchId, ::HealthTally)
        heartRates.forEach(tally::heartRate)
        stepsInMatch?.let(tally::steps)
    }

    fun summary(matchId: MatchId): HealthSummary? = synchronized(tallies) { tallies[matchId]?.summary() }

    /** „Gar nicht“: bisher gesammelte Live-Werte verwerfen. */
    fun clear() = synchronized(tallies) { tallies.clear() }
}
