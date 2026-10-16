package eu.advantly.match.session

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.Discipline
import eu.advantly.core.model.EventId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.EventPayload
import eu.advantly.core.scoring.PointWon
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.ScoreEvent
import eu.advantly.core.scoring.ScoringEngine
import eu.advantly.core.scoring.ServerChosen
import eu.advantly.core.scoring.Undo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerChoiceContextTest {
    private val doubles =
        Lineup(listOf(PlayerId("me"), PlayerId("max")), listOf(PlayerId("bea"), PlayerId("cem")), PlayerId("me"))
    private val choice = ServerChosen(0, Side.A, PlayerId("max"))

    @Test
    fun singlesAndOtherSportsSkipLogAnalysisEvenWithInvalidChoice() {
        for (preset in listOf(
            Presets.TENNIS_SINGLES_BO3,
            Presets.PICKLEBALL_DOUBLES_11_SIDEOUT,
            Presets.BADMINTON_DOUBLES_BO3_21,
            Presets.TABLE_TENNIS_DOUBLES_BO3,
        )) {
            val format = preset.format()
            val engine =
                ScoringEngine.replay(format, if (format.discipline == Discipline.DOUBLES) doubles else SINGLES, listOf(event(1, choice)))
            assertTrue(canCorrectServerRetroactively(engine) { error("Keine Log-Analyse für Einzel/andere Sportarten") }, preset.id)
            assertTrue(canCorrectServerRetroactively(engine), preset.id)
        }
    }

    @Test
    fun doublesWithoutOpeningChoiceSkipLogAnalysis() {
        val point = event(1, PointWon(Side.A))
        for (preset in listOf(Presets.TENNIS_DOUBLES_MTB10, Presets.PADEL_GOLDEN_BO3)) {
            for (events in listOf(
                emptyList(),
                listOf(event(1, choice.copy(set = 1))),
                listOf(point, event(2, choice.copy(side = Side.B, player = PlayerId("cem")))),
            )) {
                val engine = ScoringEngine.replay(preset.format(), doubles, events)
                assertTrue(canCorrectServerRetroactively(engine) { error("Keine eröffnende Wahl vor dem ersten angewendeten Punkt") })
                assertTrue(canCorrectServerRetroactively(engine))
            }
        }
    }

    @Test
    fun openingChoiceAfterUndonePointStillChecksExclusionsOnce() {
        val point = event(1, PointWon(Side.A))
        val opening = event(3, choice)
        val engine =
            ScoringEngine.replay(
                Presets.PADEL_GOLDEN_BO3.format(),
                doubles,
                listOf(point, event(2, Undo(point.id)), opening, event(4, choice)),
            )
        assertEquals(0, engine.pointsPlayed)
        var analyses = 0
        assertFalse(
            canCorrectServerRetroactively(engine) {
                analyses++
                setOf(point.id, opening.id)
            },
        )
        assertEquals(1, analyses)
        assertFalse(canCorrectServerRetroactively(engine))
    }

    @Test
    fun undoneAndIgnoredOpeningChoicesDoNotBlockRetroactiveCorrection() {
        val opening = event(1, choice)
        for (events in listOf(
            listOf(opening, event(2, Undo(opening.id))),
            listOf(event(1, choice.copy(side = Side.B))),
        )) {
            val engine = ScoringEngine.replay(Presets.PADEL_GOLDEN_BO3.format(), doubles, events)
            assertTrue(canCorrectServerRetroactively(engine))
        }
    }

    private fun event(
        index: Int,
        payload: EventPayload,
    ): ScoreEvent =
        ScoreEvent(
            id = EventId("e$index"),
            matchId = MatchId("m"),
            deviceId = DeviceId("d"),
            deviceSeq = index.toLong(),
            lamport = index.toLong(),
            wallClockMs = index * 10_000L,
            parentId = if (index == 1) null else EventId("e${index - 1}"),
            payload = payload,
        )
}
