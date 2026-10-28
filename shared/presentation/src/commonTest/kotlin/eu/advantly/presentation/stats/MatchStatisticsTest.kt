package eu.advantly.presentation.stats

import eu.advantly.core.model.Discipline
import eu.advantly.core.model.Player
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.ScoringEngine
import eu.advantly.core.scoring.SidePair
import eu.advantly.presentation.match.NO_SERVE_TRACKING
import eu.advantly.presentation.match.play
import eu.advantly.presentation.match.setupFor
import eu.advantly.presentation.match.testStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MatchStatisticsTest {
    @Test
    fun placeholderKeepsPointAttributionAndOnlyResolvedPersonGetsPersonalStatistics() {
        val store = testStore()
        val base = store.setupFor(Presets.PADEL_GOLDEN_BO3.format())
        val slot = base.lineup.a.last()
        val setup = base.copy(players = base.players.map { if (it.id == slot) Player(slot, "", isPlaceholder = true) else it })
        val id = store.start(setup)
        assertTrue(store.chooseServer(slot, assertNotNull(store.active.value!!.serverChoiceContext)))
        store.play("A")
        val record = assertNotNull(store.record(id))
        assertEquals(slot, ScoringEngine.pointHistory(record.events).single().server)
        val stats = MatchStatistics.compute(record)
        assertTrue(stats.servers.isEmpty())
        assertEquals(Ratio(1, 1), stats.serve.a.servicePoints)
        val person = store.addGuest("Assigned partner")
        val resolved = record.copy(summary = record.summary.copy(setup = setup.copy(assignedPlayers = mapOf(slot to person))))
        val personal = MatchStatistics.compute(resolved)
        assertEquals(person.id, personal.servers.single().player)
        assertEquals(
            Ratio(1, 1),
            personal.servers
                .single()
                .stats.servicePoints,
        )
        assertEquals(stats.serve, personal.serve)
        assertEquals(record.events, resolved.events)
    }

    @Test
    fun doublesRotationAndForwardCorrectionKeepHistoricalPlayerStatistics() {
        for (preset in listOf(
            Presets.TENNIS_DOUBLES_MTB10,
            Presets.PADEL_GOLDEN_BO3,
            Presets.TENNIS_TIEBREAK_ONLY_7,
            Presets.PADEL_TIEBREAK_ONLY_10,
        )) {
            val store = testStore()
            val setup = store.setupFor(preset.format(Discipline.DOUBLES)).copy(meta = NO_SERVE_TRACKING)
            val id = store.start(setup)
            val first = assertNotNull(store.active.value!!.serverChoiceContext)
            val partner = setup.lineup.a.last()
            val opponent = setup.lineup.b.last()
            assertTrue(store.chooseServer(partner, first))
            val standard = first.gameKind == eu.advantly.core.scoring.GameKind.STANDARD
            store.play(if (standard) "AAAA" else "A")
            assertTrue(store.chooseServer(opponent, assertNotNull(store.active.value!!.serverChoiceContext)))
            store.play(if (standard) "BBBB" else "BB")
            val openingServers = ScoringEngine.pointHistory(store.record(id)!!.events).map { it.server }
            assertEquals(List(if (standard) 4 else 1) { partner } + List(if (standard) 4 else 2) { opponent }, openingServers)
            val before = assertNotNull(store.record(id))
            assertTrue(store.correctServer(setup.lineup.b.first(), retroactive = false))
            assertEquals(openingServers, ScoringEngine.pointHistory(store.record(id)!!.events).map { it.server })
            store.play("B")
            val record = assertNotNull(store.record(id))
            assertEquals(setup.lineup.b.first(), ScoringEngine.pointHistory(record.events).last().server)
            val stats = MatchStatistics.compute(record)
            assertFalse(stats.serveInfoRecorded)
            assertNull(stats.serve.a.firstServeIn.percent)
            assertEquals(
                if (standard) 4 else 1,
                stats.servers
                    .first { it.player == partner }
                    .stats.servicePoints.total,
            )
            assertEquals(
                if (standard) 4 else 2,
                stats.servers
                    .first { it.player == opponent }
                    .stats.servicePoints.total,
            )
            assertEquals(
                1,
                stats.servers
                    .first { it.player == setup.lineup.b.first() }
                    .stats.servicePoints.total,
            )
            store.undo()
            assertEquals(
                MatchStatistics.compute(before).copy(netDurationMs = 0),
                MatchStatistics.compute(store.record(id)!!).copy(netDurationMs = 0),
            )
            store.redo()
            assertEquals(stats.copy(netDurationMs = 0), MatchStatistics.compute(store.record(id)!!).copy(netDurationMs = 0))
            assertEquals(record.finalState, ScoringEngine.fold(record.events.reversed()))
        }
    }

    @Test
    fun tennisPointsServeReturnAndBreakPoints() {
        val store = testStore()
        val id = store.start(store.setupFor(Presets.TENNIS_ONE_SET.format(), firstServerSide = Side.B))
        // Spiel 1 (B schlägt auf): A macht 3 Breakbälle, B wehrt einen ab, A verwertet den zweiten
        store.play("AAABA")
        // Spiel 2 (A schlägt auf): zu null gehalten
        store.play("AAAA")
        val stats = MatchStatistics.compute(store.record(id)!!)
        assertEquals(SidePair(8, 1), stats.pointsWon)
        assertEquals(9, stats.totalPoints)
        assertEquals(Ratio(4, 4), stats.servicePoints.a)
        assertEquals(Ratio(1, 5), stats.servicePoints.b)
        assertEquals(Ratio(4, 5), stats.returnPoints.a)
        assertEquals(Ratio(1, 2), stats.breakPoints.a)
        assertEquals(50, stats.breakPoints.a.percent)
        assertEquals(Ratio(0, 0), stats.breakPoints.b)
        assertNull(stats.breakPoints.b.percent)
        assertEquals(SidePair(5, 1), stats.longestStreak)
        assertEquals(SidePair(2, 0), stats.games)
        assertEquals(true, stats.netDurationMs > 0)
    }

    @Test
    fun rallySportsHaveNoGamesButCountSetPoints() {
        val store = testStore()
        val id = store.start(store.setupFor(Presets.BADMINTON_1GAME_15.format()))
        store.play("A".repeat(14) + "B" + "A")
        val stats = MatchStatistics.compute(store.record(id)!!)
        assertNull(stats.games)
        assertEquals(Ratio(1, 2), stats.setPoints.a)
        assertEquals(SidePair(15, 1), stats.pointsWon)
    }

    @Test
    fun undonePointsAndPausesDoNotCount() {
        val store = testStore()
        val id = store.start(store.setupFor(Presets.TENNIS_ONE_SET.format()).copy(meta = NO_SERVE_TRACKING))
        store.play("AB")
        store.undo()
        store.pause()
        store.resume()
        val stats = MatchStatistics.compute(store.record(id)!!)
        assertEquals(SidePair(1, 0), stats.pointsWon)
        // StepClock: 1 s je Event; Genesis → letztes Event = 5 s, davon 1 s Pause
        assertEquals(4_000, stats.netDurationMs)
    }

    @Test
    fun goldenPointAndNoAdCountTheDecidingBreakChanceOnce() {
        for (preset in listOf(Presets.PADEL_GOLDEN_BO3, Presets.TENNIS_DOUBLES_MTB10)) {
            val store = testStore()
            val id = store.start(store.setupFor(preset.format()))
            store.play("ABABAB")
            assertEquals(Ratio(0, 0), MatchStatistics.compute(store.record(id)!!).breakPoints.b)
            store.play("A")
            val stats = MatchStatistics.compute(store.record(id)!!)
            assertEquals(Ratio(0, 1), stats.breakPoints.b)
            assertEquals(Ratio(1, 1), stats.breakPointsSaved.a)
            assertEquals(Ratio(1, 1), stats.decidingPoints.a)
            assertEquals(Ratio(0, 1), stats.breaks.b)
        }
    }

    @Test
    fun doublesTennisServerChoicesDriveBreaksAndPlayerServiceGames() {
        val store = testStore()
        val setup = store.setupFor(Presets.TENNIS_DOUBLES_MTB10.format())
        val id = store.start(setup)
        val mia = setup.lineup.a.last()
        val tom = setup.lineup.b.last()
        kotlin.test.assertTrue(store.chooseServer(mia))
        store.play("BBBB")
        kotlin.test.assertTrue(store.chooseServer(tom))
        store.play("BBBB" + "A")
        val stats = MatchStatistics.compute(store.record(id)!!)
        assertEquals(listOf(Side.A, Side.B, Side.A), stats.progression.map { it.server })
        assertEquals(Ratio(1, 1), stats.breaks.b)
        assertEquals(Ratio(0, 1), stats.breaks.a)
        assertEquals(
            Ratio(0, 1),
            stats.servers
                .first { it.player == mia }
                .stats.serviceGames,
        )
        assertEquals(
            Ratio(1, 1),
            stats.servers
                .first { it.player == tom }
                .stats.serviceGames,
        )
    }

    @Test
    fun singlesBestOfThreeReachesDecidingMatchTiebreakWithoutInventingBreaks() {
        val store = testStore()
        // Das Einzel-Preset hat regulär einen dritten Satz; die erlaubte Formatwahl ersetzt ihn durch MTB10.
        val format =
            Presets.TENNIS_SINGLES_BO3.format().copy(
                presetId = null,
                decidingSet = eu.advantly.core.model.MatchTiebreakFormat.MTB10,
            )
        val id = store.start(store.setupFor(format))
        store.play("A".repeat(24) + "B".repeat(24) + "AB".repeat(8) + "AA")
        val record = store.record(id)!!
        val stats = MatchStatistics.compute(record)
        assertEquals(eu.advantly.core.scoring.MatchStatus.FINISHED, record.finalState.status)
        assertEquals(SidePair(2, 1), record.finalState.setsWon)
        assertEquals(SidePair(6, 6), stats.games)
        assertEquals(13, stats.progression.size)
        val tiebreak = stats.progression.last()
        assertEquals(eu.advantly.core.scoring.GameKind.MATCH_TIEBREAK, tiebreak.kind)
        assertEquals(SidePair(10, 8), tiebreak.pointsWon)
        kotlin.test.assertFalse(tiebreak.isBreak)
        assertNull(tiebreak.server)
        assertEquals(Ratio(3, 6), stats.breaks.a)
        assertEquals(Ratio(3, 6), stats.breaks.b)
    }
}
