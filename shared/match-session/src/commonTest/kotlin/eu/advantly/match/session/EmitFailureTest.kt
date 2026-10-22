package eu.advantly.match.session

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.EventId
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.PointWon
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.ScoreEvent
import eu.advantly.core.scoring.ScorerChanged
import eu.advantly.data.scoreEvents
import eu.advantly.data.toStoredEvent
import eu.advantly.device.link.testing.ManualScheduler
import eu.advantly.sync.protocol.PeerKind
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `SEQUENCE_CONFLICT` in `emit` nie still: ein Punkt liegt im Store oder das Kommando meldet `false`. */
class EmitFailureTest {
    private val data = testLocalData()
    private val device = DeviceId("phone")
    private var match: MatchId? = null

    /** Paralleler Schreiber: belegt genau zwischen Lesen des Stands und Speichern die nächste eigene Sequenz. */
    private var conflicts = 0
    private val random = RandomIdGenerator(Random(5))
    private val ids =
        IdGenerator { now ->
            val target = match
            if (conflicts > 0 && target != null) {
                conflicts--
                val seq = data.events.maxDeviceSeq(target.value, device.value) + 1
                val lamport = data.events.maxLamport(target.value) + 1
                val parallel = ScoreEvent(EventId("parallel-$seq"), target, device, seq, lamport, now, null, ScorerChanged(device))
                data.events.append(parallel.toStoredEvent())
            }
            random.newUuid7(now)
        }
    private val session =
        MatchSession(data, null, ManualScheduler(), SessionConfig(device, PeerKind.PHONE), WallClock { 1_000 }, ids).also { it.start() }

    private fun storedPoints(): Int = data.events.scoreEvents(match!!).count { it.payload is PointWon }

    @Test
    fun aSequenceTakenOnceIsRetriedAndThePointIsStored() {
        match = session.create(Presets.TENNIS_SINGLES_BO3.format(), SINGLES)
        conflicts = 1
        assertTrue(session.recordPoint(Side.A))
        assertEquals(1, storedPoints(), "Punkt liegt im Store")
        assertEquals(
            1,
            session.state.value.match!!
                .pointsPlayed,
        )
    }

    @Test
    fun aSequenceTakenTwiceFailsVisiblyWithoutAPointOnlyInMemory() {
        match = session.create(Presets.TENNIS_SINGLES_BO3.format(), SINGLES)
        conflicts = 2
        assertFalse(session.recordPoint(Side.A))
        assertEquals(0, storedPoints())
        assertEquals(
            0,
            session.state.value.match!!
                .pointsPlayed,
            "Engine nie vor dem Store",
        )
        conflicts = 0
        assertTrue(session.recordPoint(Side.A))
        assertEquals(1, storedPoints())
        assertEquals(
            1,
            session.state.value.match!!
                .pointsPlayed,
        )
    }
}
