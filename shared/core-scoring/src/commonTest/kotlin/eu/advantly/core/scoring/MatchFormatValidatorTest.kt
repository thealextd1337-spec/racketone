package eu.advantly.core.scoring

import eu.advantly.core.model.Discipline
import eu.advantly.core.model.GameFormat
import eu.advantly.core.model.MatchSettings
import eu.advantly.core.model.MatchTiebreakFormat
import eu.advantly.core.model.MidGameEndChange
import eu.advantly.core.model.NextGameFirstServer
import eu.advantly.core.model.RegularSet
import eu.advantly.core.model.ServeRotation
import eu.advantly.core.model.TiebreakFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Semantische Validierung 1–9 (02 §3.1) und Presets (02 §4). */
class MatchFormatValidatorTest {
    private fun rules(format: eu.advantly.core.model.MatchFormat) =
        MatchFormatValidator
            .validate(format)
            .errors
            .map { it.rule }
            .toSet()

    @Test
    fun allPresetsAreValidForAllDisciplines() {
        assertEquals(49, Presets.all.size)
        assertEquals(
            Presets.all.size,
            Presets.all
                .map { it.id }
                .toSet()
                .size,
        )
        for (preset in Presets.all) {
            for (d in preset.disciplines) {
                val format = preset.format(d)
                val result = MatchFormatValidator.validate(format)
                assertTrue(result.isValid, "${preset.id}/$d: ${result.errors}")
                assertEquals(emptyList(), result.warnings, "${preset.id}/$d")
                assertEquals(preset.id, format.presetId)
                assertEquals(preset, Presets.byId(preset.id))
            }
        }
    }

    @Test
    fun presetDetailsMatchSpec() {
        val tt = Presets.TABLE_TENNIS_LEISURE_21
        assertEquals(false, tt.format(Discipline.SINGLES).rally?.receiverSwapAtMidGameEndChange)
        assertEquals(true, tt.format(Discipline.DOUBLES).rally?.receiverSwapAtMidGameEndChange)
        assertEquals(5, tt.format().rally?.serveEvery)
        assertEquals(
            2,
            Presets.PADEL_PREMIER_2026
                .format()
                .game
                ?.maxAdvantages,
        )
        assertEquals(21, Presets.badmintonOpen(21).rally?.pointsToWin)
        assertEquals(30, Presets.badmintonOpen(21).rally?.cap)
        assertEquals(21, Presets.badmintonDefaultPointsToWin(2027, 1, 3))
        assertEquals(15, Presets.badmintonDefaultPointsToWin(2027, 1, 4))
        assertEquals(null, Presets.wizardDefault(eu.advantly.core.model.Sport.TABLE_TENNIS))
        assertFailsWith<IllegalArgumentException> { Presets.PADEL_GOLDEN_BO3.format(Discipline.SINGLES) }
    }

    @Test
    fun rule1SportBoundFields() {
        val tennis = Presets.TENNIS_SINGLES_BO3.format()
        assertEquals(setOf(1), rules(tennis.copy(rally = Presets.BADMINTON_1GAME_15.format().rally)))
        assertEquals(setOf(1), rules(tennis.copy(game = null)))
        val pb = Presets.PICKLEBALL_DOUBLES_15.format()
        assertEquals(setOf(1), rules(pb.copy(decidingSet = MatchTiebreakFormat.MTB10)))
        val bad = Presets.BADMINTON_1GAME_21.format()
        assertEquals(setOf(1), rules(bad.copy(set = RegularSet(6, 6))))
        assertEquals(setOf(1), rules(bad.copy(rally = null)))
    }

    @Test
    fun rule2TiebreakAt() {
        val f = Presets.TENNIS_SINGLES_BO3.format()
        assertEquals(setOf(2), rules(f.copy(set = RegularSet(6, 4))))
        assertTrue(rules(f.copy(set = RegularSet(6, 5))).isEmpty())
        assertTrue(rules(f.copy(set = RegularSet(6, 12))).isEmpty())
        assertTrue(rules(f.copy(set = RegularSet(6, null))).isEmpty())
    }

    @Test
    fun rule3And4AreWarningsOnly() {
        val open = Presets.TENNIS_OPEN.format().copy(decidingSet = MatchTiebreakFormat.MTB10)
        val r = MatchFormatValidator.validate(open)
        assertTrue(r.isValid)
        assertEquals(listOf(3), r.warnings.map { it.rule })
        val winBy1 = Presets.TENNIS_SINGLES_BO3.format().copy(set = RegularSet(6, 6, TiebreakFormat(10, 1, TiebreakFormat.TB7.endChange)))
        assertEquals(listOf(4), MatchFormatValidator.validate(winBy1).warnings.map { it.rule })
    }

    @Test
    fun rules5To8Rally() {
        val bad = Presets.BADMINTON_1GAME_21.format()
        val r = bad.rally!!
        assertEquals(setOf(5), rules(bad.copy(rally = r.copy(cap = 21))))
        assertEquals(setOf(6, 7), rules(bad.copy(rally = r.copy(serveRotation = ServeRotation.EVERY_N_POINTS))))
        assertEquals(setOf(6), rules(bad.copy(rally = r.copy(serveEvery = 2))))
        assertEquals(setOf(7), rules(bad.copy(rally = r.copy(nextGameFirstServer = NextGameFirstServer.PREVIOUS_FIRST_RECEIVER))))
        assertEquals(setOf(7), rules(bad.copy(rally = r.copy(midGameEndChange = MidGameEndChange.EVERY_GAME))))
        assertEquals(setOf(8), rules(bad.copy(rally = r.copy(receiverSwapAtMidGameEndChange = true))))

        val tt = Presets.TABLE_TENNIS_SINGLES_BO5.format()
        val t = tt.rally!!
        assertEquals(setOf(6), rules(tt.copy(rally = t.copy(serveEvery = 3))), "2·10 nicht durch 3 teilbar")
        assertEquals(setOf(6), rules(tt.copy(rally = t.copy(winBy = 1))))
        assertEquals(setOf(6), rules(tt.copy(rally = t.copy(serveEveryAtDeuce = null))))
        assertEquals(setOf(7), rules(tt.copy(rally = t.copy(midGameEndChange = MidGameEndChange.NEVER))))
    }

    @Test
    fun rule9BestOfSevenOnlyTableTennis() {
        assertEquals(setOf(9), rules(Presets.TENNIS_SINGLES_BO3.format().copy(match = MatchSettings.bestOf(7))))
        assertTrue(rules(Presets.TABLE_TENNIS_SINGLES_BO7.format()).isEmpty())
        assertEquals(setOf(0), rules(Presets.TENNIS_SINGLES_BO3.format().copy(match = MatchSettings.bestOf(2))))
    }

    @Test
    fun schemaRules() {
        val f = Presets.PADEL_PREMIER_2026.format()
        assertEquals(setOf(0), rules(f.copy(game = GameFormat(eu.advantly.core.model.DeuceMode.STAR_POINT, 0))))
        assertEquals(setOf(0), rules(f.copy(set = RegularSet(13, 13))))
        assertEquals(setOf(0), rules(f.copy(formatVersion = 2)))
    }

    @Test
    fun engineRejectsInvalidFormatAndLineup() {
        val f = Presets.TENNIS_SINGLES_BO3.format()
        assertFailsWith<IllegalArgumentException> { ScoringEngine.initial(f.copy(set = RegularSet(6, 1)), lineupFor(Discipline.SINGLES)) }
        assertFailsWith<IllegalArgumentException> { ScoringEngine.initial(f, lineupFor(Discipline.DOUBLES)) }
        assertFailsWith<IllegalArgumentException> { ScoringEngine.initial(f, lineupFor(Discipline.SINGLES).copy(firstServer = p("X"))) }
    }
}
