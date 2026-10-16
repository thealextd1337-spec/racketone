package eu.advantly.data

import app.cash.sqldelight.db.SqlDriver
import eu.advantly.data.db.AdvantlyDatabase

/** Lokale Upload-Berechtigung; nur [CloudUploadStore.activate] kann sie ausstellen. Kein Auth-Token. */
class CloudUploadSession internal constructor(
    val profileId: String,
    internal val generation: Long,
)

/**
 * Unveränderliche Auswahl für genau einen Upload-Versuch. Antworten dürfen ausschließlich diese Events betreffen.
 * Die Listen-Kopie verhindert, dass ein Aufrufer die interne Auswahl nachträglich verändert.
 */
class CloudUploadSnapshot internal constructor(
    internal val session: CloudUploadSession,
    events: List<StoredEvent>,
) {
    private val selected = events.associateBy { it.id }
    val profileId: String get() = session.profileId
    val events: List<StoredEvent> get() = selected.values.toList()

    internal fun selectedEvent(id: String): StoredEvent? = selected[id]
}

/**
 * Kontogebundene Upload-Grenze über den lokalen B1-Primitiven. Alle Aufrufe seriell wie [LocalData].
 *
 * Der spätere Auth-Host muss bei jeder neuen authentifizierten Sitzung [activate] und bei Logout,
 * Kontowechsel, Löschantrag oder Session-Verlust [deactivate] aufrufen. Aktivieren allein bindet keine Geräte.
 * Nur vertrauenswürdig ermittelte eigene Geräte dürfen danach explizit über [bindOwnedDevices] übernommen werden.
 * Ein bereits gebundenes Gerät wechselt seinen Eigentümer nie; ein anderes Konto braucht eine neue Geräte-ID.
 *
 * Noch keine Netzwerkanbindung: B2b muss den HTTP-Aufruf an dieselbe Auth-Sitzung binden und vor dem Versand
 * [requireCurrent] prüfen. Diese lokale Berechtigung authentifiziert selbst keine Anfrage beim Server.
 */
class CloudUploadStore internal constructor(
    private val database: AdvantlyDatabase,
    private val cloud: CloudSyncRepository,
    private val driver: SqlDriver,
) {
    private val queries = database.cloudUploadQueries
    private var active: CloudUploadSession? = null

    /** Neue Sitzung, auch bei erneuter Anmeldung am selben Konto. Alte Antworten verlieren ihre Gültigkeit. */
    fun activate(profileId: String): CloudUploadSession {
        requireOutsideTransaction()
        active = null
        require(profileId.isNotBlank())
        return database
            .transactionWithResult {
                CloudUploadSession(profileId, advanceGeneration())
            }.also { active = it }
    }

    /** Sofort lokal sperren; keine Verbindung zum Server erforderlich. Persistente Eigentümer bleiben erhalten. */
    fun deactivate() {
        requireOutsideTransaction()
        active = null
        database.transaction { advanceGeneration() }
    }

    /** Sitzung/Versandauswahl dürfen keinen später rückrollbaren äußeren Schreibvorgang überleben. */
    private fun requireOutsideTransaction() {
        check(driver.currentTransaction() == null) { "Upload-Sitzung und Auswahl nur außerhalb einer Datentransaktion ändern" }
    }

    private fun advanceGeneration(): Long {
        val previous = queries.generation().executeAsOne()
        check(previous < Long.MAX_VALUE) { "Upload-Sitzungszähler erschöpft" }
        val next = previous + 1
        queries.saveGeneration(next)
        return next
    }

    private fun requireActive(session: CloudUploadSession) {
        check(active === session && queries.generation().executeAsOne() == session.generation) { "Upload-Sitzung nicht mehr aktiv" }
    }

    /** Gesamte Liste atomar binden; ein fremdes Gerät führt zu keiner teilweisen Übernahme. */
    fun bindOwnedDevices(
        session: CloudUploadSession,
        deviceIds: Set<String>,
    ) {
        require(deviceIds.all { it.isNotBlank() })
        database.transaction {
            requireActive(session)
            deviceIds.forEach { deviceId ->
                val owner = queries.deviceOwner(deviceId).executeAsOneOrNull()
                check(owner == null || owner == session.profileId) { "Gerät gehört bereits einem anderen Konto" }
                queries.bindDevice(deviceId, session.profileId)
            }
        }
    }

    /** Begrenzte, deterministisch sortierte Auswahl; fremde und ungebundene Quellgeräte bleiben ausgeschlossen. */
    fun capture(
        session: CloudUploadSession,
        limit: Int,
    ): CloudUploadSnapshot {
        requireOutsideTransaction()
        require(limit > 0)
        return database.transactionWithResult {
            requireActive(session)
            val events =
                queries
                    .pendingForProfile(
                        session.profileId,
                        limit.toLong(),
                    ) { id, match, device, seq, lamport, at, parent, type, payload, confirmed ->
                        StoredEvent(id, match, device, seq, lamport, payload, at, parent, type, confirmed == 1L)
                    }.executeAsList()
            CloudUploadSnapshot(session, events)
        }
    }

    /** Vor dem Netzwerkversand erneut prüfen; B2b muss zusätzlich die verwendeten Credentials binden. */
    fun requireCurrent(snapshot: CloudUploadSnapshot) {
        requireActive(snapshot.session)
    }

    /**
     * Antwort an Sitzung UND Auswahl binden. Unbekannte IDs oder geänderte Events rollen den gesamten Ack zurück.
     * Zwischenzeitlich gelöschte Events werden übersprungen; weder sie noch ihre Match-Köpfe werden wieder angelegt.
     * Fehlende IDs bleiben ausstehend. Geräte-Acks werden niemals geändert.
     */
    fun acknowledge(
        snapshot: CloudUploadSnapshot,
        uploaded: Set<String>,
        rejected: Map<String, String> = emptyMap(),
    ) {
        require(uploaded.intersect(rejected.keys).isEmpty())
        require(rejected.values.all { it.isNotBlank() })
        val ids = uploaded + rejected.keys
        database.transaction {
            requireCurrent(snapshot)
            val existingIds = mutableSetOf<String>()
            ids.forEach { id ->
                val selected = requireNotNull(snapshot.selectedEvent(id)) { "Antwort enthält ein nicht ausgewähltes Event" }
                val current =
                    queries
                        .eventById(id) { eventId, match, device, seq, lamport, at, parent, type, payload, confirmed ->
                            StoredEvent(eventId, match, device, seq, lamport, payload, at, parent, type, confirmed == 1L)
                        }.executeAsOneOrNull()
                if (current != null) {
                    check(current.copy(confirmed = false) == selected.copy(confirmed = false)) { "Event seit Upload-Auswahl verändert" }
                    existingIds += id
                }
            }
            cloud.acknowledge(uploaded.intersect(existingIds), rejected.filterKeys { it in existingIds })
        }
    }
}
