package eu.advantly.health

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HealthRecorderTest {
    private val start = 1_000_000L

    private fun at(seconds: Int) = start + seconds * 1000L

    private fun series(
        bpm: List<Int?>,
        steps: List<Long?> = bpm.map { null },
    ) = HealthSeries(start, 5_000, bpm, steps, 0, HealthQuality.of(bpm, 5_000))

    @Test
    fun samplesAreAveragedPerBucketWithIntegerRoundingAndGapsStayNull() {
        val recorder = HealthRecorder(start)
        recorder.heartRate(at(0), 100)
        recorder.heartRate(at(4), 101) // 100,5 → 101
        recorder.heartRate(at(5), 120)
        recorder.heartRate(at(17), 90)
        assertEquals(listOf(101, 120, null, 90), recorder.series().bpm)
        assertEquals(listOf(null, null, null, null), recorder.series().steps)
    }

    @Test
    fun lateSamplesAreSortedBySampleTimeNotArrivalTime() {
        val recorder = HealthRecorder(start)
        recorder.heartRate(at(12), 130)
        recorder.steps(at(12), 40)
        // Gebündelt aus dem Ambient-Modus nachgeliefert: älter als das zuletzt empfangene Sample.
        recorder.heartRate(at(1), 80)
        recorder.steps(at(1), 10)
        recorder.steps(at(11), 35)
        recorder.steps(at(10), 30) // älter als das 12-s-Sample im selben Bucket → zählt nicht
        assertEquals(listOf(80, null, 130), recorder.series().bpm)
        assertEquals(listOf(0L, null, 30L), recorder.series().steps)
    }

    @Test
    fun outOfRangeValuesAndTimesAreDropped() {
        val recorder = HealthRecorder(start)
        assertFalse(recorder.heartRate(at(0), 24))
        assertFalse(recorder.heartRate(at(0), 251))
        assertFalse(recorder.heartRate(start - 1, 100))
        assertFalse(recorder.heartRate(start + HealthRecorder.MAX_DURATION_MS, 100))
        assertFalse(recorder.steps(at(0), -1))
        assertEquals(0, recorder.series().bpm.size)
        assertEquals(HealthQuality.NO_HR, recorder.series().quality)
        assertTrue(recorder.heartRate(at(0), 25))
        assertTrue(recorder.heartRate(at(5), 250))
        assertTrue(recorder.heartRate(start + HealthRecorder.MAX_DURATION_MS - 1, 100))
        assertEquals(listOf(25, 250), recorder.series().bpm.take(2))
    }

    @Test
    fun exerciseRestartReanchorsStepsWithoutNegativeDeltas() {
        val recorder = HealthRecorder(start)
        recorder.steps(at(0), 500) // Basis: Schritte vor dem Match zählen nicht
        recorder.steps(at(5), 520)
        recorder.steps(at(10), 7) // Rücksprung = neue Exercise ab 0
        recorder.steps(at(15), 12)
        assertEquals(listOf(0L, 20L, 27L, 32L), recorder.series().steps)
    }

    @Test
    fun stepsBetweenCountsWholeBucketsAndRoundsBothEndsDown() {
        val s = series(List(5) { 100 }, listOf(0L, 10L, null, 25L, 40L))
        assertEquals(40L, stepsBetween(s, start, at(25)))
        assertEquals(10L, stepsBetween(s, at(5), at(10)))
        assertEquals(10L, stepsBetween(s, at(7), at(14))) // innerhalb von Buckets → auf das Raster abgerundet
        assertEquals(15L, stepsBetween(s, at(10), at(20))) // Bucket 2 ohne Wert: Basis ist Bucket 1
        assertEquals(15L, stepsBetween(s, at(20), at(999))) // über das Ende hinaus
        assertNull(stepsBetween(s, at(10), at(15))) // nur die Lücke
        assertNull(stepsBetween(s, at(10), at(10)))
        assertEquals(10L, stepsBetween(s, start - 60_000, at(10))) // vor dem Start
        assertNull(stepsBetween(series(listOf(100)), start, at(5)))
    }

    @Test
    fun shiftMovesTheSeriesOntoTheReferenceClock() {
        val s = series(listOf(100, 120)).copy(clockOffsetMs = 3_000)
        val aligned = shift(s, s.clockOffsetMs)
        assertEquals(start - 3_000, aligned.startMs)
        assertEquals(0, aligned.clockOffsetMs)
        assertEquals(s.bpm, aligned.bpm)
        assertEquals(s, shift(aligned, -3_000))
    }

    @Test
    fun pulseAroundAveragesBucketsStartingInTheWindow() {
        // Buckets 0..9 mit 100, 110, …, 190
        val s = series(List(10) { 100 + it * 10 })
        // Punktende bei 30 s: Buckets mit Beginn in [10 s, 40 s) → 2..7 → Ø 145
        assertEquals(145, pulseAround(s, at(30)))
        // Unausgerichtet bei 31 s: [11 s, 41 s) → Buckets 3..8 → Ø 155
        assertEquals(155, pulseAround(s, at(31)))
        assertEquals(100, pulseAround(s, start - 9_000)) // [-29 s, 1 s) → nur Bucket 0
        assertNull(pulseAround(s, at(200)))
        assertEquals(130, pulseAround(series(listOf(null, 130, null)), at(5)))
        assertNull(pulseAround(series(listOf(null, null)), at(5)))
    }

    @Test
    fun qualityFollowsSampleDensityAndGaps() {
        assertEquals(HealthQuality.NO_HR, HealthQuality.of(emptyList(), 5_000))
        assertEquals(HealthQuality.NO_HR, HealthQuality.of(listOf(null, null), 5_000))
        assertEquals(HealthQuality.OK, HealthQuality.of(List(60) { 100 }, 5_000))
        // 1 Wert je 30 s ist gerade noch genug, weniger ist SPARSE_HR.
        val everySixth = List(60) { if (it % 6 == 0) 100 else null }
        assertEquals(HealthQuality.OK, HealthQuality.of(everySixth, 5_000))
        val everySeventh = List(70) { if (it % 7 == 0) 100 else null }
        assertEquals(HealthQuality.SPARSE_HR, HealthQuality.of(everySeventh, 5_000))
        // Kurze Lücken (< 30 s) zählen nicht, ab 10 % in langen Lücken → GAPS.
        val shortGaps = List(100) { if (it % 5 == 0) null else 100 }
        assertEquals(HealthQuality.OK, HealthQuality.of(shortGaps, 5_000))
        val longGap = List(100) { if (it in 40 until 50) null else 100 }
        assertEquals(HealthQuality.GAPS, HealthQuality.of(longGap, 5_000))
        val justBelow = List(100) { if (it in 40 until 49) null else 100 }
        assertEquals(HealthQuality.OK, HealthQuality.of(justBelow, 5_000))
        val trailingGap = List(100) { if (it >= 90) null else 100 }
        assertEquals(HealthQuality.GAPS, HealthQuality.of(trailingGap, 5_000))
    }

    @Test
    fun maxBpmAndRedactedToString() {
        val recorder = HealthRecorder(start)
        recorder.heartRate(at(0), 140)
        recorder.heartRate(at(10), 187)
        recorder.steps(at(10), 4321)
        val s = recorder.series(clockOffsetMs = 1234)
        assertEquals(187, maxBpm(s))
        assertNull(maxBpm(series(listOf(null))))
        assertEquals("HealthSeries(<redacted>)", s.toString())
    }

    @Test
    fun resumeContinuesTheStoredSeriesAfterProcessDeath() {
        val before = HealthRecorder(start)
        before.steps(at(0), 0)
        before.heartRate(at(0), 100)
        before.heartRate(at(4), 101)
        before.steps(at(9), 30)
        val stored = before.series(clockOffsetMs = 700)
        val resumed = HealthRecorder.resume(stored)
        assertEquals(stored.copy(clockOffsetMs = 0), resumed.series())
        // Nach dem Neustart beginnt der Schrittzähler wieder bei 0 und wird angehängt
        resumed.steps(at(12), 0)
        resumed.steps(at(16), 8)
        resumed.heartRate(at(16), 140)
        assertEquals(listOf(101, null, null, 140), resumed.series().bpm)
        assertEquals(listOf(0L, 30L, 30L, 38L), resumed.series().steps)
        // Läuft der Rohzähler weiter (gleiche Zählung wie die Summen), zählt nur die Differenz
        val continued = HealthRecorder.resume(stored)
        continued.steps(at(12), 45)
        assertEquals(listOf(0L, 30L, 45L), continued.series().steps)
    }
}
