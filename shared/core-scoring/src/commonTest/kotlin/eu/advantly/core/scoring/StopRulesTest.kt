package eu.advantly.core.scoring

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.EventId
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.Side
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Regeltabelle „Beenden, wie es steht“ (`MatchEnded(STOPPED)`, 02 §9.27, F21): Sieger = führende Side nach Sätzen,
 * dann Spielen im laufenden Satz (Tennis/Padel), dann Punkten im laufenden Spiel bzw. Satz; Gleichstand = kein Sieger.
 */
class StopRulesTest {
    private fun MatchDriver.stop(side: Side? = null) = send(MatchEnded(EndReason.STOPPED, side))

    private data class Row(
        val name: String,
        val driver: () -> MatchDriver,
        val winner: Side?,
    )

    private val rows =
        listOf(
            // Tennis: Sätze vor Spielen vor Punkten
            Row("Tennis 0:0", { MatchDriver(Presets.TENNIS_SINGLES_BO3.format()) }, null),
            Row("Tennis 15:0", { MatchDriver(Presets.TENNIS_SINGLES_BO3.format()).points("A") }, Side.A),
            Row("Tennis 0:30", { MatchDriver(Presets.TENNIS_SINGLES_BO3.format()).points("BB") }, Side.B),
            Row("Tennis 30:30", { MatchDriver(Presets.TENNIS_SINGLES_BO3.format()).points("ABAB") }, null),
            Row("Tennis Spiele 1:0, Punkte 0:30", { MatchDriver(Presets.TENNIS_SINGLES_BO3.format()).points("AAAA BB") }, Side.A),
            Row(
                "Tennis Spiele 1:1, Punkte AD-40 für B",
                { MatchDriver(Presets.TENNIS_SINGLES_BO3.format()).points("AAAA BBBB ABABABB") },
                Side.B,
            ),
            Row(
                "Tennis Sätze 1:0, Satz 2 Spiele 0:2",
                { MatchDriver(Presets.TENNIS_SINGLES_BO3.format()).points("AAAA".repeat(6) + "BBBB".repeat(2)) },
                Side.A,
            ),
            Row(
                "Tennis Tiebreak 6:6, Tiebreak-Punkte 2:3",
                { MatchDriver(Presets.TENNIS_SINGLES_BO3.format()).points("AAAABBBB".repeat(6) + "AABBB") },
                Side.B,
            ),
            // Padel (Golden Point): 40:40 = Gleichstand
            Row("Padel 40:40", { MatchDriver(Presets.PADEL_GOLDEN_BO3.format()).points("ABABAB") }, null),
            // Pickleball Rally: Punkte im laufenden Spiel
            Row("Pickleball Rally 3:5", { MatchDriver(Presets.PICKLEBALL_RALLY_BO3_15.format()).points("AAABBBBB") }, Side.B),
            Row(
                "Pickleball Rally Spiele 1:0, Punkte 0:9",
                { MatchDriver(Presets.PICKLEBALL_RALLY_BO3_15.format()).points("A".repeat(15) + "B".repeat(9)) },
                Side.A,
            ),
            // Badminton und Tischtennis: Punkte im Satz, gewonnene Sätze zuerst
            Row("Badminton 11:10", { MatchDriver(Presets.BADMINTON_SINGLES_BO3_21.format()).points("AB".repeat(10) + "A") }, Side.A),
            Row(
                "Badminton Sätze 0:1, Satz 2 20:0",
                { MatchDriver(Presets.BADMINTON_SINGLES_BO3_21.format()).points("B".repeat(21) + "A".repeat(20)) },
                Side.B,
            ),
            Row("Tischtennis 7:7", { MatchDriver(Presets.TABLE_TENNIS_SINGLES_BO5.format()).points("AB".repeat(7)) }, null),
            Row("Tischtennis 3:4", { MatchDriver(Presets.TABLE_TENNIS_SINGLES_BO5.format()).points("ABABABB") }, Side.B),
        )

    @Test
    fun winnerIsTheLeadingSideBySetsThenGamesThenPoints() {
        for (row in rows) {
            val m = row.driver()
            val before = m.state
            m.stop()
            assertEquals(MatchStatus.FINISHED, m.state.status, row.name)
            assertEquals(MatchResult(row.winner, ResultReason.STOPPED), m.state.result, row.name)
            assertEquals(listOf(Effect.MATCH_ENDED), m.state.effects, row.name)
            // Stand bleibt eingefroren stehen (wie Aufgabe/Abbruch), kein Aufschlag, keine Flags, kein Folgesatz
            assertEquals(before.current?.points, m.state.current?.points, row.name)
            assertEquals(before.current?.games, m.state.current?.games, row.name)
            assertEquals(before.setsWon, m.state.setsWon, row.name)
            assertNull(m.state.serve, row.name)
            assertEquals(emptyList(), m.state.statusFlags, row.name)
            assertNull(m.state.continuation, row.name)
        }
    }

    @Test
    fun sideIsIgnoredAndUndoReopensTheMatch() {
        val m = MatchDriver(Presets.TENNIS_SINGLES_BO3.format()).points("AB")
        m.stop(Side.B)
        assertEquals(MatchResult(null, ResultReason.STOPPED), m.state.result, "side ohne Bedeutung, 15:15 = Gleichstand")
        assertEquals(m.events.last().id, m.engineState.undoTarget, "Beenden ist UI-Undo-Ziel")
        m.undo()
        assertEquals(MatchStatus.IN_PROGRESS, m.state.status)
        assertNull(m.state.result)
        m.point(Side.A)
        assertEquals("30", checkNotNull(m.state.current).display.a)
    }

    @Test
    fun finishedMatchIgnoresAFurtherStop() {
        val m = MatchDriver(Presets.TENNIS_SINGLES_BO3.format()).points("A")
        m.stop()
        m.stop()
        assertEquals(listOf(Effect.IGNORED), m.state.effects)
        assertEquals(MatchResult(Side.A, ResultReason.STOPPED), m.state.result)
    }

    @Test
    fun stopDuringTheSetEndQuestionCountsSetsOnly() {
        // OPEN: Satzende-Frage offen (kein laufender Satz) – nur die Sätze zählen; ohne Satzführung kein Sieger
        val m = MatchDriver(Presets.TENNIS_OPEN.format()).points("AAAA".repeat(6))
        assertEquals(MatchStatus.AWAITING_DECISION, m.state.status)
        m.stop()
        assertEquals(MatchResult(Side.A, ResultReason.STOPPED), m.state.result)
        assertNull(m.state.current)
    }

    @Test
    fun pausedMatchCanBeStopped() {
        val m = MatchDriver(Presets.PADEL_GOLDEN_BO3.format()).points("BBA")
        m.send(Paused)
        m.stop()
        assertEquals(MatchResult(Side.B, ResultReason.STOPPED), m.state.result)
    }

    @Test
    fun stopFromANonScorerIsRejectedByRole() {
        val created = MatchCreated(Presets.TENNIS_SINGLES_BO3.format(), lineupFor(Presets.TENNIS_SINGLES_BO3.format().discipline))
        val withScorer = created.copy(scorerDeviceId = DeviceId("watch"))
        val genesis = envelope("g", "watch", 1, withScorer)
        val stop = envelope("s", "phone", 2, MatchEnded(EndReason.STOPPED))
        val state = ScoringEngine.replay(listOf(genesis, stop)).state
        assertEquals(MatchStatus.IN_PROGRESS, state.status)
        assertEquals(listOf(stop.id), state.rejectedByRole)
        assertNotNull(state.current)
    }

    private fun envelope(
        id: String,
        device: String,
        lamport: Long,
        payload: EventPayload,
    ) = ScoreEvent(
        EventId(id),
        MatchId("m"),
        DeviceId(device),
        lamport,
        lamport,
        lamport * 1_000,
        null,
        payload,
    )
}
