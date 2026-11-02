package eu.advantly.health

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HealthSummaryTest {
    @Test
    fun recordedSeriesGivesMaxBucketAndLastCumulativeSteps() {
        val recorder = HealthRecorder(startMs = 0)
        recorder.heartRate(1_000, 120)
        recorder.heartRate(6_000, 176)
        recorder.heartRate(7_000, 150) // Bucket 1: Mittel 163
        recorder.heartRate(11_000, 170)
        recorder.steps(1_000, 100)
        recorder.steps(16_000, 4_412)
        assertEquals(HealthSummary(steps = 4_312, maxBpm = 170), HealthSummary.of(recorder.series()))
        assertEquals(HealthSummary(null, null), HealthSummary.of(HealthRecorder(0).series()))
    }

    @Test
    fun liveTallyKeepsMaximumAndStepsInMemory() {
        val tally = HealthTally()
        assertTrue(tally.summary().isEmpty)
        listOf(130, 176, 20, 300, 150).forEach(tally::heartRate)
        tally.steps(10)
        tally.steps(4_312)
        tally.steps(4_000) // nie fallend
        assertEquals(HealthSummary(4_312, 176), tally.summary())
        assertFalse("176" in tally.toString())
    }

    @Test
    fun shownPrefersTheRecordingPerValueAndHidesWithoutTelemetry() {
        val recorded = HealthSummary(steps = 4_000, maxBpm = null)
        val live = HealthSummary(steps = 4_312, maxBpm = 176)
        assertEquals(HealthSummary(4_000, 176), HealthSummary.shown(recorded, live, telemetryAllowed = true))
        assertEquals(live, HealthSummary.shown(null, live, telemetryAllowed = true))
        assertEquals(HealthSummary(null, 150), HealthSummary.shown(null, HealthSummary(null, 150), telemetryAllowed = true))
        // „Gar nicht“ bzw. kein Workout: nichts
        assertNull(HealthSummary.shown(recorded, live, telemetryAllowed = false))
        assertNull(HealthSummary.shown(null, null, telemetryAllowed = true))
        assertNull(HealthSummary.shown(HealthSummary(null, null), HealthTally().summary(), telemetryAllowed = true))
    }
}
