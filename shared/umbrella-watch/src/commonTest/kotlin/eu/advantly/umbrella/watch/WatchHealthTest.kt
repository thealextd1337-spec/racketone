package eu.advantly.umbrella.watch

import eu.advantly.core.model.MatchId
import eu.advantly.core.model.TelemetryVisibility
import eu.advantly.device.link.testing.ManualScheduler
import eu.advantly.match.session.MatchSessions
import eu.advantly.match.session.ThreadGuard
import eu.advantly.match.session.WallClock
import eu.advantly.sync.protocol.PeerKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Plan Puls H7: Pulsverlauf über die flache Swift-Facade – Stufe beim Anlegen, Aufnahme nur bei erlaubter Stufe. */
class WatchHealthTest {
    private val scheduler = ManualScheduler()
    private val clock = WallClock { 1_760_000_000_000L + scheduler.nowMs() }
    private var onMainThread = true
    private val session =
        MatchSessions
            .create(testLocalData(), link = null, scheduler, PeerKind.WATCH, clock = clock, threadGuard = ThreadGuard { onMainThread })
            .also { it.start() }
    private val scoring = WatchScoring({ WatchScoring.Backend(session, session.data) }, clock)
    private val health = WatchHealth(scoring::session)

    private fun startMatch(): String =
        requireNotNull(scoring.startMatch("TENNIS_SINGLES_BO3", doubles = false, weServeFirst = true, watchWorkout = true)).matchId

    @Test
    fun withConsentTheWatchMatchRecordsUntilFinish() {
        session.setHealthConsent(true)
        val id = startMatch()
        assertEquals(TelemetryVisibility.PRIVATE, session.pulseVisibility(MatchId(id)), "Standardstufe beim Anlegen")
        val t = clock.nowMs()
        assertTrue(health.recordSteps(id, t, 0))
        assertTrue(health.recordHeartRate(id, t, 110))
        assertTrue(health.recordHeartRate(id, t + 6_000, 120))
        assertTrue(health.recordSteps(id, t + 6_000, 12))
        assertTrue(health.isRecording(id))
        health.finish(id)
        assertFalse(health.isRecording(id))
        val stored =
            session.data.health
                .forSource(session.deviceId)
                .single()
        assertEquals(listOf(110, 120), stored.series.bpm)
        assertEquals(listOf(0L, 12L), stored.series.steps)
        // Matchende-Bilanz (0.5.1) nur mit primitiven Typen; -1 = unbekannt
        assertEquals(12L, health.recordedSteps(id))
        assertEquals(120, health.recordedMaxBpm(id))
        assertEquals(-1L, health.recordedSteps(""))
        assertEquals(-1, health.recordedMaxBpm("unknown"))
    }

    @Test
    fun withoutConsentOrWithTelemetryOffNothingIsRecorded() {
        val noConsent = startMatch()
        assertEquals(TelemetryVisibility.OFF, session.pulseVisibility(MatchId(noConsent)))
        assertFalse(health.recordHeartRate(noConsent, clock.nowMs(), 110))
        assertFalse(health.isRecording(noConsent))

        session.setHealthConsent(true)
        assertTrue(scoring.setTelemetryVisibility("off"))
        val off = startMatch()
        assertEquals(TelemetryVisibility.OFF, session.pulseVisibility(MatchId(off)))
        assertFalse(health.recordHeartRate(off, clock.nowMs(), 110))
        assertFalse(health.isRecording(off))
        health.finish(off)
        assertTrue(
            session.data.health
                .allSeries()
                .isEmpty(),
        )
    }

    @Test
    fun invalidIdsWrongThreadAndBrokenStoreNeverThrow() {
        session.setHealthConsent(true)
        val id = startMatch()
        assertFalse(health.recordHeartRate("", clock.nowMs(), 110))
        assertFalse(health.recordHeartRate("unknown", clock.nowMs(), 110))
        assertFalse(health.isRecording(" "))
        onMainThread = false
        assertFalse(health.recordHeartRate(id, clock.nowMs(), 110))
        health.finish(id)
        onMainThread = true
        assertFalse(health.isRecording(id))

        val broken = WatchHealth(WatchScoring({ error("disk I/O error") })::session)
        assertFalse(broken.recordSteps(id, clock.nowMs(), 0))
        assertFalse(broken.isRecording(id))
        broken.finish(id)
    }
}
