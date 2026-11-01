package eu.advantly.presentation.stats

import eu.advantly.core.model.Side
import eu.advantly.core.scoring.MomentKind
import eu.advantly.core.scoring.MomentOutcome
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.SidePair
import eu.advantly.presentation.match.CountingIds
import eu.advantly.presentation.match.InMemoryMatchStore
import eu.advantly.presentation.match.StepClock
import eu.advantly.presentation.match.play
import eu.advantly.presentation.match.setupFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MatchTimelineTest {
    /** Beispiel aus dem Review (0 · Pause 10 · Punkt 70 · Punkt 100 → 40 s Spielzeit). */
    private val clock = PlayClock(0, 100_000, listOf(10_000L..70_000L))

    @Test
    fun playClockCutsPauses() {
        assertEquals(40_000, clock.durationMs)
        assertEquals(5_000, clock.at(5_000))
        assertNull(clock.at(50_000), "in der Pause")
        assertEquals(10_000, clock.position(50_000), "Position in der Pause = Pausenbeginn")
        assertEquals(20_000, clock.at(80_000))
        assertNull(clock.at(110_000))
        assertEquals(40_000, clock.position(110_000))
    }

    @Test
    fun pulseGapsAndPausesBreakTheLine() {
        val track = PulseTrack(0, 10_000, listOf(100, null, 120, 130, 140, 150, 160, 170, 180, 190, 200, 210))
        assertEquals(
            listOf(
                listOf(PulsePoint(0, 100)),
                listOf(PulsePoint(10_000, 170), PulsePoint(20_000, 180), PulsePoint(30_000, 190), PulsePoint(40_000, 200)),
            ),
            clock.pulseSegments(track),
        )
    }

    @Test
    fun statsCarryMomentsWithScoreBeforeThePointOnThePlayAxis() {
        val time = StepClock(now = 0, stepMs = 0)
        val store = InMemoryMatchStore(time, CountingIds())
        val id = store.start(store.setupFor(Presets.TENNIS_SINGLES_BO3.format()))
        store.play("AABBB")
        time.now = 60_000
        assertTrue(store.pause())
        time.now = 120_000
        store.play("B")
        val stats = MatchStatistics.compute(store.record(id)!!)
        val moment = stats.moments.single()
        assertEquals(setOf(MomentKind.BREAK_POINT, MomentKind.BREAK), moment.moment.kinds)
        assertEquals(MomentOutcome.CONVERTED, moment.moment.outcome)
        assertEquals(Side.B, moment.moment.side)
        assertEquals(SidePair("30", "40"), moment.before.current?.display)
        assertEquals(60_000, moment.playMs, "Pause herausgeschnitten")
        assertEquals(stats.netDurationMs, stats.playClock.durationMs)
    }
}
