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

    /**
     * Review P1-2: Uhr 0.2.0 (v4) und Handy 0.3.0 (v5) sind gesperrt; beide zählen währenddessen weiter. Nach dem Update
     * der Uhr kommen beide Matches vollständig auf beiden Seiten an, und neue Punkte fließen wieder live.
     */
    @Test
    fun afterTheWatchUpdateEverythingScoredDuringTheLockArrives() {
        val pair = accountPair(3..5, 3..4)
        val self =
            pair.phoneData.players
                .self()!!
                .id
        pair.phoneData.players.addGuest(GUEST, "Bea", 0)
        val format = Presets.BADMINTON_1GAME_15.format(Discipline.SINGLES)
        val phoneMatch = pair.phone.create(format, Lineup(listOf(self), listOf(GUEST), self))
        repeat(3) { assertTrue(pair.phone.recordPoint(Side.A)) }
        val watchMatch = pair.watch.create(format, SINGLES)
        repeat(4) { assertTrue(pair.watch.recordPoint(Side.B)) }
        pair.settle()
        assertEquals(CompatibilityStatus.PEER_TOO_OLD, pair.phone.state.value.sync.versionMismatch)
        assertEquals(0L, pair.watchData.events.count(phoneMatch.value), "gesperrt: kein Teilsync")
        assertEquals(0L, pair.phoneData.events.count(watchMatch.value), "gesperrt: kein Teilsync")

        pair.watchVersions = 3..5
        pair.restartWatch()
        repeat(3) { pair.scheduler.advanceBy(31_000) }
        assertEquals(null, pair.phone.state.value.sync.versionMismatch)
        assertEquals(null, pair.watch.state.value.sync.versionMismatch)
        for (match in listOf(phoneMatch, watchMatch)) {
            assertEquals(pair.phoneData.events.count(match.value), pair.watchData.events.count(match.value), "$match vollständig")
            assertEquals(
                pair.phoneData.matches
                    .get(match)
                    ?.score,
                pair.watchData.matches
                    .get(match)
                    ?.score,
                "$match gleicher Stand",
            )
        }
        assertEquals(4L, pair.phoneData.events.count(phoneMatch.value), "Genesis + 3 Punkte")
        assertTrue(pair.phoneData.events.count(watchMatch.value) >= 5, "Genesis + 4 Punkte (ggf. Übergabe-Events)")
        // danach normaler Live-Betrieb
        pair.phone.resume(phoneMatch)
        assertTrue(pair.phone.recordPoint(Side.A))
        pair.settle()
        assertEquals(5L, pair.watchData.events.count(phoneMatch.value))
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
        assertFalse(pair.phone.correctResult(match, listOf(CorrectedSet(SetKind.RALLY_GAME, SidePair(15, 14))), null))
        assertFalse(pair.phone.correctResult(match, null, listOf(GameServer(0, 0, self, Side.A))), "Badminton: keine Aufschläger je Spiel")
        assertEquals(before, pair.phoneData.events.count(match.value))

        assertTrue(pair.phone.correctResult(match, listOf(CorrectedSet(SetKind.RALLY_GAME, SidePair(15, 13))), null))
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
        assertFalse(pair.phone.correctResult(match, listOf(CorrectedSet(SetKind.RALLY_GAME, SidePair(15, 13))), null))
    }

    private companion object {
        val GUEST = PlayerId("guest-bea")
    }
}
