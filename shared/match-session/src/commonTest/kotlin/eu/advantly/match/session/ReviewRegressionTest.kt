package eu.advantly.match.session

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.EndReason
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.SidePair
import eu.advantly.data.SetScore
import eu.advantly.device.link.testing.ManualScheduler
import eu.advantly.sync.protocol.DeviceSeq
import eu.advantly.sync.protocol.Events
import eu.advantly.sync.protocol.MatchMetaMessage
import eu.advantly.sync.protocol.PeerKind
import eu.advantly.sync.protocol.Summary
import eu.advantly.sync.protocol.VersionVector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Regressionstests zu Review-Runde 1 (PR #13), nach dem Reproducer `Review.kt`. */
class ReviewRegressionTest {
    private val format = Presets.TENNIS_SINGLES_BO3.format()

    /** Befund 1: Kommt der letzte Teil (`more = false`) vor den früheren, darf nichts ungefaltet liegen bleiben. */
    @Test
    fun reorderedMultipartDeliveryIsAppliedCompletely() {
        val a = WireNode("a", 11)
        val b = WireNode("b", 12)
        val id = a.session.create(format, SINGLES, NO_SERVE_TRACKING)
        b.link.deliver(Events(id, a.events()))
        a.points(Side.A, 3)
        val e = a.events().drop(1)
        b.link.deliver(Events(id, e.takeLast(1), more = false))
        b.link.deliver(Events(id, e.dropLast(1), more = true))
        b.scheduler.advanceBy(120_000)
        assertEquals(a.events(), b.events())
        assertEquals(a.match.state, b.match.state)
        assertEquals(
            SidePair(3, 0),
            b.match.state.current
                ?.points,
        )
        assertEquals(4, b.match.eventCount)
        // identische Vektoren → nichts mehr nachzufordern, aber auch nichts mehr offen
        val remote =
            VersionVector.fromSequences(
                a.data.events
                    .sequences(id.value)
                    .map { DeviceSeq(DeviceId(it.deviceId), it.deviceSeq) },
            )
        b.link.deliver(Summary(mapOf(id.value to remote)))
        assertEquals(
            remote,
            b.link.sent
                .filterIsInstance<Summary>()
                .last()
                .vector(id),
        )
    }

    /** Befund 5: Folge-Match wird geöffnet, auch wenn der Abschluss des Vorgängers erst danach ankommt. */
    @Test
    fun followUpIsOpenedWhenThePredecessorEndsLater() {
        val a = WireNode("a", 1)
        val b = WireNode("b", 2)
        val old = a.session.create(format, SINGLES, NO_SERVE_TRACKING)
        b.link.deliver(Events(old, a.events()))
        a.session.endMatch()
        val end = a.events().last()
        val next = assertNotNull(a.session.createFollowUp())
        val meta =
            a.link.sent
                .filterIsInstance<MatchMetaMessage>()
                .last()
        b.link.deliver(meta)
        b.link.deliver(Events(next, a.events()))
        assertEquals(old, b.match.id, "Vorgänger läuft noch")
        b.link.deliver(Events(old, listOf(end)))
        assertEquals(next, b.match.id)
        assertEquals(old, b.match.previousMatchId)
        assertEquals(
            MatchStatus.FINISHED,
            b.data.matches
                .get(old)
                ?.status,
        )
    }

    /** Befund 6: Bei Aufgabe/Abbruch bleibt der laufende Satz im Ergebnis-Cache erhalten. */
    @Test
    fun resultCacheKeepsTheFrozenSetOnRetirement() {
        val a = WireNode("a", 81)
        val id = a.session.create(format, SINGLES, NO_SERVE_TRACKING)
        a.points(Side.A, 9)
        assertTrue(a.session.endMatch(EndReason.RETIRED, Side.A))
        val cached = assertNotNull(a.data.matches.get(id)).score
        assertEquals(
            SidePair(2, 0),
            a.match.state.current
                ?.games,
        )
        assertEquals(SetScore(2, 0), cached.current)
        assertEquals(emptyList(), cached.sets)
        assertEquals(Side.B, cached.winner)
    }

    /** Thread-Vorgabe: öffentliche Aufrufe außerhalb des Scheduler-Threads schlagen fehl. */
    @Test
    fun publicCallsOffTheSchedulerThreadFail() {
        val session =
            MatchSession(
                testLocalData(),
                null,
                ManualScheduler(),
                SessionConfig(DeviceId("x"), PeerKind.PHONE),
                threadGuard = { false },
            )
        assertFailsWith<IllegalStateException> { session.create(format, SINGLES, NO_SERVE_TRACKING) }
        assertFailsWith<IllegalStateException> { session.recordPoint(Side.A) }
    }
}
