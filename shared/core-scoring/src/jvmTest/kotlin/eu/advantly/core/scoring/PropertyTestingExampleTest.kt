package eu.advantly.core.scoring

import io.kotest.common.ExperimentalKotest
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Vorlage für Property-Tests mit Kotest Property (Welle 1 ersetzt das durch echte Engine-Invarianten,
 * z. B. „Undo(Punkt) ≡ ohne Punkt“ oder „Zustellreihenfolge egal → gleicher Zustand“, siehe 01 §8).
 * Fester Seed, damit Fehlschläge reproduzierbar sind (`PropTestConfig(seed)` ist in Kotest noch experimentell).
 */
@OptIn(ExperimentalKotest::class)
class PropertyTestingExampleTest {
    private val config = PropTestConfig(seed = 2026L)

    /** Platzhalter-„Engine“: zählt Punkte je Seite (true = Seite A). */
    private fun tally(points: List<Boolean>): Pair<Int, Int> = points.count { it } to points.count { !it }

    @Test
    fun tallyIsIndependentOfDeliveryOrder() {
        runBlocking {
            checkAll(config, Arb.list(Arb.boolean(), 0..200)) { points ->
                val shuffled = points.shuffled(Random(points.size))
                assertEquals(tally(points), tally(shuffled))
            }
        }
    }

    @Test
    fun tallyAccountsForEveryPoint() {
        runBlocking {
            checkAll(config, Arb.list(Arb.boolean(), 0..200)) { points ->
                val (a, b) = tally(points)
                assertEquals(points.size, a + b)
            }
        }
    }
}
