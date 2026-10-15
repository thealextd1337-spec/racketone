package eu.advantly.data

/**
 * Ein gespeichertes Match-Event (Minimal-Schema aus Spike S1).
 * [payload] ist das serialisierte Event (JSON); das Format legt später `sync-protocol` fest.
 */
data class StoredEvent(
    val id: String,
    val matchId: String,
    val deviceId: String,
    val deviceSeq: Long,
    val lamport: Long,
    val payload: String,
)

/** Ergebnis von [EventStore.append]. */
enum class AppendResult {
    /** Event war neu und ist gespeichert. */
    INSERTED,

    /** Event mit derselben `id` existiert schon – idempotent ignoriert (Normalfall bei Wiederholungen/Sync). */
    DUPLICATE,

    /**
     * Andere `id`, aber `(matchId, deviceId, deviceSeq)` ist schon belegt. Darf bei korrekt arbeitenden
     * Geräten nicht vorkommen (lückenlose Sequenz je Gerät, 01 §4.3) – wird nicht gespeichert.
     */
    SEQUENCE_CONFLICT,
}

/**
 * Append-only Event-Log aller Matches (lokale Single Source of Truth, 01 §5).
 * Synchron: ein Match hat < 500 Events, Zugriffe sind klein. Nicht thread-sicher über Plattformgrenzen hinweg –
 * Aufrufer nutzen einen Thread bzw. eine serielle Queue.
 */
interface EventStore {
    /** Speichert [event] idempotent über `id`. */
    fun append(event: StoredEvent): AppendResult

    /** Alle Events eines Matches in der globalen Ordnung `(lamport, deviceId, id)` (01 §3.2). */
    fun eventsFor(matchId: String): List<StoredEvent>

    /** Anzahl der Events eines Matches. */
    fun count(matchId: String): Long
}
