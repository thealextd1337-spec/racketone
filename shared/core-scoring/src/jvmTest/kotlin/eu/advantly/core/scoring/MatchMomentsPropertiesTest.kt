package eu.advantly.core.scoring

import eu.advantly.core.model.MatchMode
import io.kotest.common.ExperimentalKotest
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Invarianten der Highlights über zufällige Matches aller Presets ([RandomMatches]), fester Seed. */
@OptIn(ExperimentalKotest::class)
class MatchMomentsPropertiesTest {
    private val config = PropTestConfig(seed = 2026L, iterations = 300)

    @Test
    fun momentsAreOrderedEffectivePointsWithSingleMatchWon() {
        runBlocking {
            checkAll(config, Arb.int(0 until Presets.all.size), Arb.long(), Arb.int(0..220)) { preset, seed, length ->
                val m = RandomMatches.generate(preset, seed, length)
                val points = ScoringEngine.pointHistory(m.format, m.lineup, m.events, null)
                val moments = MatchMoments.derive(points)
                // Teilmenge der wirksamen Punkte, streng aufsteigend, nie leer
                assertTrue(moments.zipWithNext().all { (a, b) -> a.pointIndex < b.pointIndex })
                moments.forEach { moment ->
                    assertEquals(points[moment.pointIndex].event.id, moment.eventId)
                    assertTrue(moment.kinds.isNotEmpty())
                }
                val state = ScoringEngine.fold(m.format, m.lineup, m.events)
                val matchWon = moments.count { MomentKind.MATCH_WON in it.kinds }
                // Genau ein MATCH_WON, wenn ein Punkt das Match entschied (Best of); Aufgabe und OPEN-Ende haben keinen Punkt
                val wonByPoint = state.result?.reason == ResultReason.COMPLETED && m.format.match.mode == MatchMode.BEST_OF
                assertEquals(if (wonByPoint) 1 else 0, matchWon)
                assertEquals(moments, MatchMoments.derive(points), "deterministisch")
            }
        }
    }
}
