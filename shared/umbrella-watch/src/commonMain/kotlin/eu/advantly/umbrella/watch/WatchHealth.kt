package eu.advantly.umbrella.watch

import eu.advantly.core.model.MatchId
import eu.advantly.match.session.MatchSession

/**
 * Swift-Facade für den Pulsverlauf (Plan Puls H7): `WorkoutManager` gibt Puls und Schritte weiter, die gemeinsame
 * [MatchSession] prüft Einwilligung und Stufe selbst, sichert zwischendurch und sendet am Ende ans iPhone.
 * Gleiche Session wie [WatchScoring] – also auch nur auf der Main-Queue aufrufen. Fehler laufen nie nach Swift:
 * ohne Datenbank, mit leerer Match-ID oder bei Speicherfehlern liefern die Befehle `false` bzw. tun nichts.
 *
 * Swift: `let health = AdvantlyWatch.shared.health()`, dann z. B.
 * `health.recordHeartRate(matchId: id, atMs: ms, bpm: 120)`.
 */
class WatchHealth internal constructor(
    private val session: () -> MatchSession?,
) {
    /** Puls-Sample; [atMs] = Zeitpunkt des Samples (Epoch-ms), nicht der Empfang. `true`, wenn aufgenommen. */
    fun recordHeartRate(
        matchId: String,
        atMs: Long,
        bpm: Int,
    ): Boolean = call(matchId, false) { s, id -> s.recordHeartRate(id, atMs, bpm) }

    /** Kumulierte Schritte (z. B. seit Workout-Start); beim Matchstart einmal mit dem aktuellen Stand aufrufen. */
    fun recordSteps(
        matchId: String,
        atMs: Long,
        cumulativeSteps: Long,
    ): Boolean = call(matchId, false) { s, id -> s.recordSteps(id, atMs, cumulativeSteps) }

    /** Workout-Ende: Aufnahme speichern und ans iPhone senden (Wiederholung bis zum Ack übernimmt die Session). */
    fun finish(matchId: String) {
        call(matchId, Unit) { s, id -> s.finishHealthRecording(id) }
    }

    /** Matchende-Bilanz aus dem eigenen gespeicherten Pulsverlauf: Schritte in der Partie, -1 = keine Aufnahme/kein Wert. */
    fun recordedSteps(matchId: String): Long = call(matchId, -1L) { s, id -> s.recordedHealthSummary(id)?.steps ?: -1L }

    /** Matchende-Bilanz aus dem eigenen gespeicherten Pulsverlauf: höchster Puls, -1 = keine Aufnahme/kein Wert. */
    fun recordedMaxBpm(matchId: String): Int = call(matchId, -1) { s, id -> s.recordedHealthSummary(id)?.maxBpm ?: -1 }

    /** Läuft für das Match eine erlaubte Aufnahme? (roter Aufnahme-Punkt neben dem Puls) */
    fun isRecording(matchId: String): Boolean = call(matchId, false) { s, id -> s.isRecordingHealth(id) }

    private fun <T> call(
        matchId: String,
        fallback: T,
        block: (MatchSession, MatchId) -> T,
    ): T {
        if (matchId.isBlank()) return fallback
        return try {
            session()?.let { block(it, MatchId(matchId)) } ?: fallback
        } catch (e: Exception) {
            // auch SessionThreadViolation: Programmierfehler, darf aber nie über die Swift-Grenze laufen
            fallback
        }
    }
}
