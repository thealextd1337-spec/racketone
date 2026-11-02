package eu.advantly.health

/**
 * Kleine Match-Bilanz der Uhr am Matchende: Schritte in der Partie und höchster Puls. `null` = Wert unbekannt (z. B.
 * kein Pulsrecht); beide `null` = nichts anzeigen. `toString` ohne Messwerte (Gesundheitsdaten nie in Logs).
 */
data class HealthSummary(
    val steps: Long?,
    val maxBpm: Int?,
) {
    val isEmpty: Boolean get() = steps == null && maxBpm == null

    override fun toString(): String = "HealthSummary(<redacted>)"

    companion object {
        /** Aus der aufgezeichneten Serie: höchster Puls-Bucket, Schritte = letzter kumulierter Wert (ab Aufnahmebeginn). */
        fun of(series: HealthSeries): HealthSummary = HealthSummary(series.steps.lastOrNull { it != null }, maxBpm(series))

        /**
         * Was die Uhr zeigt: nichts bei Telemetrie „Gar nicht“ ([telemetryAllowed] = `false`); sonst je Wert die
         * aufgezeichnete Serie ([recorded]), ersatzweise die Live-Werte des Workouts ([live]); ohne beides `null`.
         */
        fun shown(
            recorded: HealthSummary?,
            live: HealthSummary?,
            telemetryAllowed: Boolean,
        ): HealthSummary? {
            if (!telemetryAllowed) return null
            return HealthSummary(recorded?.steps ?: live?.steps, recorded?.maxBpm ?: live?.maxBpm).takeUnless { it.isEmpty }
        }
    }
}

/**
 * Live-Werte eines Workouts nur im Arbeitsspeicher (wird nie gespeichert): höchster Puls und Schritte in der Partie.
 * Schritte als Zählerstand seit Beginn des Matches (nie fallend übernommen); Pulswerte außerhalb von
 * [HealthRecorder.BPM_RANGE] werden ignoriert. Nicht thread-sicher.
 */
class HealthTally {
    private var maxBpm: Int? = null
    private var steps: Long? = null

    fun heartRate(bpm: Int) {
        if (bpm in HealthRecorder.BPM_RANGE) maxBpm = maxOf(maxBpm ?: bpm, bpm)
    }

    fun steps(sinceMatchStart: Long) {
        if (sinceMatchStart >= 0) steps = maxOf(steps ?: sinceMatchStart, sinceMatchStart)
    }

    fun summary(): HealthSummary = HealthSummary(steps, maxBpm)

    override fun toString(): String = "HealthTally(<redacted>)"
}
