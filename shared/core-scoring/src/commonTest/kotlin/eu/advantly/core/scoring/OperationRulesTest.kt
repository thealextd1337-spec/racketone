package eu.advantly.core.scoring

import eu.advantly.core.model.Discipline
import eu.advantly.core.model.End
import eu.advantly.core.model.MatchTiebreakFormat
import eu.advantly.core.model.RegularSet
import eu.advantly.core.model.Side
import eu.advantly.core.model.TiebreakFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regel-Tabellen „Bedienung & Statistik“ (Feldtest 2026-10-15, 02 §5.2, §6.5.5, §7, §7.2, §7.3): Punkt einer Seite
 * abziehen, Aufschlägerwahl im Doppel, Aufschlag-Info je Punkt, Games pro Satz 1–6, Nur-Tiebreak-Formate und
 * Tiebreak-Sätze im Satzende-Dialog.
 */
class OperationRulesTest {
    private val MatchDriver.cur get() = checkNotNull(state.current)
    private val MatchDriver.srv get() = checkNotNull(state.serve)

    private fun MatchDriver.winGames(
        side: Side,
        n: Int,
    ) = apply { repeat(n) { points(if (side == Side.A) "AAAA" else "BBBB") } }

    // --- 1. Punkt einer Seite abziehen (§7.3) -------------------------------------------------------------------------

    @Test
    fun removeLastPointOfSideAcrossSetBoundary() {
        val m = MatchDriver(Presets.TENNIS_SINGLES_BO3.format()).winGames(Side.A, 6)
        val setPoint = m.events.last().id
        m.points("BB")
        assertEquals(1, m.state.sets.size)
        assertEquals(SidePair(0, 2), m.cur.points)
        assertEquals(setPoint, ScoringEngine.lastEffectivePointOf(m.engineState, Side.A), "auch nach Punkten des Gegners")

        m.send(Undo(setPoint))
        // Satzball weg: Satz 1 läuft bei 5:0 weiter, die beiden B-Punkte zählen jetzt in Spiel 6 (B schlägt auf)
        assertEquals(emptyList(), m.state.sets)
        assertEquals(SidePair(0, 0), m.state.setsWon)
        assertEquals(SidePair(5, 0), m.cur.games)
        assertEquals(SidePair(3, 2), m.cur.points)
        assertEquals(Side.B, m.srv.side)
        assertEquals("30-40", m.state.scoreCall)
        assertEquals(listOf(Effect.UNDONE), m.state.effects)

        val lastB = assertNotNull(ScoringEngine.lastEffectivePointOf(m.engineState, Side.B))
        m.send(Undo(lastB))
        assertEquals(SidePair(3, 1), m.cur.points)
        // Redo = Undo auf das Undo stellt den Punkt wieder her
        m.send(Undo(m.events.last().id))
        assertEquals(SidePair(3, 2), m.cur.points)
    }

    @Test
    fun removeLastPointOnlyWhenSideHasOne() {
        val m = MatchDriver(Presets.TENNIS_SINGLES_BO3.format()).points("BB")
        assertNull(ScoringEngine.lastEffectivePointOf(m.engineState, Side.A))
        // Pickleball Side-Out: ein gewonnener Ballwechsel ohne Punkt ist kein „Punkt der Seite“
        val pb = MatchDriver(Presets.PICKLEBALL_SINGLES_11_SIDEOUT.format()).points("AB")
        assertEquals(pb.events.first().id, ScoringEngine.lastEffectivePointOf(pb.engineState, Side.A))
        assertNull(ScoringEngine.lastEffectivePointOf(pb.engineState, Side.B))
        assertEquals(2, pb.engineState.pointsPlayed)
    }

    // --- 2. Aufschlägerwahl im Doppel (§6.5.5) -----------------------------------------------------------------------

    @Test
    fun doublesServerChoiceGameOneAndTwoFixesRotation() {
        val m = MatchDriver(Presets.TENNIS_BO3_MTB10.format(Discipline.DOUBLES))
        assertEquals(ServerChoice(1, Side.A, listOf(p("A1"), p("A2"), p("B1"), p("B2")), null), m.state.serverChoice)

        m.send(ServerChosen(0, Side.A, p("A2")))
        assertEquals(emptyList(), m.state.effects)
        assertEquals(p("A2"), m.srv.player)
        assertEquals(ServerChoice(1, Side.A, listOf(p("A2"), p("A1"), p("B1"), p("B2")), p("A2")), m.state.serverChoice)

        m.point(Side.A)
        assertNull(m.state.serverChoice, "Game 1 läuft, Team A hat aufgeschlagen")
        m.send(ServerChosen(0, Side.A, p("A1")))
        assertEquals(listOf(Effect.IGNORED), m.state.effects, "A hat im Satz schon aufgeschlagen")
        m.points("AAA")
        assertEquals(ServerChoice(2, Side.B, listOf(p("B1"), p("B2")), null), m.state.serverChoice)
        m.send(ServerChosen(1, Side.B, p("B2")))
        assertEquals(listOf(Effect.IGNORED), m.state.effects, "falscher Satzindex")
        m.send(ServerChosen(0, Side.B, p("A1")))
        assertEquals(listOf(Effect.IGNORED), m.state.effects, "Spieler gehört nicht zur Side")
        m.send(ServerChosen(0, Side.B, p("B2")))
        assertEquals(ServerChoice(2, Side.B, listOf(p("B2"), p("B1")), p("B2")), m.state.serverChoice)

        val servers = mutableListOf(m.srv.player.value)
        repeat(4) {
            m.holdGames(1)
            servers += m.srv.player.value
        }
        assertEquals(listOf("B2", "A1", "B1", "A2", "B2"), servers, "Rotation für den Satz festgelegt")
        assertNull(m.state.serverChoice)
    }

    @Test
    fun doublesServerChoiceGameOneMaySwitchOpeningTeam() {
        val m = MatchDriver(Presets.PADEL_GOLDEN_BO3.format())
        m.send(ServerChosen(0, Side.B, p("B2")))
        assertEquals(Side.B, m.srv.side)
        assertEquals(p("B2"), m.srv.player)
        assertEquals(ServerChoice(1, Side.B, listOf(p("B2"), p("B1"), p("A1"), p("A2")), p("B2")), m.state.serverChoice)
        m.points("BBBB")
        assertEquals(ServerChoice(2, Side.A, listOf(p("A1"), p("A2")), null), m.state.serverChoice)
        // übersprungen: Default = bisherige Reihenfolge
        m.point(Side.A)
        assertNull(m.state.serverChoice)
        assertEquals(p("A1"), m.srv.player)
    }

    @Test
    fun doublesServerChoiceOpensAgainEachSet() {
        val m = MatchDriver(Presets.TENNIS_BO3_MTB10.format(Discipline.DOUBLES)).winGames(Side.A, 6)
        assertEquals(1, m.cur.setIndex)
        assertEquals(ServerChoice(1, Side.A, listOf(p("A1"), p("A2"), p("B1"), p("B2")), null), m.state.serverChoice)
        m.send(ServerChosen(0, Side.A, p("A2")))
        assertEquals(listOf(Effect.IGNORED), m.state.effects, "Wahl für Satz 1 gilt nicht mehr")
        m.send(ServerChosen(1, Side.A, p("A2")))
        assertEquals(p("A2"), m.srv.player)
        // Undo des Satzballs: die Wahl für Satz 2 steht davor nicht mehr im laufenden Satz → übersprungen
        val setPoint = checkNotNull(ScoringEngine.lastEffectivePointOf(m.engineState, Side.A))
        m.send(Undo(setPoint))
        assertEquals(0, m.cur.setIndex)
        assertTrue(m.events[m.events.size - 2].id in m.state.ignoredEvents)
    }

    /** Review R1: Eine Korrektur, die den gewählten Aufschläger ändert, hebt die Wahl auf; erneut wählen setzt ihn wieder ein. */
    @Test
    fun correctionAfterServerChoiceClearsTheStaleChoice() {
        val m = MatchDriver(Presets.TENNIS_BO3_MTB10.format(Discipline.DOUBLES))
        m.send(ServerChosen(0, Side.A, p("A2")))
        m.send(Correction.servingOrder(Side.A, listOf(p("A1"), p("A2"))))
        assertEquals(p("A1"), m.srv.player)
        assertEquals(ServerChoice(1, Side.A, listOf(p("A1"), p("A2"), p("B1"), p("B2")), null), m.state.serverChoice)
        m.send(ServerChosen(0, Side.A, p("A2")))
        assertEquals(p("A2"), m.srv.player)
        assertEquals(p("A2"), m.state.serverChoice?.chosen)
        // Correction(SERVER) auf denselben Spieler lässt die Wahl stehen, auf einen anderen hebt sie auf
        m.send(Correction.server(p("A2")))
        assertEquals(p("A2"), m.state.serverChoice?.chosen)
        m.send(Correction.server(p("B1")))
        assertEquals(ServerChoice(1, Side.B, listOf(p("B1"), p("B2"), p("A2"), p("A1")), null), m.state.serverChoice)

        // Game 2: Wahl von B2, danach SERVING_ORDER [B1, B2] → Wahl aufgehoben
        val g = MatchDriver(Presets.PADEL_GOLDEN_BO3.format()).winGames(Side.A, 1)
        g.send(ServerChosen(0, Side.B, p("B2")))
        g.send(Correction.servingOrder(Side.B, listOf(p("B1"), p("B2"))))
        assertEquals(p("B1"), g.srv.player)
        assertEquals(ServerChoice(2, Side.B, listOf(p("B1"), p("B2")), null), g.state.serverChoice)
        // Wahl während Game 1 (Fenster noch nicht offen) bleibt erhalten, solange sie wirksam ist
        val early = MatchDriver(Presets.PADEL_GOLDEN_BO3.format()).points("A")
        early.send(ServerChosen(0, Side.B, p("B2")))
        early.points("AAA")
        assertEquals(ServerChoice(2, Side.B, listOf(p("B2"), p("B1")), p("B2")), early.state.serverChoice)
    }

    @Test
    fun serverChoiceInTiebreakOnlyMatch() {
        val m = MatchDriver(Presets.TENNIS_TIEBREAK_ONLY_7.format(Discipline.DOUBLES))
        assertEquals(1, m.state.serverChoice?.game)
        m.send(ServerChosen(0, Side.B, p("B1")))
        m.point(Side.A)
        assertEquals(ServerChoice(2, Side.A, listOf(p("A1"), p("A2")), null), m.state.serverChoice)
        m.send(ServerChosen(0, Side.A, p("A2")))
        val servers = mutableListOf("B1", m.srv.player.value)
        repeat(6) {
            m.point(if (it % 2 == 0) Side.B else Side.A)
            servers += m.srv.player.value
        }
        assertEquals(listOf("B1", "A2", "A2", "B2", "B2", "A1", "A1", "B1"), servers)
    }

    @Test
    fun secondChoiceSurvivesUndoAcrossGameBoundaryAndRedo() {
        val m = MatchDriver(Presets.PADEL_GOLDEN_BO3.format())
        m.send(ServerChosen(0, Side.A, p("A2")))
        m.points("AAA")
        val gameBall = m.point(Side.A)
        m.send(ServerChosen(0, Side.B, p("B2")))
        val secondGamePoint = m.point(Side.B)
        val undoPoint = m.send(Undo(secondGamePoint))
        assertEquals(SidePair(1, 0), m.cur.games)
        assertEquals(SidePair(0, 0), m.cur.points)
        assertEquals(ServerChoice(2, Side.B, listOf(p("B2"), p("B1")), p("B2")), m.state.serverChoice)

        val undoGame = m.send(Undo(gameBall))
        assertEquals(SidePair(0, 0), m.cur.games)
        assertEquals(SidePair(3, 0), m.cur.points)
        assertEquals(p("A2"), m.srv.player)
        assertNull(m.state.serverChoice, "zweite Wahl bleibt als frühzeitige Wahl gültig")
        assertEquals(emptyList(), m.state.ignoredEvents)
        m.send(Undo(undoGame))
        assertEquals(p("B2"), m.state.serverChoice?.chosen)
        m.send(Undo(undoPoint))
        assertEquals(SidePair(0, 1), m.cur.points)
        assertEquals(p("B2"), m.srv.player)
        assertNull(m.state.serverChoice)
    }

    @Test
    fun nextSetChoiceIsIgnoredAfterSetBallUndoAndReactivatedByRedo() {
        val m = MatchDriver(Presets.TENNIS_BO3_MTB10.format(Discipline.DOUBLES)).winGames(Side.A, 6)
        val setBall = m.events.last().id
        val choice = m.send(ServerChosen(1, Side.B, p("B2")))
        val undo = m.send(Undo(setBall))
        assertEquals(0, m.cur.setIndex)
        assertEquals(SidePair(5, 0), m.cur.games)
        assertEquals(SidePair(3, 0), m.cur.points)
        assertEquals(p("B1"), m.srv.player)
        assertEquals(listOf(choice), m.state.ignoredEvents)
        m.send(Undo(undo))
        assertEquals(1, m.cur.setIndex)
        assertEquals(SidePair(0, 0), m.cur.points)
        assertEquals(p("B2"), m.srv.player)
        assertEquals(p("B2"), m.state.serverChoice?.chosen)
        assertEquals(emptyList(), m.state.ignoredEvents)
    }

    @Test
    fun secondChoiceBecomesOpeningChoiceWhenAllRalliesAreUndone() {
        val m = MatchDriver(Presets.PADEL_GOLDEN_BO3.format())
        m.send(ServerChosen(0, Side.A, p("A2")))
        m.points("AAAA")
        val points = m.events.filter { it.payload is PointWon }.map { it.id }
        m.send(ServerChosen(0, Side.B, p("B2")))
        points.reversed().forEach { m.send(Undo(it)) }
        assertEquals(0, m.engineState.pointsPlayed)
        assertEquals(SidePair(0, 0), m.cur.games)
        assertEquals(SidePair(0, 0), m.cur.points)
        assertEquals(p("B2"), m.srv.player)
        assertEquals(ServerChoice(1, Side.B, listOf(p("B2"), p("B1"), p("A2"), p("A1")), p("B2")), m.state.serverChoice)
        assertEquals(emptyList(), m.state.ignoredEvents, "kein Phasenanker im historischen Event")
    }

    @Test
    fun tiebreakFirstPointUndoReinterpretsSecondChoiceAndRedoRestoresRotation() {
        for (preset in listOf(Presets.TENNIS_TIEBREAK_ONLY_7, Presets.PADEL_TIEBREAK_ONLY_10)) {
            val m = MatchDriver(preset.format(Discipline.DOUBLES))
            m.send(ServerChosen(0, Side.A, p("A2")))
            val point = m.point(Side.A)
            m.send(ServerChosen(0, Side.B, p("B2")))
            assertEquals(2, m.state.serverChoice?.game)
            val undo = m.undo()
            assertEquals(0, m.engineState.pointsPlayed)
            assertEquals(p("B2"), m.srv.player)
            assertEquals(1, m.state.serverChoice?.game)
            assertEquals(p("B2"), m.state.serverChoice?.chosen)
            m.send(Undo(undo))
            assertEquals(SidePair(1, 0), m.cur.points)
            assertEquals(2, m.state.serverChoice?.game)
            assertEquals(p("B2"), m.srv.player)
            assertEquals(p("A2"), m.engineState.point(point)?.server)
            m.point(Side.B)
            assertEquals(p("B2"), m.srv.player, "Zweierblock nach Punkt 1")
            m.point(Side.A)
            assertEquals(p("A1"), m.srv.player)
        }
    }

    @Test
    fun explicitChoiceOverridesInitialServerButForwardCorrectionPreservesHistory() {
        val m = MatchDriver(Presets.TENNIS_BO3_MTB10.format(Discipline.DOUBLES))
        m.send(ServerChosen(0, Side.A, p("A2")))
        val point = m.point(Side.A)
        val correction = m.send(Correction.initialServer(p("B2")))
        assertTrue(correction !in m.state.ignoredEvents, "akzeptiert bedeutet nicht rückwirkend wirksam")
        assertEquals(p("A2"), m.srv.player)
        assertEquals(p("A2"), m.engineState.point(point)?.server)
        m.send(Correction.server(p("B2")))
        assertEquals(p("B2"), m.srv.player)
        assertEquals(p("A2"), m.engineState.point(point)?.server, "ab jetzt, historische Zuordnung unverändert")
        val next = m.point(Side.B)
        assertEquals(p("B2"), m.engineState.point(next)?.server)
    }

    @Test
    fun serverChoiceOnlyTennisAndPadelDoubles() {
        for (format in listOf(
            Presets.TENNIS_SINGLES_BO3.format(),
            Presets.BADMINTON_DOUBLES_BO3_21.format(),
            Presets.TABLE_TENNIS_DOUBLES_BO3.format(),
            Presets.PICKLEBALL_DOUBLES_11_SIDEOUT.format(),
        )) {
            val m = MatchDriver(format)
            assertNull(m.state.serverChoice, format.presetId)
            val player = m.lineup.players(Side.A).last()
            m.send(ServerChosen(0, Side.A, player))
            assertEquals(listOf(Effect.IGNORED), m.state.effects, format.presetId)
        }
    }

    // --- 3. Aufschlag-Info je Punkt (§7.2) ---------------------------------------------------------------------------

    @Test
    fun serveInfoLastWinsAndDoubleFaultOnlyForReceiver() {
        val m = MatchDriver(Presets.TENNIS_SINGLES_BO3.format())
        val p1 = m.point(Side.A) // A schlägt auf und gewinnt
        m.send(PointDetailSet(p1, PointDetail(serve = ServeOutcome.FIRST_SERVE_IN)))
        assertEquals(emptyList(), m.state.effects)
        val df = m.send(PointDetailSet(p1, PointDetail(serve = ServeOutcome.DOUBLE_FAULT)))
        assertEquals(listOf(Effect.IGNORED), m.state.effects)
        assertTrue(df in m.state.ignoredEvents)
        assertEquals(
            ServeOutcome.FIRST_SERVE_IN,
            m.engineState
                .point(p1)
                ?.detail
                ?.serve,
        )

        val p2 = m.point(Side.B)
        m.send(PointDetailSet(p2, PointDetail(serve = ServeOutcome.DOUBLE_FAULT)))
        assertEquals(
            ServeOutcome.DOUBLE_FAULT,
            m.engineState
                .point(p2)
                ?.detail
                ?.serve,
        )
        m.send(PointDetailSet(p2, PointDetail(serve = ServeOutcome.SECOND_SERVE_IN)))
        assertEquals(
            ServeOutcome.SECOND_SERVE_IN,
            m.engineState
                .point(p2)
                ?.detail
                ?.serve,
            "letzte gilt",
        )
        val p3 = m.send(PointWon(Side.A, PointDetail(PointDetailKind.ACE, secondServe = true)))
        val p4 = m.point(Side.B)
        assertEquals(p4, m.engineState.lastPoint?.id)
        assertEquals(Side.A, m.engineState.lastPoint?.servingSide)

        val history = ScoringEngine.pointHistory(m.format, m.lineup, m.events, null)
        assertEquals(listOf(p1, p2, p3, p4), history.map { it.event.id })
        assertEquals(
            listOf(ServeOutcome.FIRST_SERVE_IN, ServeOutcome.SECOND_SERVE_IN, ServeOutcome.SECOND_SERVE_IN, null),
            history.map { it.serve },
        )
        assertEquals(listOf("A1", "A1", "A1", "A1"), history.map { it.server?.value })
    }

    @Test
    fun serveInfoOnlyTennisAndPadel() {
        val m = MatchDriver(Presets.BADMINTON_SINGLES_BO3_21.format())
        val point = m.point(Side.A)
        m.send(PointDetailSet(point, PointDetail(serve = ServeOutcome.FIRST_SERVE_IN)))
        assertEquals(listOf(Effect.IGNORED), m.state.effects)
        m.send(PointDetailSet(point, PointDetail(PointDetailKind.ACE)))
        assertEquals(emptyList(), m.state.effects, "Details ohne Aufschlag-Info bleiben erlaubt")
    }

    @Test
    fun serveOutcomeConsistencyTable() {
        data class Row(
            val detail: PointDetail?,
            val side: Side,
            val expected: ServeOutcome?,
        )
        val rows =
            listOf(
                Row(null, Side.A, null),
                Row(PointDetail(serve = ServeOutcome.FIRST_SERVE_IN), Side.A, ServeOutcome.FIRST_SERVE_IN),
                Row(PointDetail(serve = ServeOutcome.DOUBLE_FAULT), Side.A, null),
                Row(PointDetail(serve = ServeOutcome.DOUBLE_FAULT), Side.B, ServeOutcome.DOUBLE_FAULT),
                Row(PointDetail(PointDetailKind.DOUBLE_FAULT), Side.B, ServeOutcome.DOUBLE_FAULT),
                Row(PointDetail(PointDetailKind.WINNER, secondServe = false), Side.A, ServeOutcome.FIRST_SERVE_IN),
                // Vorrangregel: serve gilt vor widersprechenden älteren Angaben (wie im Fold)
                Row(PointDetail(PointDetailKind.DOUBLE_FAULT, serve = ServeOutcome.FIRST_SERVE_IN), Side.B, ServeOutcome.FIRST_SERVE_IN),
                Row(PointDetail(secondServe = true, serve = ServeOutcome.FIRST_SERVE_IN), Side.A, ServeOutcome.FIRST_SERVE_IN),
                Row(PointDetail(PointDetailKind.WINNER, p("B1"), serve = ServeOutcome.DOUBLE_FAULT), Side.B, ServeOutcome.DOUBLE_FAULT),
                Row(PointDetail(PointDetailKind.WINNER, serve = ServeOutcome.DOUBLE_FAULT), Side.A, null),
            )
        for (row in rows) {
            assertEquals(
                row.expected,
                PointDetailRules.serveOutcome(row.detail, row.side, Side.A, eu.advantly.core.model.Sport.TENNIS),
                row.toString(),
            )
        }
        assertNull(PointDetailRules.serveOutcome(rows[1].detail, Side.A, Side.A, eu.advantly.core.model.Sport.PICKLEBALL))
    }

    /** Review R1: Doppelfehler auf einen Punkt mit `kind = WINNER` bleibt in der Statistik (bereinigt + Vorrang). */
    @Test
    fun doubleFaultOverWinnerIsCleanedAndCounted() {
        val winner = PointDetail(PointDetailKind.WINNER, p("B1"))
        assertEquals(
            PointDetail(PointDetailKind.DOUBLE_FAULT, serve = ServeOutcome.DOUBLE_FAULT),
            PointDetailRules.withServe(winner, ServeOutcome.DOUBLE_FAULT),
        )
        assertEquals(
            PointDetail(serve = ServeOutcome.SECOND_SERVE_IN),
            PointDetailRules.withServe(
                PointDetail(PointDetailKind.DOUBLE_FAULT, serve = ServeOutcome.DOUBLE_FAULT),
                ServeOutcome.SECOND_SERVE_IN,
            ),
        )
        assertNull(PointDetailRules.withServe(PointDetail(serve = ServeOutcome.FIRST_SERVE_IN), null))

        val m = MatchDriver(Presets.TENNIS_SINGLES_BO3.format())
        val point = m.send(PointWon(Side.B, winner))
        m.send(PointDetailSet(point, PointDetail(PointDetailKind.WINNER, p("B1"), serve = ServeOutcome.DOUBLE_FAULT)))
        assertEquals(emptyList(), m.state.ignoredEvents, "Fold akzeptiert")
        val history = ScoringEngine.pointHistory(m.format, m.lineup, m.events, null)
        assertEquals(ServeOutcome.DOUBLE_FAULT, history.single().serve, "Statistik zählt den Doppelfehler (gleiche Vorrangregel)")
    }

    // --- 4. Games pro Satz 1–6 (§5.1) --------------------------------------------------------------------------------

    @Test
    fun gamesPerSetOneToSixAreValidWithTiebreakAtNn() {
        for (n in 1..6) {
            val format = Presets.withGamesPerSet(Presets.TENNIS_SINGLES_BO3.format(), n)
            assertEquals(RegularSet(n, n), format.set)
            assertTrue(MatchFormatValidator.validate(format).isValid, "n = $n")
        }
        val fs10 = Presets.withGamesPerSet(Presets.TENNIS_SINGLES_BO5_FS10.format(), 4)
        assertEquals(
            RegularSet(
                4,
                4,
                TiebreakFormat(
                    10,
                    2,
                    eu.advantly.core.model
                        .EndChange(6, 6),
                ),
            ),
            fs10.decidingSet,
        )
        assertEquals(RegularSet(3, null), Presets.withGamesPerSet(Presets.TENNIS_BO3_ADV_FINAL.format(), 3).decidingSet)
        assertEquals(MatchTiebreakFormat.MTB10, Presets.withGamesPerSet(Presets.PADEL_GOLDEN_STB.format(), 2).decidingSet)
    }

    /** Review: Preset-ID eindeutig, Aliase nur für bestehende IDs, withGamesPerSet ohne irreführenden Namen. */
    @Test
    fun presetRecognitionIsUniqueAndGamesPerSetRenames() {
        for (preset in Presets.all) {
            for (d in preset.disciplines) {
                val format = preset.format(d)
                assertEquals(preset.id, Presets.recognize(format), "eigene ID bleibt")
                val expected = Presets.aliases[preset.id] ?: preset.id
                assertEquals(expected, Presets.recognize(format.copy(presetId = null)), "ohne ID: bevorzugtes Preset")
                // keine weiteren strukturellen Duplikate außer den erklärten Aliasen
                val twins =
                    Presets.all.filter { other ->
                        other !== preset && other.sport == preset.sport && d in other.disciplines &&
                            other.format(d).copy(presetId = null) == format.copy(presetId = null)
                    }
                assertTrue(twins.all { Presets.aliases[it.id] == preset.id || Presets.aliases[preset.id] == it.id }, "${preset.id}: $twins")
            }
        }
        assertEquals(
            "TENNIS_TIEBREAK_ONLY_10",
            Presets.recognize(Presets.TENNIS_MATCH_TB_ONLY.format().copy(presetId = "TENNIS_SINGLES_BO3")),
        )

        val bo3 = Presets.TENNIS_SINGLES_BO3.format()
        assertEquals("TENNIS_SINGLES_BO3", Presets.withGamesPerSet(bo3, 6).presetId, "unverändertes Format")
        assertNull(Presets.withGamesPerSet(bo3, 4).presetId, "Best of 3 mit 4 Games: benutzerdefiniert")
        assertNull(Presets.withGamesPerSet(bo3, 1).presetId)
        assertEquals("TENNIS_SHORT_SETS", Presets.withGamesPerSet(Presets.TENNIS_BO3_MTB10.format(), 4).presetId)
        assertEquals("PADEL_MINI_SETS", Presets.withGamesPerSet(Presets.PADEL_GOLDEN_STB.format(), 4).presetId)
    }

    @Test
    fun oneGameSetNeedsTwoLeadOrTiebreakAtOneAll() {
        val format = Presets.withGamesPerSet(Presets.TENNIS_SINGLES_BO3.format(), 1)
        val m = MatchDriver(format).winGames(Side.A, 1)
        assertEquals(SidePair(1, 0), m.cur.games, "1:0 ist noch kein Satz")
        m.winGames(Side.A, 1)
        assertEquals(listOf(SetRecord(SetKind.REGULAR, SidePair(2, 0), null, null, Side.A)), m.state.sets)

        val t = MatchDriver(format).winGames(Side.A, 1).winGames(Side.B, 1)
        assertEquals(GameKind.TIEBREAK, t.cur.gameKind, "Tiebreak bei 1:1")
        assertTrue(Effect.TIEBREAK_STARTED in t.state.effects)
        t.points("AAAAAAA")
        assertEquals(listOf(SetRecord(SetKind.REGULAR, SidePair(2, 1), SidePair(7, 0), null, Side.A)), t.state.sets)
    }

    @Test
    fun gamesPerSetBoundaryTable() {
        data class Row(
            val n: Int,
            val games: String,
            val expectSet: SidePair<Int>?,
            val tiebreak: Boolean,
        )
        val rows =
            listOf(
                Row(2, "AA", SidePair(2, 0), false),
                Row(2, "ABA", null, false),
                Row(2, "ABAA", SidePair(3, 1), false),
                Row(2, "ABAB", null, true),
                Row(3, "AAA", SidePair(3, 0), false),
                Row(3, "AABBA", null, false),
                Row(3, "AABBAB", null, true),
                Row(6, "AAAAABBBBBB", null, false),
                Row(6, "AAAAABBBBBBA", null, true),
            )
        for (row in rows) {
            val m = MatchDriver(Presets.withGamesPerSet(Presets.TENNIS_SINGLES_BO3.format(), row.n))
            row.games.forEach { m.winGames(if (it == 'A') Side.A else Side.B, 1) }
            assertEquals(
                row.expectSet,
                m.state.sets
                    .firstOrNull()
                    ?.games,
                row.toString(),
            )
            assertEquals(row.tiebreak, m.cur.gameKind == GameKind.TIEBREAK, row.toString())
        }
    }

    // --- 5. Nur-Tiebreak-Formate (§4.1/§4.2) -------------------------------------------------------------------------

    @Test
    fun tiebreakOnly7() {
        val m = MatchDriver(Presets.TENNIS_TIEBREAK_ONLY_7.format())
        assertEquals(SetKind.MATCH_TIEBREAK, m.cur.setKind)
        assertEquals(GameKind.MATCH_TIEBREAK, m.cur.gameKind)
        assertEquals(ServeCourt.RIGHT, m.srv.court)
        m.points("ABABAB")
        assertTrue(Effect.CHANGE_ENDS in m.state.effects, "Seitenwechsel nach 6 Punkten")
        assertEquals(SidePair(End.END_2, End.END_1), m.state.ends)
        m.points("ABAB")
        assertEquals(emptyList(), m.state.statusFlags, "5:5 kein Matchball")
        m.points("A")
        assertEquals(listOf(StatusFlag(Side.A, FlagLevel.MATCH_POINT, 1, false)), m.state.statusFlags)
        m.points("A")
        assertEquals(MatchStatus.FINISHED, m.state.status)
        assertEquals(MatchResult(Side.A, ResultReason.COMPLETED), m.state.result)
        assertEquals(listOf(SetRecord(SetKind.MATCH_TIEBREAK, SidePair(1, 0), SidePair(7, 5), null, Side.A)), m.state.sets)
        assertEquals(SidePair(End.END_2, End.END_1), m.state.ends, "kein Wechsel nach Matchende")
    }

    @Test
    fun tiebreakOnly10NeedsTwoPointLead() {
        val m = MatchDriver(Presets.PADEL_TIEBREAK_ONLY_10.format()).points("AB".repeat(9))
        assertEquals(SidePair(9, 9), m.cur.points)
        m.points("AB")
        assertEquals(MatchStatus.IN_PROGRESS, m.state.status, "10:10 geht weiter")
        m.points("BB")
        assertEquals(listOf(SetRecord(SetKind.MATCH_TIEBREAK, SidePair(0, 1), SidePair(10, 12), null, Side.B)), m.state.sets)
        for (preset in listOf(Presets.TENNIS_TIEBREAK_ONLY_7, Presets.TENNIS_TIEBREAK_ONLY_10, Presets.PADEL_TIEBREAK_ONLY_7)) {
            for (d in preset.disciplines) assertTrue(MatchFormatValidator.validate(preset.format(d)).isValid, preset.id)
        }
    }

    // --- 6. Satzende-Dialog mit Tiebreak-Sätzen (§5.2) ---------------------------------------------------------------

    @Test
    fun openModeTiebreak7AndChampionsTiebreak10() {
        val m = MatchDriver(Presets.TENNIS_OPEN.format().copy(matchTiebreak = null)).winGames(Side.A, 6)
        assertEquals(
            listOf(NextSetKind.REGULAR, NextSetKind.TIEBREAK_7, NextSetKind.MATCH_TIEBREAK_10),
            ScoringEngine.nextSetOptions(m.format),
        )
        m.send(ContinueDecision(true, NextSetKind.TIEBREAK_7))
        assertEquals(SetKind.MATCH_TIEBREAK, m.cur.setKind)
        assertEquals(Side.A, m.srv.side, "B schlug das letzte Spiel auf")
        m.points("BBBBBBB")
        assertEquals(MatchStatus.AWAITING_DECISION, m.state.status)
        assertEquals(SetRecord(SetKind.MATCH_TIEBREAK, SidePair(0, 1), SidePair(0, 7), null, Side.B), m.state.sets[1])
        assertEquals(Continuation(Side.B, p("B1")), m.state.continuation, "other(tbFirst)")
        m.send(ContinueDecision(true, NextSetKind.MATCH_TIEBREAK_10))
        m.points("AAAAAAAAAA")
        assertEquals(SetRecord(SetKind.MATCH_TIEBREAK, SidePair(1, 0), SidePair(10, 0), null, Side.A), m.state.sets[2])
        m.send(ContinueDecision(false))
        assertEquals(MatchResult(Side.A, ResultReason.COMPLETED), m.state.result)
        assertEquals(SidePair(2, 1), m.state.setsWon)
    }

    @Test
    fun tiebreakSetKindsOnlyForTennisAndPadel() {
        val m = MatchDriver(Presets.BADMINTON_OPEN.format()).points("A".repeat(15))
        assertEquals(listOf(NextSetKind.REGULAR), ScoringEngine.nextSetOptions(m.format))
        for (kind in listOf(NextSetKind.TIEBREAK_7, NextSetKind.MATCH_TIEBREAK_10, NextSetKind.MATCH_TIEBREAK)) {
            m.send(ContinueDecision(true, kind))
            assertEquals(listOf(Effect.IGNORED), m.state.effects, kind.name)
        }
    }
}
