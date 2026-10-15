package eu.advantly.presentation.wizard

import eu.advantly.core.model.Discipline
import eu.advantly.core.model.MatchMode
import eu.advantly.core.model.MatchTiebreakFormat
import eu.advantly.core.model.Sport
import eu.advantly.core.scoring.MatchFormatValidator
import eu.advantly.core.scoring.Presets
import eu.advantly.presentation.match.testStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PhoneFormatTest {
    @Test
    fun allOfferedGameCountsUseTheEngineHelper() {
        for (sport in listOf(Sport.TENNIS, Sport.PADEL)) {
            val wizard = MatchWizard(testStore())
            wizard.selectSport(sport)
            val expected = (1..6).toList() + if (sport == Sport.TENNIS) listOf(8, 10) else emptyList()
            assertEquals(expected, wizard.state.value.options.gamesPerSet)
            for (games in expected) {
                wizard.setGamesPerSet(games)
                wizard.setDecidingSet(DecidingSetChoice.TIEBREAK_TO_10)
                val format = assertNotNull(wizard.state.value.format)
                assertEquals(games, format.set?.gamesToWin)
                assertEquals(games, format.set?.tiebreakAt)
                assertTrue(MatchFormatValidator.validate(format).isValid)
                assertEquals(format, Presets.withGamesPerSet(format, games))
            }
            wizard.setGamesPerSet(0)
            assertEquals(expected.last(), wizard.state.value.input.gamesPerSet)
        }
    }

    @Test
    fun tiebreakOnlyUsesEachExactPresetAndRestoresRegularInputs() {
        for (sport in listOf(Sport.TENNIS, Sport.PADEL)) {
            val wizard = MatchWizard(testStore())
            wizard.selectSport(sport)
            wizard.setDiscipline(Discipline.DOUBLES)
            repeat(3) { wizard.addGuest("Player $it") }
            wizard.setSets(SetsChoice.Open)
            wizard.setGamesPerSet(1)
            assertTrue(wizard.finish()!!.meta.serveStats)
            wizard.setServeStats(false)
            for (choice in listOf(TennisMatchChoice.TIEBREAK_7, TennisMatchChoice.TIEBREAK_10)) {
                wizard.setTennisMatch(choice)
                val setup = assertNotNull(wizard.finish())
                val suffix = if (choice == TennisMatchChoice.TIEBREAK_7) 7 else 10
                val expected = Presets.all.single { it.id == "${sport.name}_TIEBREAK_ONLY_$suffix" }.format(Discipline.DOUBLES)
                assertEquals(expected, setup.format)
                assertEquals(1, setup.format.match.bestOf)
                assertEquals(suffix, (setup.format.decidingSet as MatchTiebreakFormat).pointsToWin)
                assertFalse(setup.meta.serveStats)
            }
            wizard.setTennisMatch(TennisMatchChoice.REGULAR)
            assertEquals(
                MatchMode.OPEN,
                wizard
                    .finish()!!
                    .format.match.mode,
            )
            assertEquals(
                1,
                wizard
                    .finish()!!
                    .format.set
                    ?.gamesToWin,
            )
            wizard.selectSport(Sport.BADMINTON)
            wizard.setTennisMatch(TennisMatchChoice.TIEBREAK_7)
            assertEquals(TennisMatchChoice.REGULAR, wizard.state.value.input.tennisMatch)
            assertTrue(wizard.state.value.input.serveStats)
        }
    }
}
