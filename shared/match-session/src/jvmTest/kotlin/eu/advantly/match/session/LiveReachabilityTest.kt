package eu.advantly.match.session

import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.Presets
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * M2 (Review): Der Kopf des Handys trägt Aufschlag und Erreichbarkeit des Zählers für die Live-Projektion; die
 * Erreichbarkeit folgt der Verbindung zur zählenden Uhr (neu geschrieben, damit der Upload „Zähler offline“ meldet).
 */
class LiveReachabilityTest {
    private val pair = LifecyclePair().also { it.activate("A") }

    @AfterTest
    fun tearDown() = pair.close()

    @Test
    fun headerCarriesServingAndFollowsTheWatchConnection() {
        val me =
            pair.phoneData.players
                .self()!!
                .id
        val match =
            pair.phone.create(
                Presets.TENNIS_SINGLES_BO3.format(),
                Lineup(listOf(me), listOf(PlayerId("bea")), me),
                MatchMeta(),
                ScorerChoice.MyWatch,
            )
        pair.settle()
        assertEquals(true, pair.watch.recordPoint(Side.A))
        pair.settle()

        fun score() = assertNotNull(pair.phoneData.matches.get(match)).score
        assertEquals(true, score().scorerConnected)
        assertNotNull(score().serving, "Aufschlag aus dem Engine-Zustand")

        pair.network.setConnected("watch", false)
        pair.settle()
        assertEquals(false, score().scorerConnected, "Uhr weg: Zähler nicht erreichbar")
        pair.network.setConnected("watch", true)
        pair.settle()
        assertEquals(true, score().scorerConnected)
    }
}
