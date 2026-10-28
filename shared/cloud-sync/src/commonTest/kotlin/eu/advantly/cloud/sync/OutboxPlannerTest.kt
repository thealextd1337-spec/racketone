package eu.advantly.cloud.sync

import eu.advantly.data.StoredEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OutboxPlannerTest {
    private fun event(
        match: String,
        seq: Long,
        device: String = "d1",
        type: String = "PointWon",
    ) = StoredEvent(
        id = "$match-$device-$seq",
        matchId = match,
        deviceId = device,
        deviceSeq = seq,
        lamport = seq,
        payload = """{"type":"$type","side":"A"}""",
        wallClockMs = seq * 1000,
        type = type,
    )

    private fun header(id: String) = MatchDto(id = id, sport = "tennis", discipline = "singles")

    @Test
    fun splitsIntoBatchesOfAtMost500AndCarriesHeaders() {
        val events = (1L..700L).map { event("m1", it) } + (1L..501L).map { event("m2", it) }
        val device = DeviceDto("d1", "phone", "android")
        val batches =
            OutboxPlanner.plan(
                events = events.shuffled(kotlin.random.Random(7)),
                headers = mapOf("m1" to header("m1"), "m2" to header("m2"), "m3" to header("m3")),
                devices = listOf(device),
            )

        assertEquals(listOf(500, 500, 201), batches.map { it.events.size })
        assertEquals(listOf(device), batches[0].devices)
        assertTrue(batches.drop(1).all { it.devices.isEmpty() && it.players.isEmpty() }, "Geräte/Spieler nur im ersten Batch")
        assertEquals(listOf("m3", "m1"), batches[0].matches.map { it.id }, "Kopf ohne Events (m3) + Kopf zu den Events")
        assertEquals(listOf("m1", "m2"), batches[1].matches.map { it.id })
        assertEquals(listOf("m2"), batches[2].matches.map { it.id })
        assertEquals((1L..500L).toList(), batches[0].events.map { it.lamport }, "Events in Event-Ordnung")
    }

    @Test
    fun projectionTravelsOnlyWithTheLastChunkOfItsMatch() {
        val events = (1L..700L).map { event("m1", it) }
        val head = header("m1").copy(projection = ProjectionDto(status = "in_progress", eventCount = 700))
        val batches = OutboxPlanner.plan(events, headers = mapOf("m1" to head))

        assertEquals(listOf(null, head.projection), batches.map { it.matches.single().projection })
    }

    @Test
    fun projectionAheadOfTheServerIsRetried() {
        val head = header("m1").copy(projection = ProjectionDto(status = "in_progress", eventCount = 1))
        val sent = OutboxPlanner.plan(listOf(event("m1", 1)), headers = mapOf("m1" to head)).single()
        val ahead =
            OutboxPlanner.evaluate(
                sent,
                PushResponse(matches = listOf(ItemResult("m1", "updated", projection = "ahead_of_events"))),
            )
        val applied = OutboxPlanner.evaluate(sent, PushResponse(matches = listOf(ItemResult("m1", "updated", projection = "applied"))))

        assertEquals(setOf("m1"), ahead.projectionRetry)
        assertEquals(emptySet(), applied.projectionRetry)
    }

    @Test
    fun deduplicatesAndHandlesEmptyOutbox() {
        assertEquals(emptyList(), OutboxPlanner.plan(emptyList()))
        val onlyHeader = OutboxPlanner.plan(emptyList(), headers = mapOf("m1" to header("m1")))
        assertEquals(1, onlyHeader.size)
        assertEquals(0, onlyHeader.single().events.size)
        val twice = OutboxPlanner.plan(listOf(event("m1", 1), event("m1", 1)))
        assertEquals(1, twice.single().events.size)
        assertFailsWith<IllegalArgumentException> { OutboxPlanner.plan(listOf(event("m1", 1)), maxEvents = 501) }
    }

    @Test
    fun evaluatesServerResults() {
        val sent = OutboxPlanner.plan((1L..5L).map { event("m1", it) }).single()
        val ids = sent.events.map { it.id }
        val response =
            PushResponse(
                events =
                    listOf(
                        ItemResult(ids[0], "accepted"),
                        ItemResult(ids[1], "duplicate"),
                        ItemResult(ids[2], "rejected", "id_conflict"),
                        ItemResult(ids[3], "rejected", "unknown_match"),
                        // ids[4] fehlt in der Antwort (z. B. abgebrochene Verbindung) → erneut senden
                    ),
            )
        val update = OutboxPlanner.evaluate(sent, response)
        assertEquals(setOf(ids[0], ids[1]), update.uploaded)
        assertEquals(mapOf(ids[2] to "id_conflict"), update.rejected)
        assertEquals(setOf(ids[3], ids[4]), update.retry)
    }

    /** Ergebnis-Korrektur eines Nicht-Erstellers (0.3.0): endgültig abgewiesen, kein Endlos-Retry. */
    @Test
    fun correctionForbiddenIsRejectedNotRetried() {
        val sent = OutboxPlanner.plan(listOf(event("m1", 1))).single()
        val id = sent.events.single().id
        val update = OutboxPlanner.evaluate(sent, PushResponse(events = listOf(ItemResult(id, "rejected", "correction_forbidden"))))
        assertEquals(mapOf(id to "correction_forbidden"), update.rejected)
        assertEquals(emptySet(), update.retry)
    }
}
