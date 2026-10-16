package eu.advantly.data

import eu.advantly.data.db.AdvantlyDatabase

/**
 * Persistente P2-B-Primitive ohne Netzwerk-/Auth-Abhängigkeit. Wie [LocalData] seriell verwenden.
 * Ein Geräte-Ack bestätigt niemals den Cloud-Upload. App/Worker werden erst in P2-B2 angebunden;
 * dort müssen Kontozuordnung und Upload-Rechte vor der Auswahl der Events geprüft werden.
 */
class CloudSyncRepository internal constructor(
    private val database: AdvantlyDatabase,
    private val events: EventStore,
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
     * Download atomar speichern, beide Bestätigungen setzen und Wiederholungen deduplizieren.
     * Abweichender Inhalt unter gleicher ID oder eine belegte Gerätesequenz bricht den ganzen Batch ab.
     * Events lokal gelöschter Matches werden verworfen, damit ein später Download sie nicht wiederherstellt.
     * Cursor muss der Aufrufer in derselben [LocalData.transaction] wie den vollständigen Empfang speichern.
     */
    fun storeDownloaded(downloaded: List<StoredEvent>) {
        database.transaction {
            downloaded.forEach { event ->
                if (database.matchTombstoneQueries.countById(event.matchId).executeAsOne() > 0) return@forEach
                val received = event.copy(confirmed = true)
                when (events.append(received)) {
                    AppendResult.INSERTED -> {}

                    AppendResult.DUPLICATE -> {
                        val existing = events.eventsFor(event.matchId).firstOrNull { it.id == event.id }
                        require(existing?.copy(confirmed = true) == received) { "Cloud-Event-ID mit abweichendem Inhalt" }
                    }

                    AppendResult.SEQUENCE_CONFLICT -> {
                        error("Cloud-Event mit belegter Gerätesequenz")
                    }
                }
                queries.confirmDownloaded(event.id)
            }
        }
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
