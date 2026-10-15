package eu.advantly.wear.match

import eu.advantly.core.model.Discipline
import eu.advantly.core.model.Environment
import eu.advantly.core.model.MatchMode
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.scoring.LineupValidator
import eu.advantly.core.scoring.MatchFormatValidator
import eu.advantly.core.scoring.ScoringEngine
import eu.advantly.presentation.match.LocalDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupCatalogTest {
    private val day = LocalDay(2026, 10, 15)
    private val players = LineupPlayers(PlayerId("me"), PlayerId("partner"), PlayerId("o1"), PlayerId("o2"))

    @Test
    fun everyChoiceBuildsAValidMatchInEveryAllowedDiscipline() {
        for (choice in FormatChoice.entries) {
            for (discipline in SetupCatalog.disciplines(choice.sport)) {
                val format = SetupCatalog.format(choice, discipline, day)
                assertTrue("$choice/$discipline", MatchFormatValidator.validate(format).isValid)
                for (server in Side.entries) {
                    val lineup = SetupCatalog.lineup(discipline, players, server)
                    assertEquals("$choice/$discipline", emptyList<String>(), LineupValidator.validate(format, lineup))
                    ScoringEngine.replay(format, lineup, emptyList()) // wirft bei ungültiger Kombination
                }
            }
        }
    }

    @Test
    fun everySportOffersThreeToFiveFormats() {
        for (sport in SetupCatalog.sports) {
            val formats = SetupCatalog.formats(sport, day)
            assertTrue("$sport", formats.size in 3..5)
            assertTrue("$sport", formats.all { it.sport == sport })
        }
        assertEquals(Sport.entries.toSet(), SetupCatalog.sports.toSet())
    }

    @Test
    fun tableTennisHasNoDefaultSetCount() {
        assertNull(SetupCatalog.defaultFormat(Sport.TABLE_TENNIS, day))
        assertEquals(FormatChoice.TENNIS_BO3, SetupCatalog.defaultFormat(Sport.TENNIS, day))
        assertEquals(FormatChoice.PADEL_GOLDEN, SetupCatalog.defaultFormat(Sport.PADEL, day))
        assertEquals(FormatChoice.PICKLEBALL_11, SetupCatalog.defaultFormat(Sport.PICKLEBALL, day))
    }

    @Test
    fun badmintonDefaultFollowsTheLocalDate() {
        assertEquals(FormatChoice.BADMINTON_BO3_21, SetupCatalog.defaultFormat(Sport.BADMINTON, LocalDay(2027, 1, 3)))
        assertEquals(FormatChoice.BADMINTON_BO3_15, SetupCatalog.defaultFormat(Sport.BADMINTON, LocalDay(2027, 1, 4)))
        // beide Systeme bleiben wählbar
        assertTrue(FormatChoice.BADMINTON_BO3_15 in SetupCatalog.formats(Sport.BADMINTON, LocalDay(2027, 1, 3)))
        assertTrue(FormatChoice.BADMINTON_BO3_21 in SetupCatalog.formats(Sport.BADMINTON, LocalDay(2027, 1, 4)))
        val open = SetupCatalog.format(FormatChoice.BADMINTON_OPEN, Discipline.SINGLES, LocalDay(2027, 2, 1))
        assertEquals(15, open.rally?.pointsToWin)
        assertEquals(MatchMode.OPEN, open.match.mode)
    }

    @Test
    fun formatsMatchTheSpecPresets() {
        fun preset(
            choice: FormatChoice,
            discipline: Discipline,
        ) = SetupCatalog.format(choice, discipline, day).presetId
        assertEquals("TENNIS_SINGLES_BO3", preset(FormatChoice.TENNIS_BO3, Discipline.SINGLES))
        assertEquals("TENNIS_DOUBLES_MTB10", preset(FormatChoice.TENNIS_BO3_MTB, Discipline.DOUBLES))
        assertEquals("PADEL_PREMIER_2026", preset(FormatChoice.PADEL_STAR, Discipline.DOUBLES))
        assertEquals("PADEL_GOLDEN_BO3", preset(FormatChoice.PADEL_GOLDEN, Discipline.DOUBLES))
        assertEquals("PICKLEBALL_DOUBLES_11_SIDEOUT", preset(FormatChoice.PICKLEBALL_11, Discipline.DOUBLES))
        assertEquals("BADMINTON_SINGLES_BO3_21", preset(FormatChoice.BADMINTON_BO3_21, Discipline.SINGLES))
        assertEquals("TABLE_TENNIS_SINGLES_BO5", preset(FormatChoice.TABLE_TENNIS_BO5, Discipline.SINGLES))
        assertEquals("TABLE_TENNIS_DOUBLES_BO3", preset(FormatChoice.TABLE_TENNIS_BO3, Discipline.DOUBLES))
    }

    @Test
    fun padelIsDoublesOnlyAndPickleballDefaultsToDoubles() {
        assertEquals(listOf(Discipline.DOUBLES), SetupCatalog.disciplines(Sport.PADEL))
        assertEquals(Discipline.DOUBLES, SetupCatalog.defaultDiscipline(Sport.PICKLEBALL))
        assertEquals(Discipline.SINGLES, SetupCatalog.defaultDiscipline(Sport.TENNIS))
    }

    @Test
    fun lineupPutsUsOnSideAAndTheChosenServerFirst() {
        val singles = SetupCatalog.lineup(Discipline.SINGLES, players, Side.B)
        assertEquals(listOf(PlayerId("me")), singles.a)
        assertEquals(listOf(PlayerId("o1")), singles.b)
        assertEquals(PlayerId("o1"), singles.firstServer)
        val doubles = SetupCatalog.lineup(Discipline.DOUBLES, players, Side.A)
        assertEquals(listOf(PlayerId("me"), PlayerId("partner")), doubles.a)
        assertEquals(listOf(PlayerId("o1"), PlayerId("o2")), doubles.b)
        assertEquals(PlayerId("me"), doubles.firstServer)
    }

    @Test
    fun doublesFirstServerIsTheChosenPlayer() {
        assertEquals(PlayerId("partner"), SetupCatalog.lineup(Discipline.DOUBLES, players, Side.A, serverIndex = 1).firstServer)
        assertEquals(PlayerId("o2"), SetupCatalog.lineup(Discipline.DOUBLES, players, Side.B, serverIndex = 1).firstServer)
        val failed = runCatching { SetupCatalog.lineup(Discipline.SINGLES, players, Side.A, serverIndex = 1) }
        assertTrue("Einzel hat keinen zweiten Spieler", failed.isFailure)
    }

    @Test
    fun choiceForFindsAChoiceProducingTheSameFormat() {
        for (choice in FormatChoice.entries) {
            val discipline = SetupCatalog.disciplines(choice.sport).last()
            val format = SetupCatalog.format(choice, discipline, day)
            val found = SetupCatalog.choiceFor(format, day)
            val rebuilt = found?.let { SetupCatalog.format(it, discipline, day).copy(presetId = null) }
            assertEquals("$choice", format.copy(presetId = null), rebuilt)
        }
    }

    @Test
    fun badmintonIsPlayedIndoorsByDefault() {
        assertEquals(Environment.INDOOR, SetupCatalog.meta(Sport.BADMINTON).environment)
        assertNull(SetupCatalog.meta(Sport.TENNIS).environment)
    }
}
