package eu.advantly.health

/**
 * Nimmt Rohsamples eines Matches entgegen und rastert sie ab [startMs] in Buckets à [intervalMs] (Plan Puls §3.1).
 * Einsortiert wird nach Sample-Zeit, nicht nach Empfangszeit – gebündelte oder verspätete Samples (Ambient-Modus)
 * landen im richtigen Bucket. Samples vor [startMs] oder mehr als [MAX_DURATION_MS] danach werden verworfen.
 * Nur RAM, nicht thread-sicher; `toString` enthält keine Messwerte.
 */
class HealthRecorder(
    val startMs: Long,
    val intervalMs: Long = INTERVAL_MS,
) {
    init {
        require(intervalMs > 0)
    }

    private val bpmSums = mutableMapOf<Int, Long>()
    private val bpmCounts = mutableMapOf<Int, Int>()

    /** Je Bucket das späteste Schrittsample als (Sample-Zeit, Rohzähler). */
    private val lastSteps = mutableMapOf<Int, Pair<Long, Long>>()

    /** Pulswert in bpm; `false`, wenn er außerhalb von [BPM_RANGE] oder des Zeitraums liegt und verworfen wurde. */
    fun heartRate(
        atMs: Long,
        bpm: Int,
    ): Boolean {
        val bucket = bucketOf(atMs) ?: return false
        if (bpm !in BPM_RANGE) return false
        bpmSums[bucket] = (bpmSums[bucket] ?: 0) + bpm
        bpmCounts[bucket] = (bpmCounts[bucket] ?: 0) + 1
        return true
    }

    /**
     * Kumulierter Schrittzähler der Exercise. Ein Rücksprung gilt als Neustart der Exercise (Zähler ab 0) und wird neu
     * verankert – es entstehen nie negative Deltas. `false`, wenn das Sample verworfen wurde.
     */
    fun steps(
        atMs: Long,
        cumulativeSteps: Long,
    ): Boolean {
        val bucket = bucketOf(atMs) ?: return false
        if (cumulativeSteps < 0) return false
        val previous = lastSteps[bucket]
        if (previous == null || atMs >= previous.first) lastSteps[bucket] = atMs to cumulativeSteps
        return true
    }

    /**
     * Aktueller Stand als Serie bis zum letzten Bucket mit Daten. Schritte zählen ab dem ersten Schrittwert; Schritte
     * innerhalb eines Buckets vor einem Exercise-Neustart gehen verloren (höchstens ein Bucket).
     */
    fun series(clockOffsetMs: Long = 0): HealthSeries {
        val size = ((bpmCounts.keys + lastSteps.keys).maxOrNull() ?: -1) + 1
        val bpm = List(size) { i -> bpmCounts[i]?.let { roundedMean(bpmSums.getValue(i), it) } }
        var total = 0L
        var previous: Long? = null
        val steps = ArrayList<Long?>(size)
        for (i in 0 until size) {
            val raw = lastSteps[i]?.second
            if (raw != null) {
                val last = previous
                total +=
                    when {
                        last == null -> 0
                        raw >= last -> raw - last
                        else -> raw
                    }
                previous = raw
            }
            steps.add(raw?.let { total })
        }
        return HealthSeries(startMs, intervalMs, bpm, steps, clockOffsetMs, HealthQuality.of(bpm, intervalMs))
    }

    private fun bucketOf(atMs: Long): Int? {
        if (atMs < startMs || atMs - startMs >= MAX_DURATION_MS) return null
        return ((atMs - startMs) / intervalMs).toInt()
    }

    companion object {
        /** Raster für Statistik und Sync (05 §5.3). */
        const val INTERVAL_MS: Long = 5_000

        /** Plausibler Pulsbereich wie der CHECK in 03 §3.3.5. */
        val BPM_RANGE: IntRange = 25..250

        /** Obergrenze gegen kaputte Zeitstempel (sonst riesige Serien); 12 h. */
        const val MAX_DURATION_MS: Long = 12 * 60 * 60 * 1000L
    }
}
