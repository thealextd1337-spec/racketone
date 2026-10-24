package eu.advantly.match.session

import eu.advantly.core.model.MatchId
import eu.advantly.core.model.PlayerId
import eu.advantly.data.RedactionCursor
import eu.advantly.data.RedactionPage
import eu.advantly.data.RedactionTransfer
import eu.advantly.data.ReplicaRedactionEvent
import eu.advantly.data.StoredEvent
import eu.advantly.sync.protocol.RedactedChunk
import eu.advantly.sync.protocol.SyncCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Data-Seite nahe 64 KiB → mehrere gültige Wire-Seiten ≤ 64 KiB samt Envelope, lückenlose Cursorkette, deterministisch. */
class RedactionChunksTest {
    private val transfer = RedactionTransfer("alice:m:1:1", "m", 1, 1, 10_000)

    /** Phone-Seite wie aus Data (≤ 64 KiB ohne Hülle): IDs und große, opake Event-Payloads. */
    private fun nearLimitPage(): RedactionPage {
        val ids = (0 until 40).map { "p-${it.toString().padStart(2, '0')}" }
        val events = mutableListOf<ReplicaRedactionEvent>()
        var page = RedactionPage(3, RedactionCursor("a", 5), RedactionCursor(ids.last(), 5), ids, emptyList(), true, true)
        while (true) {
            val seq = 6L + events.size
            val event =
                StoredEvent("e-$seq", "m", "d", seq, seq, """{"type":"PlayerAssigned","pad":"${"x".repeat(1_500)}"}""", seq, null, "")
            val next = page.copy(next = RedactionCursor(ids.last(), seq), events = events + ReplicaRedactionEvent(event, seq))
            if (SyncCodec.json
                    .encodeToString(RedactionPage.serializer(), next)
                    .encodeToByteArray()
                    .size > 64 * 1024
            ) {
                return page
            }
            events += ReplicaRedactionEvent(event, seq)
            page = next
        }
    }

    @Test
    fun aPageNearTheLimitBecomesSeveralValidWireChunks() {
        val page = nearLimitPage()
        val whole =
            RedactedChunk(
                "m:1:1",
                MatchId("m"),
                1,
                1,
                10_000,
                0,
                eu.advantly.sync.protocol
                    .RedactionCursor(PlayerId("a"), 5),
                eu.advantly.sync.protocol
                    .RedactionCursor(PlayerId(page.playerIds.last()), page.next.afterServerSeq),
                page.playerIds.map(::PlayerId),
                RedactionChunks.split(page, "m:1:1", transfer, 0)!!.flatMap { it.events },
                idsDone = true,
                eventsDone = true,
            )
        assertFailsWith<IllegalArgumentException>("eine Seite als ein Paket sprengt 64 KiB samt Envelope") {
            SyncCodec.encode(whole, WORST_ENVELOPE)
        }

        val chunks = RedactionChunks.split(page, "m:1:1", transfer, 7)!!
        assertTrue(chunks.size >= 2)
        assertEquals((7L until 7L + chunks.size).toList(), chunks.map { it.index })
        chunks.forEach {
            SyncCodec.encode(it, WORST_ENVELOPE)
            val stored = SyncCodec.json.encodeToString(RedactionPage.serializer(), it.page())
            assertTrue(stored.encodeToByteArray().size <= 64 * 1024, "Data-Seite der Uhr ≤ 64 KiB")
        }
        assertEquals(RedactionCursor("a", 5).afterServerSeq, chunks.first().from.afterServerSeq)
        chunks.zipWithNext().forEach { (a, b) -> assertEquals(a.next, b.from, "lückenlose Cursorkette") }
        assertEquals(page.next, chunks.last().page().next)
        assertEquals(page.playerIds, chunks.flatMap { c -> c.playerIds.map { it.value } })
        assertEquals(page.events.map { it.serverSeq }, chunks.flatMap { c -> c.events.map { it.serverSeq } })
        assertTrue(chunks.dropLast(1).none { it.idsDone && it.eventsDone }, "nur die letzte Seite schließt ab")
        assertTrue(chunks.last().idsDone && chunks.last().eventsDone)
        assertEquals(chunks, RedactionChunks.split(page, "m:1:1", transfer, 7), "deterministisch")
    }

    @Test
    fun aSingleItemThatFitsNoWirePageIsNeverSplitIntoOne() {
        val event = StoredEvent("e-1", "m", "d", 1, 1, "x".repeat(64 * 1024), 1, null, "")
        val page =
            RedactionPage(
                0,
                RedactionCursor(null, 0),
                RedactionCursor(null, 1),
                emptyList(),
                listOf(ReplicaRedactionEvent(event, 1)),
                true,
                true,
            )
        assertNull(RedactionChunks.split(page, "m:1:1", transfer, 0))
    }

    @Test
    fun anEmptyFinalPageIsOneEmptyChunk() {
        val page = RedactionPage(0, RedactionCursor(null, 0), RedactionCursor(null, 0), emptyList(), emptyList(), true, true)
        val chunk = RedactionChunks.split(page, "m:1:1", transfer, 0)!!.single()
        assertTrue(chunk.idsDone && chunk.eventsDone && chunk.playerIds.isEmpty() && chunk.events.isEmpty())
    }
}
