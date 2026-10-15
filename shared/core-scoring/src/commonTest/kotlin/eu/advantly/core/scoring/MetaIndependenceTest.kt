package eu.advantly.core.scoring

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.EventId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import kotlin.test.Test
import kotlin.test.assertEquals

/** `MatchMeta` hat keine Regelwirkung (02 §10) – auch das neue Feld `watchWorkout` (W1-I) ändert den Fold nicht. */
class MetaIndependenceTest {
    private val format = Presets.TENNIS_SINGLES_BO3.format()
    private val lineup = Lineup(listOf(PlayerId("a")), listOf(PlayerId("b")), PlayerId("a"))

    private fun log(meta: MatchMeta): List<ScoreEvent> {
        val genesis = ScoreEvent(EventId("g"), MatchId("m"), DeviceId("d"), 1, 1, 0, null, MatchCreated(format, lineup, meta))
        val points =
            "AABABAAA".mapIndexed { i, c ->
                ScoreEvent(
                    EventId("e$i"),
                    MatchId("m"),
                    DeviceId("d"),
                    i + 2L,
                    i + 2L,
                    0,
                    null,
                    PointWon(
                        if (c ==
                            'A'
                        ) {
                            Side.A
                        } else {
                            Side.B
                        },
                    ),
                )
            }
        return listOf(genesis) + points
    }

    @Test
    fun watchWorkoutDoesNotChangeTheScore() {
        val withWorkout = ScoringEngine.fold(log(MatchMeta()))
        val withoutWorkout = ScoringEngine.fold(log(MatchMeta(watchWorkout = false)))
        assertEquals(withWorkout, withoutWorkout)
        assertEquals(true, MatchMeta().watchWorkout)
    }
}
