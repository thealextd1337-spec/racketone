package eu.advantly.cloud.sync

import eu.advantly.data.StoredEvent

/** Auswertung einer `sync_push`-Antwort für die lokale Outbox. */
data class OutboxUpdate(
    /** `accepted` oder `duplicate`: liegt in der Cloud → nie wieder senden. */
    val uploaded: Set<String>,
    /** Dauerhaft abgewiesen (Grund-Schlüssel des Servers) → nicht wiederholen, in Diagnose/UI melden. */
    val rejected: Map<String, String>,
    /** Vorübergehend (keine Antwort, Match-Kopf fehlt noch) → im nächsten Lauf erneut senden. */
    val retry: Set<String>,
)

/**
 * Reine Outbox-Logik (ohne I/O): Batches für `sync_push` bilden und Antworten auswerten.
 *
 * Regeln (docs/p2-plan.md §5):
 * - höchstens [PushBatch.MAX_EVENTS_PER_PUSH] Events je Aufruf; Events eines Matches bleiben in Event-Ordnung
 *   `(lamport, deviceId, id)` zusammen, Matches in fester Reihenfolge (nach ID) → deterministische Batches;
 * - der Kopf eines Matches reist in jedem Batch mit, der Events dieses Matches enthält (Server legt Events nur zu
 *   bekannten Matches an); Köpfe ohne Events (z. B. nur Projektion) und Geräte/Spieler im ersten Batch;
 * - Wiederholungen sind gefahrlos: der Server ist idempotent über die Event-ID.
 */
object OutboxPlanner {
    /** Gründe, bei denen ein erneuter Versuch sinnvoll ist (z. B. Kopf kam im selben Lauf zu spät an). */
    val RETRYABLE_REASONS: Set<String> = setOf("unknown_match")

    fun plan(
        events: List<StoredEvent>,
        headers: Map<String, MatchDto> = emptyMap(),
        devices: List<DeviceDto> = emptyList(),
        players: List<PlayerDto> = emptyList(),
        maxEvents: Int = PushBatch.MAX_EVENTS_PER_PUSH,
    ): List<PushBatch> {
        require(maxEvents in 1..PushBatch.MAX_EVENTS_PER_PUSH) { "maxEvents außerhalb 1..${PushBatch.MAX_EVENTS_PER_PUSH}" }
        val ordered =
            events
                .distinctBy { it.id }
                .sortedWith(compareBy<StoredEvent>({ it.matchId }, { it.lamport }, { it.deviceId }, { it.id }))
                .map(EventRows::toRow)
        val chunks = ordered.chunked(maxEvents)
        val eventMatches = ordered.mapTo(mutableSetOf()) { it.matchId }
        val headerOnly = headers.filterKeys { it !in eventMatches }.values.sortedBy { it.id }

        if (chunks.isEmpty()) {
            return if (headerOnly.isEmpty() && devices.isEmpty() && players.isEmpty()) {
                emptyList()
            } else {
                listOf(PushBatch(devices = devices, players = players, matches = headerOnly))
            }
        }
        return chunks.mapIndexed { index, chunk ->
            val chunkHeaders = chunk.map { it.matchId }.distinct().mapNotNull { headers[it] }
            if (index == 0) {
                PushBatch(devices = devices, players = players, matches = headerOnly + chunkHeaders, events = chunk)
            } else {
                PushBatch(matches = chunkHeaders, events = chunk)
            }
        }
    }

    fun evaluate(
        sent: PushBatch,
        response: PushResponse,
    ): OutboxUpdate {
        val byId = response.events.filter { it.id != null }.associateBy { it.id }
        val uploaded = mutableSetOf<String>()
        val rejected = mutableMapOf<String, String>()
        val retry = mutableSetOf<String>()
        for (event in sent.events) {
            val result = byId[event.id]
            when {
                result == null -> retry += event.id
                result.result == "accepted" || result.result == "duplicate" -> uploaded += event.id
                result.reason in RETRYABLE_REASONS -> retry += event.id
                else -> rejected[event.id] = result.reason ?: result.result
            }
        }
        return OutboxUpdate(uploaded, rejected, retry)
    }
}
