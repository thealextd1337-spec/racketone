package eu.advantly.data

/**
 * Ein gespeichertes Match-Event (01 §5.1 `match_event`). [payload] ist der serialisierte `EventPayload` als JSON in
 * derselben Form wie auf dem Draht (`{"type":"PointWon","side":"A"}`); typisierte Umwandlung über
 * [toScoreEvent]/[toStoredEvent].
 *
 * @property wallClockMs nur Anzeige/Statistik und Doppel-Tap-Erkennung
 * @property parentId Log-Kopf, den das erzeugende Gerät gesehen hat (Fork-Erkennung)
 * @property type Payload-Typ (z. B. `PointWon`) für Abfragen ohne JSON-Parsing
 */
data class StoredEvent(
    val id: String,
    val matchId: String,
    val deviceId: String,
    val deviceSeq: Long,
    val lamport: Long,
    val payload: String,
    val wallClockMs: Long = 0,
    val parentId: String? = null,
    val type: String = "",
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

/** Ein `(deviceId, deviceSeq)`-Paar eines gespeicherten Events (Grundlage des Versionsvektors). */
data class StoredSequence(
    val deviceId: String,
    val deviceSeq: Long,
)

/**
 * Append-only Event-Log aller Matches (lokale Single Source of Truth, 01 §5).
 * Synchron: ein Match hat < 500 Events, Zugriffe sind klein. Nicht thread-sicher über Plattformgrenzen hinweg –
 * Aufrufer nutzen einen Thread bzw. eine serielle Queue.
 */
interface EventStore {
    /** Speichert [event] idempotent über `id`. */
    fun append(event: StoredEvent): AppendResult

    /** Speichert einen Empfangs-Batch in **einer** Transaktion; Ergebnis je Event in Eingabereihenfolge. */
    fun appendAll(events: List<StoredEvent>): List<AppendResult>

    /** Alle Events eines Matches in der globalen Ordnung `(lamport, deviceId, id)` (01 §3.2). */
    fun eventsFor(matchId: String): List<StoredEvent>

    /** Events von [deviceId] mit `deviceSeq` in [fromSeq]..[toSeq] (Anti-Entropie-Nachlieferung), in globaler Ordnung. */
    fun eventsInRange(
        matchId: String,
        deviceId: String,
        fromSeq: Long,
        toSeq: Long,
    ): List<StoredEvent>

    /** Alle vorhandenen `(deviceId, deviceSeq)` eines Matches (sortiert) – daraus entsteht der Versionsvektor. */
    fun sequences(matchId: String): List<StoredSequence>

    /** Höchste Lamport-Zeit eines Matches (0 ohne Events). */
    fun maxLamport(matchId: String): Long

    /** Höchste `deviceSeq` von [deviceId] in einem Match (0 ohne Events). */
    fun maxDeviceSeq(
        matchId: String,
        deviceId: String,
    ): Long

    /** Anzahl der Events eines Matches. */
    fun count(matchId: String): Long

    /** Löscht alle Events eines Matches (nur für „Match löschen“ in der Historie). */
    fun deleteMatch(matchId: String)
}
