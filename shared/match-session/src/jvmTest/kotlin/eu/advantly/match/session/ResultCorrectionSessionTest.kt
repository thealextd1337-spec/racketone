package eu.advantly.match.session

import eu.advantly.core.model.Discipline
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.CorrectedSet
import eu.advantly.core.scoring.GameServer
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.ResultCorrected
import eu.advantly.core.scoring.ScorerChanged
import eu.advantly.core.scoring.SetKind
import eu.advantly.core.scoring.SidePair
import eu.advantly.data.scoreEvents
import eu.advantly.sync.protocol.CompatibilityStatus
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Ergebnis-Korrektur nach Matchende an der Session (Protokoll 5). */
class ResultCorrectionSessionTest {
    private var pair: LifecyclePair? = null

    @AfterTest
    fun tearDown() {
        pair?.close()
    }

    private fun accountPair(
        phoneVersions: IntRange = 3..5,
        watchVersions: IntRange = phoneVersions,
    ): LifecyclePair = LifecyclePair(phoneVersions, watchVersions).also { pair = it }.apply { activate("alice") }

    /** 0.2.0 (Protokoll 4) gegen 0.3.0 (5) mit Konto: beidseitig Updatezustand, kein Event fließt. */
    @Test
    fun withAnAccountProtocolFourAndFiveAreAnUpdateStateInBothDirections() {
        for ((phone, watch) in listOf(3..5 to 3..4, 3..4 to 3..5)) {
            val pair = accountPair(phone, watch)
            val self =
                pair.phoneData.players
                    .self()!!
                    .id
            pair.phoneData.players.addGuest(GUEST, "Bea", 0)
            val match = pair.phone.create(Presets.BADMINTON_1GAME_15.format(Discipline.SINGLES), Lineup(listOf(self), listOf(GUEST), self))
            assertTrue(pair.phone.recordPoint(Side.A))
            pair.settle()
            val (phoneSees, watchSees) =
                if (phone.last == 5) {
                    CompatibilityStatus.PEER_TOO_OLD to CompatibilityStatus.PEER_TOO_NEW
                } else {
                    CompatibilityStatus.PEER_TOO_NEW to CompatibilityStatus.PEER_TOO_OLD
                }
            assertEquals(phoneSees, pair.phone.state.value.sync.versionMismatch, "Handy $phone")
            assertEquals(watchSees, pair.watch.state.value.sync.versionMismatch, "Uhr $watch")
            assertEquals(0L, pair.watchData.events.count(match.value), "kein Teilsync")
            pair.close()
            this.pair = null
        }
    }

    @Test
    fun phoneCorrectsAWatchScoredMatchWithoutShiftingItsEnd() {
        val pair = accountPair()
        val self =
            pair.phoneData.players
                .self()!!
                .id
        pair.phoneData.players.addGuest(GUEST, "Bea", 0)
        val match = pair.phone.create(Presets.BADMINTON_1GAME_15.format(Discipline.SINGLES), Lineup(listOf(self), listOf(GUEST), self))
        pair.settle()
        assertTrue(pair.watch.takeOverScoring())
        pair.settle()
        assertFalse(pair.phone.canCorrectResult(match), "läuft noch")
        repeat(15) { assertTrue(pair.watch.recordPoint(Side.A)) }
        pair.scheduler.advanceBy(60_000)
        pair.settle()
        val ended = pair.phoneData.matches.get(match)!!
        assertTrue(ended.isFinished)
        assertTrue(pair.phone.canCorrectResult(match))

        // unplausibel: kein Event
        val before = pair.phoneData.events.count(match.value)
        assertFalse(pair.phone.correctResult(match, listOf(CorrectedSet(SetKind.RALLY_GAME, SidePair(15, 14)))))
        assertFalse(pair.phone.correctGameServers(match, listOf(GameServer(0, 0, self))), "Badminton: keine Aufschläger je Spiel")
        assertEquals(before, pair.phoneData.events.count(match.value))

        assertTrue(pair.phone.correctResult(match, listOf(CorrectedSet(SetKind.RALLY_GAME, SidePair(15, 13)))))
        pair.settle()
        val log =
            pair.phoneData.events
                .scoreEvents(match)
                .map { it.payload }
        assertTrue(log.takeLast(2).first() is ScorerChanged && log.last() is ResultCorrected, "Übernahme vor der Korrektur: $log")
        for (data in listOf(pair.phoneData, pair.watchData)) {
            val score = data.matches.get(match)!!.score
            assertTrue(score.corrected)
            assertEquals(listOf(15 to 13), score.sets.map { it.a to it.b })
            assertEquals(Side.A, score.winner)
            assertEquals(ended.score.lastEventAtMs, score.lastEventAtMs, "Matchende bleibt beim letzten Punkt")
        }
    }

    @Test
    fun withoutAnAccountThereIsNoCorrection() {
        val pair = LifecyclePair(3..5).also { pair = it }
        val match = pair.phone.create(Presets.BADMINTON_1GAME_15.format(Discipline.SINGLES), SINGLES)
        repeat(15) { assertTrue(pair.phone.recordPoint(Side.A)) }
        pair.settle()
        assertFalse(pair.phone.canCorrectResult(match), "P1 spricht 3: eine Uhr mit 0.2.0 könnte die Korrektur nicht lesen")
        assertFalse(pair.phone.correctResult(match, listOf(CorrectedSet(SetKind.RALLY_GAME, SidePair(15, 13)))))
    }

    private companion object {
        val GUEST = PlayerId("guest-bea")
    }
}
