package eu.advantly.data

import eu.advantly.data.db.AdvantlyDatabase
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Persistente P2-B-Primitive ohne Netzwerk-/Auth-Abhängigkeit. Wie [LocalData] seriell verwenden.
 * Ein Geräte-Ack bestätigt niemals den Cloud-Upload. App/Worker werden erst in P2-B2 angebunden;
 * dort müssen Kontozuordnung und Upload-Rechte vor der Auswahl der Events geprüft werden.
 */
class CloudSyncRepository internal constructor(
    internal val database: AdvantlyDatabase,
    private val events: EventStore,
    private val sanitizeDownloaded: (StoredEvent, () -> Unit) -> StoredEvent,
    internal val redactedAssignmentSlots: (String) -> Set<String>,
) {
    private val queries = database.cloudSyncQueries

    fun pendingEvents(limit: Int): List<StoredEvent> {
        require(limit > 0)
        return queries
            .pendingEvents(limit.toLong()) { id, match, device, seq, lamport, at, parent, type, payload, confirmed ->
                StoredEvent(id, match, device, seq, lamport, payload, at, parent, type, confirmed == 1L)
            }.executeAsList()
    }

    fun pendingCount(): Long = queries.pendingCount().executeAsOne()

    fun rejectedEvents(): Map<String, String> =
        queries.rejectedEvents { id, reason -> id to requireNotNull(reason) }.executeAsList().toMap()

    /**
     * `device_not_owned` wieder ausstehend: Nach einer neuen Upload-Sitzung oder neuen G kann das Quellgerät (etwa eine
     * erst später freigegebene Uhr) wieder gelten. Kontoübergreifend; kostet höchstens eine weitere Ablehnungsrunde.
     */
    internal fun resetDeviceRejections() = queries.resetDeviceRejections()

    /** Nur tatsächlich bestätigte IDs übergeben. Fehlende Antworten bleiben ausstehend; Anwendung ist atomar. */
    fun acknowledge(
        uploaded: Set<String>,
        rejected: Map<String, String> = emptyMap(),
    ) {
        require(uploaded.intersect(rejected.keys).isEmpty())
        require(rejected.values.all { it.isNotBlank() })
        database.transaction {
            uploaded.forEach(queries::markUploaded)
            rejected.forEach { (id, reason) -> queries.markRejected(reason, id) }
        }
    }

    /**
     * Download atomar speichern, Cloud-Bestätigung setzen und Wiederholungen deduplizieren.
     * Neue Events bleiben für Geräte ausstehend; Duplikate behalten ihren bisherigen Geräte-Ack.
     * Abweichender Inhalt unter gleicher ID oder eine belegte Gerätesequenz bricht den ganzen Batch ab.
     * Events lokal gelöschter Matches werden verworfen, damit ein später Download sie nicht wiederherstellt.
     * Cursor muss der Aufrufer in derselben [LocalData.transaction] wie den vollständigen Empfang speichern.
     * [fence] prüft den Kontext zwischen begrenzten lokalen Einheiten; ein Abbruch rollt den ganzen Batch zurück.
     */
    fun storeDownloaded(
        downloaded: List<StoredEvent>,
        fence: () -> Unit = {},
    ) {
        database.transaction {
            fence()
            downloaded.forEach { event ->
                fence()
                if (database.matchTombstoneQueries.countById(event.matchId).executeAsOne() > 0) return@forEach
                val originalPayload = DataJson.parseToJsonElement(event.payload).jsonObject
                val received = sanitizeDownloaded(event.copy(confirmed = false, type = effectiveType(event, originalPayload)), fence)
                val payload = DataJson.parseToJsonElement(received.payload).jsonObject
                fence()
                when (events.append(received)) {
                    AppendResult.INSERTED -> {}

                    AppendResult.DUPLICATE -> {
                        val existing =
                            database.matchEventQueries
                                .eventById(event.id) { id, match, device, seq, lamport, at, parent, type, json ->
                                    StoredEvent(id, match, device, seq, lamport, json, at, parent, type)
                                }.executeAsOneOrNull()
                        require(existing != null && sameContent(existing, received, payload)) { "Cloud-Event-ID mit abweichendem Inhalt" }
                    }

                    AppendResult.SEQUENCE_CONFLICT -> {
                        error("Cloud-Event mit belegter Gerätesequenz")
                    }
                }
                fence()
                queries.markUploaded(event.id)
                fence()
            }
            fence()
        }
    }

    /** JSONB darf Objektfelder umordnen; die gespeicherte Payload selbst bleibt unverändert. */
    private fun sameContent(
        existing: StoredEvent,
        received: StoredEvent,
        receivedPayload: JsonObject,
    ): Boolean {
        val existingPayload = DataJson.parseToJsonElement(existing.payload).jsonObject
        val normalized = existing.copy(payload = "", type = effectiveType(existing, existingPayload), confirmed = false)
        return normalized == received.copy(payload = "") && existingPayload == receivedPayload
    }

    /** Alte P1-Zeilen können den Typ ausschließlich in der Payload enthalten. */
    private fun effectiveType(
        event: StoredEvent,
        payload: JsonObject,
    ): String {
        val type = (payload["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        require(!type.isNullOrEmpty() && (event.type.isEmpty() || event.type == type)) { "Event-Typ passt nicht zur Payload" }
        return type
    }

    fun cursor(
        profileId: String,
        stream: String,
    ): Long {
        require(profileId.isNotBlank() && stream.isNotBlank())
        return queries.cursor(profileId, stream).executeAsOneOrNull() ?: 0L
    }

    /** Ein Vollabgleich darf den Cursor gezielt auf null zurücksetzen; keine implizite monotone Maximierung. */
    fun saveCursor(
        profileId: String,
        stream: String,
        value: Long,
    ) {
        require(profileId.isNotBlank() && stream.isNotBlank() && value >= 0)
        queries.saveCursor(profileId, stream, value)
    }

    fun clearCursors(profileId: String) {
        require(profileId.isNotBlank())
        queries.clearCursors(profileId)
    }
}
