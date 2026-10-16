package eu.advantly.presentation.wizard

import eu.advantly.core.model.DeuceMode
import eu.advantly.core.model.Discipline
import eu.advantly.core.model.Environment
import eu.advantly.core.model.MatchMode
import eu.advantly.core.model.MatchTiebreakFormat
import eu.advantly.core.model.MidGameEndChange
import eu.advantly.core.model.PickleballScoring
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.model.Surface
import eu.advantly.core.scoring.MatchFormatValidator
import eu.advantly.core.scoring.Presets
import eu.advantly.presentation.match.LocalDay
import eu.advantly.presentation.match.testStore
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MatchWizardTest {
    private val store = testStore()

    private fun wizard(day: LocalDay = LocalDay(2026, 10, 15)) = MatchWizard(store, today = { day }, random = Random(7))

    private fun MatchWizard.fillOpponents() {
        val needed = state.value.input.teamA.size + state.value.input.teamB.size - 1
        repeat(needed) { addGuest("Gast $it") }
    }

    @Test
    fun tennisDefaultsMatchThePresetAndSurfaceGoesToMeta() {
        val w = wizard()
        w.selectSport(Sport.TENNIS)
        assertEquals(WizardStep.SURFACE, w.state.value.step)
        w.setEnvironment(Environment.INDOOR)
        w.selectSurface(Surface.CLAY)
        assertEquals(WizardStep.PLAYERS, w.state.value.step)
        assertFalse(w.state.value.canContinue, "Gegner fehlt")
        w.fillOpponents()
        assertTrue(w.next())
        assertEquals(WizardStep.FORMAT, w.state.value.step)
        val setup = assertNotNull(w.finish())
        assertEquals("TENNIS_SINGLES_BO3", setup.format.presetId)
        assertEquals(Presets.TENNIS_SINGLES_BO3.format(), setup.format)
        assertEquals(Surface.CLAY, setup.meta.surface)
        assertEquals(Environment.INDOOR, setup.meta.environment)
        assertEquals(store.self.value.id, setup.lineup.firstServer)
    }

    @Test
    fun padelIsAlwaysDoublesWithGoldenPointDefault() {
        val w = wizard()
        w.selectSport(Sport.PADEL)
        assertEquals(WizardStep.PLAYERS, w.state.value.step, "kein Belag-Schritt")
        w.setDiscipline(Discipline.SINGLES)
        assertEquals(Discipline.DOUBLES, w.state.value.input.discipline)
        w.fillOpponents()
        w.next()
        val setup = assertNotNull(w.finish())
        assertEquals("PADEL_GOLDEN_BO3", setup.format.presetId)
        assertEquals(DeuceMode.DECIDING_POINT, setup.format.game!!.deuceMode)
        assertEquals(2, setup.lineup.a.size)
    }

    @Test
    fun pickleballOpenUsesEveryGameEndChangeUnlessTouched() {
        val w = wizard()
        w.selectSport(Sport.PICKLEBALL)
        w.fillOpponents()
        w.next()
        assertEquals("PICKLEBALL_DOUBLES_11_SIDEOUT", w.state.value.presetId)
        w.setSets(SetsChoice.Open)
        assertEquals(
            MidGameEndChange.EVERY_GAME,
            w.state.value.format!!
                .pickleball!!
                .midGameEndChange,
        )
        assertEquals("PICKLEBALL_OPEN", w.state.value.presetId)
        w.setPickleballMidGame(MidGameEndChange.NEVER)
        w.setSets(SetsChoice.BestOf(3))
        assertEquals(
            MidGameEndChange.NEVER,
            w.state.value.format!!
                .pickleball!!
                .midGameEndChange,
        )
        w.setPickleballScoring(PickleballScoring.RALLY)
        w.setPoints(15)
        assertTrue(w.state.value.canStart)
    }

    @Test
    fun badmintonDefaultDependsOnTheDate() {
        val before = wizard(LocalDay(2027, 1, 3))
        before.selectSport(Sport.BADMINTON)
        assertEquals(WizardStep.SURFACE, before.state.value.step, "Badminton: Umgebung statt Belag")
        assertEquals(Environment.INDOOR, before.state.value.input.environment)
        assertEquals(21, before.state.value.input.pointsToWin)

        val after = wizard(LocalDay(2027, 1, 4))
        after.selectSport(Sport.BADMINTON)
        after.next()
        after.fillOpponents()
        after.next()
        assertEquals("BADMINTON_SINGLES_BO3_15", after.state.value.presetId)
        assertEquals(Presets.BADMINTON_SINGLES_BO3_15.format(), after.finish()!!.format)
    }

    @Test
    fun badmintonElevenPointsForcesBestOfFive() {
        val w = wizard()
        w.selectSport(Sport.BADMINTON)
        w.setPoints(11)
        assertEquals(SetsChoice.BestOf(5), w.state.value.input.sets)
        assertEquals(
            1,
            w.state.value.format!!
                .rally!!
                .winBy,
        )
        w.setPoints(21)
        assertEquals(SetsChoice.BestOf(3), w.state.value.input.sets)
    }

    @Test
    fun tableTennisRequiresAnExplicitSetChoice() {
        val w = wizard()
        w.selectSport(Sport.TABLE_TENNIS)
        w.setDiscipline(Discipline.DOUBLES)
        w.fillOpponents()
        w.next()
        assertNull(w.state.value.format)
        assertFalse(w.state.value.canStart)
        assertNull(w.finish())
        w.setSets(SetsChoice.BestOf(7))
        // Best of 7 gibt es nur im Einzel als Preset; im Doppel bleibt es gültig, aber ohne Preset-Namen
        assertTrue(w.state.value.canStart)
        w.setSets(SetsChoice.BestOf(5))
        assertEquals("TABLE_TENNIS_DOUBLES_BO5", w.state.value.presetId)
        assertTrue(
            w.state.value.format!!
                .rally!!
                .receiverSwapAtMidGameEndChange,
        )
    }

    @Test
    fun tennisDoublesMatchTiebreakSuggestsNoAd() {
        val w = wizard()
        w.selectSport(Sport.TENNIS)
        w.selectSurface(null)
        w.setDiscipline(Discipline.DOUBLES)
        w.fillOpponents()
        w.next()
        w.setDecidingSet(DecidingSetChoice.MATCH_TIEBREAK)
        assertEquals("TENNIS_DOUBLES_MTB10", w.state.value.presetId)
        // Vom Nutzer gewählter Einstand bleibt
        w.setDeuceMode(DeuceMode.ADVANTAGE)
        w.setDecidingSet(DecidingSetChoice.NORMAL)
        w.setDecidingSet(DecidingSetChoice.MATCH_TIEBREAK)
        assertEquals(
            DeuceMode.ADVANTAGE,
            w.state.value.format!!
                .game!!
                .deuceMode,
        )
        assertEquals("TENNIS_BO3_MTB10", w.state.value.presetId)
    }

    @Test
    fun tennisOpenOffersMatchTiebreakAndShortSetsMoveTheTiebreak() {
        val w = wizard()
        w.selectSport(Sport.TENNIS)
        w.selectSurface(Surface.HARD)
        w.fillOpponents()
        w.next()
        w.setSets(SetsChoice.Open)
        assertEquals(
            MatchMode.OPEN,
            w.state.value.format!!
                .match.mode,
        )
        assertEquals(
            MatchTiebreakFormat.MTB10,
            w.state.value.format!!
                .matchTiebreak,
        )
        assertEquals("TENNIS_OPEN", w.state.value.presetId)
        w.setSets(SetsChoice.BestOf(3))
        w.setGamesPerSet(4)
        assertEquals(
            4,
            w.state.value.format!!
                .set!!
                .tiebreakAt,
        )
        w.setTiebreakAt(TiebreakAtChoice.AT_GAMES_MINUS_ONE)
        assertEquals(
            3,
            w.state.value.format!!
                .set!!
                .tiebreakAt,
        )
        w.setTiebreakAt(TiebreakAtChoice.NONE)
        assertNull(
            w.state.value.format!!
                .set!!
                .tiebreakAt,
        )
        assertTrue(w.state.value.canStart)
    }

    /** Review: strukturell gleiche Presets werden eindeutig erkannt (vorhandene ID, sonst bevorzugte statt Alias). */
    @Test
    fun recognizePresetIsUniqueForTiebreakOnly() {
        val champions =
            eu.advantly.core.scoring.Presets.TENNIS_TIEBREAK_ONLY_10
                .format()
        assertEquals("TENNIS_TIEBREAK_ONLY_10", MatchWizard.recognizePreset(champions))
        assertEquals("TENNIS_TIEBREAK_ONLY_10", MatchWizard.recognizePreset(champions.copy(presetId = null)))
        val legacy =
            eu.advantly.core.scoring.Presets.TENNIS_MATCH_TB_ONLY
                .format()
        assertEquals("TENNIS_MATCH_TB_ONLY", MatchWizard.recognizePreset(legacy), "bestehende ID bleibt")
    }

    @Test
    fun everyOfferedOptionCombinationIsValid() {
        for (sport in Sport.entries) {
            val options = MatchWizard.optionsFor(sport)
            val w = wizard()
            w.selectSport(sport)
            for (sets in options.sets) {
                w.setSets(sets)
                for (points in (options.points + options.advancedPoints).ifEmpty { listOf(11) }) {
                    w.setPoints(points)
                    for (decider in options.decidingSets.ifEmpty { listOf(DecidingSetChoice.NORMAL) }) {
                        w.setDecidingSet(decider)
                        val format = assertNotNull(w.state.value.format, "$sport $sets $points")
                        assertTrue(MatchFormatValidator.validate(format).isValid, "$sport $sets $points $decider")
                    }
                }
            }
        }
    }

    @Test
    fun playerSlotsFillOpponentsFirstAndCanBeSwappedAndCleared() {
        val w = wizard()
        w.selectSport(Sport.PICKLEBALL)
        val jonas = w.addGuest("Jonas")!!
        val mia = w.addGuest("Mia")!!
        val tom = w.addGuest("Tom")!!
        val input = w.state.value.input
        assertEquals(listOf(jonas, tom), input.teamB)
        assertEquals(listOf(store.self.value, mia), input.teamA)
        w.swapOrder(Side.B)
        assertEquals(listOf(tom, jonas), w.state.value.input.teamB)
        w.clearSlot(Side.A, 0)
        assertEquals(store.self.value, w.state.value.input.teamA[0], "eigener Slot bleibt")
        w.clearSlot(Side.B, 0)
        assertFalse(w.state.value.playersComplete)
        w.pickPlayer(jonas)
        assertFalse(w.state.value.playersComplete, "schon gesetzter Spieler wird nicht doppelt belegt")
        assertNull(w.addGuest("   "))
        w.pickPlayer(tom)
        assertTrue(w.state.value.playersComplete)
        w.setFirstServer(jonas.id)
        assertEquals(
            jonas.id,
            w.state.value.setup
                ?.lineup
                ?.firstServer,
        )
        w.setDiscipline(Discipline.SINGLES)
        assertNull(w.state.value.input.firstServer, "Erstaufschläger nicht mehr im Lineup")
    }

    @Test
    fun switchingFromBadmintonDoublesToPadelIgnoresTheHiddenServerSelection() {
        val w = wizard()
        w.selectSport(Sport.BADMINTON)
        w.setDiscipline(Discipline.DOUBLES)
        w.fillOpponents()
        val opponent =
            w.state.value.input.teamB
                .first()!!
                .id
        w.setFirstServer(opponent)
        assertEquals(
            opponent,
            w.state.value.setup!!
                .lineup.firstServer,
        )
        w.selectSport(Sport.PADEL)
        assertNull(w.state.value.input.firstServer)
        w.fillOpponents()
        assertTrue(w.next())
        assertEquals(store.self.value.id, assertNotNull(w.finish()).lineup.firstServer)
    }

    @Test
    fun switchingTennisDisciplineUsesTheLiveChoiceDefaultOnlyForDoubles() {
        val w = wizard()
        w.selectSport(Sport.TENNIS)
        w.fillOpponents()
        val opponent =
            w.state.value.input.teamB
                .first()!!
                .id
        w.setFirstServer(opponent)
        assertEquals(
            opponent,
            w.state.value.setup!!
                .lineup.firstServer,
        )
        w.setDiscipline(Discipline.DOUBLES)
        w.fillOpponents()
        assertEquals(
            store.self.value.id,
            w.state.value.setup!!
                .lineup.firstServer,
        )
        w.setDiscipline(Discipline.SINGLES)
        assertEquals(
            opponent,
            w.state.value.setup!!
                .lineup.firstServer,
        )
    }

    @Test
    fun coinTossPicksAServerFromTheLineup() {
        val w = wizard()
        w.selectSport(Sport.TENNIS)
        w.fillOpponents()
        val server = assertNotNull(w.coinToss())
        assertTrue(
            server in
                w.state.value.input.players
                    .map { it.id },
        )
    }

    @Test
    fun backNavigatesStepsAndFavoritesAreSaved() {
        val w = wizard()
        assertFalse(w.back())
        w.selectSport(Sport.TENNIS)
        assertTrue(w.back())
        assertEquals(WizardStep.SPORT, w.state.value.step)
        w.selectSport(Sport.PADEL)
        w.fillOpponents()
        w.next()
        w.setSaveAsFavorite(true)
        val setup = assertNotNull(w.finish())
        assertEquals(
            setup,
            store.favorites.value
                .single()
                .setup,
        )
    }
}
