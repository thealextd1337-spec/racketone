package eu.advantly.umbrella.watch

import eu.advantly.data.AppendResult
import eu.advantly.data.EventStore
import eu.advantly.data.StoredEvent

/**
 * Swift-Facade des lokalen Event-Stores (Spike S1). Nur einfache Typen an der Grenze.
 * Swift: `AdvantlyWatch.shared.store().append(id:matchId:deviceId:deviceSeq:lamport:payload:)`.
 */
class WatchStore internal constructor(
    private val events: EventStore,
) {
    /** `true`, wenn das Event neu gespeichert wurde; `false` bei Duplikat (gleiche `id`) oder Sequenzkonflikt. */
    fun append(
        id: String,
        matchId: String,
        deviceId: String,
        deviceSeq: Long,
        lamport: Long,
        payload: String,
    ): Boolean = events.append(StoredEvent(id, matchId, deviceId, deviceSeq, lamport, payload)) == AppendResult.INSERTED

    fun count(matchId: String): Long = events.count(matchId)

    /** Event-IDs eines Matches in globaler Ordnung (lamport, deviceId, id). */
    fun eventIds(matchId: String): List<String> = events.eventsFor(matchId).map { it.id }

    /**
     * Start-Smoke-Test der Watch-App: schreibt idempotent ein festes Event und liefert die Anzahl im Smoke-Match.
     * Erwartet: immer 1 – auch nach Neustarts (Datei bleibt, `append` ist idempotent).
     */
    fun smokeCheck(): Long {
        append(id = SMOKE_EVENT_ID, matchId = SMOKE_MATCH_ID, deviceId = "watch", deviceSeq = 1, lamport = 1, payload = "{}")
        return count(SMOKE_MATCH_ID)
    }

    internal companion object {
        const val SMOKE_MATCH_ID = "smoke-match"
        const val SMOKE_EVENT_ID = "smoke-event-1"
    }
}
