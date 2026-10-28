package eu.advantly.match.session

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.EventId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.Placeholders
import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.EventPayload
import eu.advantly.core.scoring.PlayerAssigned
import eu.advantly.core.scoring.PointWon
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.ScoreEvent
import eu.advantly.data.LocalData
import eu.advantly.data.createInMemoryJvmDriver
import eu.advantly.data.scoreEvents
import eu.advantly.data.toStoredEvent
import eu.advantly.device.link.testing.ManualScheduler
import eu.advantly.sync.protocol.PeerKind
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** Session-Hook für gepullte Events (`CloudPullRunner.eventsCommitted`, Anfrage Paket H, Bedingungen Review H). */
class PulledEventsHookTest {
    private val scheduler = ManualScheduler()
    private val driver = createInMemoryJvmDriver()
    private val data = LocalData(driver)
    private val session =
        MatchSession(
            data,
            null,
            scheduler,
            SessionConfig(MatchSessions.deviceId(data), PeerKind.PHONE),
            WallClock { 1_760_000_000_000 + scheduler.nowMs() },
            RandomIdGenerator(Random(5)),
        )
    private val otherPhone = DeviceId("phone-b")
    private var otherSeq = 0L

    @AfterTest
    fun tearDown() {
        session.close()
        driver.close()
    }

    /** Wie der Pull: Events eines zweiten eigenen Handys landen am Store vorbei an der Session. */
    private fun pull(
        match: MatchId,
        vararg payloads: EventPayload,
    ) = payloads.forEach {
        val lamport = data.events.maxLamport(match.value) + 1
        val parent =
            data.events
                .scoreEvents(match)
                .last()
                .id
        val event = ScoreEvent(EventId("b-${++otherSeq}"), match, otherPhone, otherSeq, lamport, lamport, parent, it)
        data.events.append(event.toStoredEvent().copy(confirmed = true))
    }

    private fun points(match: MatchId) =
        session.state.value
            .match(match)!!
            .pointsPlayed

    private fun events(match: MatchId) =
        session.state.value
            .match(match)!!
            .eventCount

    @Test
    fun pulledEventsAppearInTheOpenMatchOnceCoalesced() {
        session.start()
        val match =
            session.create(Presets.TENNIS_SINGLES_BO3.format(), SINGLES, scorer = ScorerChoice.Device(otherPhone))
        val replays = session.lifecycleReplays
        pull(match, PointWon(Side.A), PointWon(Side.B), PointWon(Side.A))
        val posts = scheduler.pendingTasks

        session.eventsCommitted(match.value)
        session.eventsCommitted(match.value)
        assertEquals(posts + 1, scheduler.pendingTasks, "Aufrufe zu einem Post zusammengelegt")
        assertEquals(0, points(match), "kein Replay im Callback")

        scheduler.runUntilIdle()
        assertEquals(3, points(match))
        assertEquals(data.events.scoreEvents(match).size, events(match))
        assertEquals(replays + 1, session.lifecycleReplays)
    }

    @Test
    fun noReplayWithoutNewEvents() {
        session.start()
        val match = session.create(Presets.TENNIS_SINGLES_BO3.format(), SINGLES)
        repeat(3) { session.recordPoint(Side.A) }
        scheduler.runUntilIdle()
        val replays = session.lifecycleReplays
        val state = session.state.value

        session.eventsCommitted(match.value)
        scheduler.runUntilIdle()
        assertEquals(replays, session.lifecycleReplays)
        assertSame(state, session.state.value)
    }

    @Test
    fun liveScoringDuringAPullLosesNoPoint() {
        session.start()
        val slot = Placeholders.id("opponent")
        val match =
            session.create(
                Presets.TENNIS_SINGLES_BO3.format(),
                Lineup(listOf(PlayerId("me")), listOf(slot), PlayerId("me")),
                NO_SERVE_TRACKING,
            )
        session.recordPoint(Side.A)

        // Zweites eigenes Handy weist zu (Pull), die Session hat das Signal noch nicht verarbeitet – hier wird weiter gezählt
        pull(match, PlayerAssigned(slot, Player(PlayerId("guest-bea"), "Bea"), PlayerId("me")))
        session.eventsCommitted(match.value)
        session.recordPoint(Side.B)
        session.recordPoint(Side.A)
        scheduler.runUntilIdle()

        assertEquals(3, points(match))
        assertEquals(data.events.scoreEvents(match).size, events(match), "Genesis, 3 eigene Punkte und die Zuweisung")
        assertEquals(5, events(match))
        assertEquals(3, data.events.scoreEvents(match).count { it.payload is PointWon })
    }

    @Test
    fun aCallForAMatchThatIsNotOpenChangesNothing() {
        session.start()
        val closed = session.create(Presets.TENNIS_SINGLES_BO3.format(), SINGLES, scorer = ScorerChoice.Device(otherPhone))
        session.closeMatch(closed)
        session.create(Presets.TENNIS_SINGLES_BO3.format(), SINGLES)
        scheduler.runUntilIdle()
        val replays = session.lifecycleReplays
        val state = session.state.value

        pull(closed, PointWon(Side.A))
        session.eventsCommitted(closed.value)
        scheduler.runUntilIdle()
        assertEquals(replays, session.lifecycleReplays)
        assertSame(state, session.state.value)
    }

    @Test
    fun aCallAfterCloseChangesNothing() {
        session.start()
        val match = session.create(Presets.TENNIS_SINGLES_BO3.format(), SINGLES, scorer = ScorerChoice.Device(otherPhone))
        val replays = session.lifecycleReplays
        session.close()
        val state = session.state.value

        pull(match, PointWon(Side.A))
        session.eventsCommitted(match.value)
        scheduler.runUntilIdle()
        assertEquals(replays, session.lifecycleReplays)
        assertSame(state, session.state.value)
    }
}
