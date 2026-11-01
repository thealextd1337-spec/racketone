package eu.advantly.presentation.stats

import eu.advantly.health.HealthQuality
import eu.advantly.health.HealthSeries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HealthTracksTest {
    private fun series(
        bpm: List<Int?>,
        startMs: Long = 0,
        offsetMs: Long = 0,
        quality: HealthQuality = HealthQuality.OK,
        steps: List<Long?> = bpm.map { null },
    ) = HealthSeries(startMs, 5_000, bpm, steps, offsetMs, quality)

    @Test
    fun clockOffsetIsAppliedOnlyAboveTwoSeconds() {
        assertEquals(10_000, HealthTracks.aligned(series(listOf(100), 10_000, offsetMs = 2_000)).startMs)
        assertEquals(10_000, HealthTracks.aligned(series(listOf(100), 10_000, offsetMs = -2_000)).startMs)
        assertEquals(7_999, HealthTracks.aligned(series(listOf(100), 10_000, offsetMs = 2_001)).startMs)
        assertEquals(13_000, HealthTracks.aligned(series(listOf(100), 10_000, offsetMs = -3_000)).startMs)
        // Die Kurve liegt nach dem Abgleich in Handyzeit.
        assertEquals(7_000, HealthTracks.pulseTrack(listOf(series(listOf(100), 10_000, offsetMs = 3_000)), 0, 20_000)!!.startMs)
    }

    @Test
    fun seriesMostlyOutsideTheMatchIsMarkedAsGaps() {
        val bpm = List(10) { 100 }
        // Buckets beginnen bei 0, 5 s … 45 s; im Match 0…20 s liegen 5 von 10 → plausibel
        assertEquals(HealthQuality.OK, HealthTracks.pulseTrack(listOf(series(bpm)), 0, 20_000)!!.quality)
        // 4 von 10 → weniger als die Hälfte
        assertEquals(HealthQuality.GAPS, HealthTracks.pulseTrack(listOf(series(bpm)), 0, 15_000)!!.quality)
        // Serie eine Stunde neben dem Match (z. B. falsche Uhrzeit der Quelle): ganz außerhalb
        assertEquals(HealthQuality.GAPS, HealthTracks.pulseTrack(listOf(series(bpm, 3_600_000)), 0, 60_000)!!.quality)
        // Ohne Pulswerte bleibt es bei NO_HR
        val empty = series(List(10) { null }, 3_600_000, quality = HealthQuality.NO_HR)
        assertEquals(HealthQuality.NO_HR, HealthTracks.pulseTrack(listOf(empty), 0, 60_000)!!.quality)
        assertNull(HealthTracks.pulseTrack(emptyList(), 0, 60_000))
    }

    @Test
    fun bestSourceWinsByQualityThenByValues() {
        val sparse = series(listOf(150, null, null, null, null, null, null, null), quality = HealthQuality.SPARSE_HR)
        val gaps = series(listOf(120, 121), quality = HealthQuality.GAPS)
        val okFew = series(listOf(130))
        val okMany = series(listOf(140, 141, null))
        assertEquals(okMany, HealthTracks.best(listOf(sparse, gaps, okFew, okMany)))
        assertEquals(gaps, HealthTracks.best(listOf(sparse, gaps)))
        assertEquals(listOf(140, 141, null), HealthTracks.pulseTrack(listOf(sparse, okMany), 0, 15_000)!!.bpm)
    }

    @Test
    fun mostStepsInASetSkipsGapsAndSetsWithoutSteps() {
        // kumulierte Schritte je 5-s-Bucket; Lücken (null) zählen nicht
        val steps = listOf<Long?>(0, 10, 20, null, 50, 60, null, null, 100, 400, null, null)
        val s = series(List(steps.size) { 100 }, steps = steps)
        // Satz 1: Buckets 1..4 (5 s bis vor 25 s) → letzter Wert 50, davor 0
        // Satz 2: Buckets 6..8 (30 s bis vor 45 s) → 100 − 60
        // Satz 3: nur Lücken → kein Wert
        assertEquals(50, HealthTracks.maxStepsInSet(s, listOf(5_000L..25_000L, 30_000L..45_000L, 50_000L..60_000L)))
        assertEquals(40, HealthTracks.maxStepsInSet(s, listOf(30_000L..45_000L)))
        assertNull(HealthTracks.maxStepsInSet(s, listOf(50_000L..60_000L)))
        assertNull(HealthTracks.maxStepsInSet(s, emptyList()))
    }

    @Test
    fun averagePulseIsATrendAndSkippedForSparseSeries() {
        val track = PulseTrack(0, 5_000, listOf(100, 110, null, 130, 200, 200, 200))
        // Fenster [−20 s, +10 s) um 10 s: Buckets 0…3
        assertEquals(113, track.averageAround(10_000))
        assertNull(track.copy(quality = HealthQuality.SPARSE_HR).averageAround(10_000))
        assertNull(track.averageAround(600_000))
        assertEquals("PulseTrack(<redacted>)", track.toString())
    }
}
