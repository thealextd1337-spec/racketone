package eu.advantly.match.session

import eu.advantly.core.model.Lineup
import eu.advantly.core.model.Placeholders
import eu.advantly.core.model.PlayerId
import eu.advantly.core.scoring.Presets
import eu.advantly.sync.protocol.Events
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Events-Batches nach Bytes (64 KiB samt Envelope), Übergröße bricht `send` nie ab. */
class EventBatchBytesTest {
    private var pair: LifecyclePair? = null

    @AfterTest
    fun tearDown() {
        pair?.close()
    }

    private val slot = Placeholders.id("opponent")

    private fun LifecyclePair.matchWithGuests(nameLength: Int): Triple<eu.advantly.core.model.MatchId, PlayerId, PlayerId> {
        val self = phoneData.players.self()!!.id
        val a = PlayerId("guest-a")
        val b = PlayerId("guest-b")
        phoneData.players.addGuest(a, "A".repeat(nameLength), 0)
        phoneData.players.addGuest(b, "B".repeat(nameLength), 0)
        val match = phone.create(Presets.TENNIS_SINGLES_BO3.format(), Lineup(listOf(self), listOf(slot), self))
        return Triple(match, a, b)
    }

    @Test
    fun aLargeBacklogTravelsInPacketsBelowTheLimit() {
        val pair = LifecyclePair().also { pair = it }
        val (match, a, b) = pair.matchWithGuests(3_000)
        pair.settle()
        pair.network.setConnected("watch", false)
        repeat(30) { assertTrue(pair.phone.assignPlayer(match, slot, if (it % 2 == 0) a else b)) }
        pair.phoneLink.sent.clear()
        pair.network.setConnected("watch", true)
        pair.settle()
        val events = pair.phoneLink.sent.filter { it.decodeToString().contains("\"type\":\"Events\"") }
        assertTrue(events.isNotEmpty())
        assertTrue(events.all { it.size <= 64 * 1024 }, "jedes Events-Paket ≤ 64 KiB: ${events.map { it.size }}")
        assertEquals(pair.phoneData.events.count(match.value), pair.watchData.events.count(match.value))
    }

    @Test
    fun anOversizedEventNeverAbortsSendingAndStaysVisiblyBlocked() {
        // Protokoll 4 ohne Konto erzwungen: Ohne Konto spricht 3..4 nur 3, mit Konto ist die Zuweisung namenfrei und nie übergroß
        val pair = LifecyclePair(4..4).also { pair = it }
        val (match, a, _) = pair.matchWithGuests(70_000)
        pair.settle()
        assertTrue(pair.phone.assignPlayer(match, slot, a), "gespeichert, auch wenn das Paket zu groß ist")
        pair.settle()
        val sync = pair.phone.state.value.sync
        assertTrue(sync.lifecycleBlocked && !sync.settled)
        assertTrue(
            pair.phoneLink.decoded().none {
                (it.message as? Events)?.events?.any { e -> e.matchId == match && e.deviceSeq == 2L } ==
                    true
            },
        )
    }
}
