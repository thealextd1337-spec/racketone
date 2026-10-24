package eu.advantly.data

import app.cash.sqldelight.db.SqlDriver
import eu.advantly.data.db.AdvantlyDatabase
import eu.advantly.data.db.Cloud_metadata

/** Lokale Upload-Berechtigung; nur [CloudUploadStore.activate] kann sie ausstellen. Kein Auth-Token. */
class CloudUploadSession internal constructor(
    val profileId: String,
    internal val generation: Long,
)

/**
 * U4-Upload-Beleg: [generation] = zuletzt abgeschlossene Pull-`visibilityEpoch` G, [redactionEpochs] = E für JEDES
 * ausgewählte Match (ohne Access-Zeile 0). Ohne je abgeschlossene Runde gibt es keinen Beleg.
 */
data class CloudUploadLifecycle(
    val generation: Long,
    val redactionEpochs: Map<String, Long>,
)

/** Snapshot passt nicht mehr zu Kontext oder G/E: ohne Acks verwerfen und neu auswählen. */
class CloudUploadLifecycleChangedException : IllegalStateException("Upload-Snapshot an veralteten Lifecycle gebunden")

/**
 * Unveränderliche Auswahl für genau einen Upload-Versuch. Antworten dürfen ausschließlich diese Events betreffen.
 * Die Listen-Kopie verhindert, dass ein Aufrufer die interne Auswahl nachträglich verändert.
 */
class CloudUploadSnapshot internal constructor(
    private val store: CloudUploadStore,
    internal val session: CloudUploadSession,
    events: List<StoredEvent>,
    headers: List<CloudRevisioned<CloudMatchHeader>> = emptyList(),
    participants: List<CloudRevisioned<CloudParticipants>> = emptyList(),
    players: List<CloudRevisioned<CloudPlayer>> = emptyList(),
    projections: List<CloudRevisioned<CloudProjection>> = emptyList(),
    completeEventMatches: Set<String> = emptySet(),
    val inventory: CloudUploadInventory = CloudUploadInventory(0, 0, 0),
    /** Gebunden an Auswahl und [context]; vor Versand und Ack erneut geprüft. */
    val lifecycle: CloudUploadLifecycle? = null,
    internal val context: ReplicaContext? = null,
) {
    private val selected = events.associateBy { it.id }
    private val selectedHeaders = headers.toList()
    private val selectedParticipants = participants.toList()
    private val selectedPlayers = players.toList()
    private val selectedProjections = projections.toList()
    private val complete = completeEventMatches.toSet()
    val profileId: String get() = session.profileId
    val events: List<StoredEvent> get() = selected.values.toList()
    val headers: List<CloudRevisioned<CloudMatchHeader>> get() = selectedHeaders.toList()
    val participants: List<CloudRevisioned<CloudParticipants>> get() = selectedParticipants.toList()
    val players: List<CloudRevisioned<CloudPlayer>> get() = selectedPlayers.toList()
    val projections: List<CloudRevisioned<CloudProjection>> get() = selectedProjections.toList()
    val completeEventMatches: Set<String> get() = complete.toSet()
    val metadataRevisions: Set<CloudMetadataRevision>
        get() =
            (headers.map { it.version } + participants.map { it.version } + players.map { it.version } + projections.map { it.version })
                .toSet()

    /** Alle Matches, auf die sich Events oder Match-Metadaten dieser Auswahl beziehen. */
    val matchIds: Set<String>
        get() =
            selected.values.map { it.matchId }.toSet() + selectedHeaders.map { it.value.id } +
                selectedParticipants.map { it.value.matchId } + selectedProjections.map { it.value.matchId }

    internal fun selectedEvent(id: String): StoredEvent? = selected[id]

    internal val isEmpty: Boolean
        get() =
            selected.isEmpty() && selectedHeaders.isEmpty() && selectedParticipants.isEmpty() && selectedPlayers.isEmpty() &&
                selectedProjections.isEmpty()

    /** Nach `restart_required`: Upload des Profils sperren bis zu einer danach begonnenen, beendeten Pull-Runde. */
    fun deferUntilPull() = store.deferUntilPull(this)
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
    private val replicas: ReplicaLifecycleStore,
    private val pulls: CloudPullStore,
    private val settings: SettingsRepository,
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
                val session = CloudUploadSession(profileId, advanceGeneration())
                // Jede Anmeldung bindet neu (K4-U: etwa nach der Freigabe dieses Handys).
                cloud.resetDeviceRejections()
                cloud.resolvePlayerMetadataSources()
                cloud.resolveOwnedMatchMetadataSources(profileId)
                session
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
            cloud.resolvePlayerMetadataSources()
            cloud.resolveOwnedMatchMetadataSources(session.profileId)
        }
    }

    /**
     * Begrenzte, deterministisch sortierte Auswahl; fremde und ungebundene Quellgeräte bleiben ausgeschlossen.
     * Blockierte Matches (kein gültiger G/E-Beleg oder Neustart nach Pull offen) werden vor dem Limit übersprungen.
     */
    fun capture(
        session: CloudUploadSession,
        limit: Int,
    ): CloudUploadSnapshot {
        requireOutsideTransaction()
        require(limit > 0)
        return database.transactionWithResult {
            requireActive(session)
            val proof = LifecycleProof(session.profileId)
            if (proof.blocksAll) {
                cloud.resolveMetadataSources()
                return@transactionWithResult CloudUploadSnapshot(
                    this@CloudUploadStore,
                    session,
                    emptyList(),
                    inventory = readInventory(session),
                    context = proof.context,
                ).also { requireActive(session) }
            }
            val events = mutableListOf<StoredEvent>()
            var afterMatch: String? = null
            while (events.size < limit) {
                val page =
                    queries
                        .pendingForProfile(
                            session.profileId,
                            afterMatch,
                            (limit - events.size).toLong(),
                        ) { id, match, device, seq, lamport, at, parent, type, payload, confirmed ->
                            StoredEvent(id, match, device, seq, lamport, payload, at, parent, type, confirmed == 1L)
                        }.executeAsList()
                val blocked = page.firstOrNull { !proof.admits(it.matchId) }?.matchId
                // shortcut: verwirft je blockiertem Match den Seitenrest (bis B × limit Zeilen); Keyset (match, lamport, device, id)
                // einführen, sobald D1 Access-Zeilen schreibt und viele Matches blockiert sein können (Review r2 P3-2).
                events += page.takeWhile { it.matchId != blocked }
                afterMatch = blocked ?: break
            }
            cloud.resolveMetadataSources()
            val headers = mutableListOf<CloudRevisioned<CloudMatchHeader>>()
            val participants = mutableListOf<CloudRevisioned<CloudParticipants>>()
            val players = mutableListOf<CloudRevisioned<CloudPlayer>>()
            val projections = mutableListOf<CloudRevisioned<CloudProjection>>()
            val selectedIds = events.mapTo(mutableSetOf()) { it.id }
            pendingMetadata(session.profileId, selectedIds, limit, proof).forEach { row ->
                val version = row.version()
                when (version.kind) {
                    CloudMetadataKind.HEADER -> {
                        cloud.uploadHeader(version.id)?.let { headers += CloudRevisioned(version, it) }
                    }

                    CloudMetadataKind.PARTICIPANTS -> {
                        cloud.uploadParticipants(version.id, session.profileId)?.let {
                            participants +=
                                CloudRevisioned(version, it)
                        }
                    }

                    CloudMetadataKind.PLAYER -> {
                        cloud.uploadPlayer(version.id)?.let { players += CloudRevisioned(version, it) }
                    }

                    CloudMetadataKind.PROJECTION -> {
                        cloud.uploadProjection(version.id)?.let { projections += CloudRevisioned(version, it) }
                    }
                }
            }
            val complete =
                projections
                    .map { it.value.matchId }
                    .filter { id ->
                        queries.outstandingForMatch(id).executeAsList().all { it in selectedIds }
                    }.toSet()
            val matchIds =
                (
                    events.map { it.matchId } + headers.map { it.value.id } + participants.map { it.value.matchId } +
                        projections.map { it.value.matchId }
                ).toSet()
            CloudUploadSnapshot(
                this@CloudUploadStore,
                session,
                events,
                headers,
                participants,
                players,
                projections,
                complete,
                readInventory(session),
                proof.lifecycle(matchIds),
                proof.context,
            ).also { requireActive(session) }
        }
    }

    /** Keyset-Seiten der Metadatenauswahl; Zeilen blockierter Matches zählen nicht zum Limit. */
    private fun pendingMetadata(
        profileId: String,
        selectedIds: Set<String>,
        limit: Int,
        proof: LifecycleProof,
    ): List<Cloud_metadata> {
        val rows = mutableListOf<Cloud_metadata>()
        var after: Cloud_metadata? = null
        while (rows.size < limit) {
            val requested = limit - rows.size
            val page =
                database.cloudMetadataQueries
                    .pendingForProfile(profileId, selectedIds, after?.let(::rank), after?.entity_id.orEmpty(), requested.toLong())
                    .executeAsList()
            rows += page.filter { it.kind == CloudMetadataKind.PLAYER.name || proof.admits(it.entity_id) }
            if (page.size < requested) break
            after = page.last()
        }
        return rows
    }

    /** Dieselbe Reihenfolge wie `CloudMetadata.pendingForProfile`. */
    private fun rank(row: Cloud_metadata): Long =
        when (row.kind) {
            "PLAYER" -> 0
            "HEADER" -> 1
            "PARTICIPANTS" -> 2
            else -> 3
        }

    /**
     * Upload-Beleg nach Vertrag Welle 2 (U4, korrigiert nach Review r1). Data wird ausschließlich über
     * `CloudPullStore.progress`/`snapshot`, `ReplicaLifecycleStore.access` und `redactionProgress` gelesen.
     */
    private inner class LifecycleProof(
        private val profileId: String,
        snapshotContext: ReplicaContext? = null,
    ) {
        private val progress = pulls.progress(profileId)

        // Eine neue Runde ohne targetEpoch lässt das zuletzt abgeschlossene G gültig; der Server prüft G vor jedem Write.
        val generation: Long? = progress.visibilityEpoch

        // Ohne Owner-Scope existiert kein Replikatzugriff; eine Prüfung verwendet den Kontext ihres Snapshots.
        val context: ReplicaContext? = snapshotContext ?: runCatching { replicas.captureContext() }.getOrNull()

        // G stammt aus dem Upload-Profil; E nur aus demselben Konto lesen.
        private val account = context?.takeIf { it.accountKey == profileId }

        /** Kein Versand: neues G' noch nicht abgeschlossen, E aus fremdem Konto oder Neustart nach 40001 offen. */
        val blocksAll = progress.targetEpoch != null || (generation != null && account == null) || restartPending(profileId, progress)
        private val epochs = mutableMapOf<String, Long>()
        private val blocked = mutableSetOf<String>()
        private val checked = mutableSetOf<String>()

        fun admits(matchId: String): Boolean {
            if (checked.add(matchId)) evaluate(matchId)
            return matchId !in blocked
        }

        private fun evaluate(matchId: String) {
            val access = account?.let { replicas.access(it, matchId) }
            val applied = access?.redactionEpoch ?: 0
            // Katalog-E über dem angewandten E: D1 hat den Anstieg gesehen, der Transfer fehlt noch (auch ohne offenen Transfer).
            val catalog = pulls.snapshot(profileId, CloudPullEntityKind.MATCH, matchId)?.redactionObserved ?: 0
            val g = generation
            if (blocksAll || catalog > applied || (g == null && applied > 0)) {
                // Sonst sicher 40001: ohne G bei bekanntem E > 0, mit G beim alten E; beides sperrte das ganze Profil.
                blocked += matchId
                return
            }
            if (g == null) return
            val ctx = checkNotNull(account)
            // Der Server verlangt einen Eintrag für jedes ihm bekannte Match; ohne Access-Zeile ist E = 0.
            if (access?.generation == null) {
                epochs[matchId] = 0
                return
            }
            val openTransfer = runCatching { replicas.redactionProgress(ctx, matchId)?.completed == false }.getOrDefault(true)
            if (access.access == ReplicaAccessLevel.MEMBER && access.generation == g && !openTransfer &&
                access.redactionEpoch <= Int.MAX_VALUE
            ) {
                epochs[matchId] = access.redactionEpoch
            } else {
                blocked += matchId
            }
        }

        fun lifecycle(matchIds: Set<String>): CloudUploadLifecycle? {
            if (blocksAll) return null
            matchIds.forEach(::admits)
            return generation?.let { g -> CloudUploadLifecycle(g, epochs.filterKeys { it in matchIds }) }
        }

        fun matches(snapshot: CloudUploadSnapshot): Boolean {
            if (blocksAll) return snapshot.isEmpty
            val ids = snapshot.matchIds
            return ids.all(::admits) && lifecycle(ids) == snapshot.lifecycle
        }
    }

    private fun restartKey(profileId: String) = "cloud.upload.restart.$profileId"

    /**
     * Profilweite Neustartsperre: gespeichert ist die Pull-Runde beim 40001. Integratorentscheid (Integrationspunkt B):
     * Frei ist, sobald eine danach begonnene Runde ihren KATALOG abgeschlossen hat (`roundCatalogReady`), nicht erst
     * nach dem Rundenabschluss. D1 braucht für eine ganze Runde bei 205 Matches 61 Läufe, U4 aber nur frisches G und
     * frische Access-Zeilen; ein im Katalog gesehener E-Anstieg sperrt danach nur sein Match ([LifecycleProof]).
     * Ein unlesbarer Wert sperrt bis zur nächsten Runde und wird dafür mit der aktuellen Runde überschrieben.
     * Die Freigabe löscht den Wert: ein Katalogneustart derselben Runde (D1-eigenes 40001) sperrt nicht erneut.
     */
    private fun restartPending(
        profileId: String,
        progress: CloudPullProgress,
    ): Boolean {
        val stored = settings.get(restartKey(profileId)) ?: return false
        val round = stored.toLongOrNull() ?: progress.checkRound.also { settings.putLong(restartKey(profileId), it) }
        // beginRun startet nur ohne aktive Runde; checkRound >= round + 2 beweist also, dass round + 1 beendet ist.
        val pending =
            progress.checkRound <= round ||
                (progress.checkRound == round + 1 && progress.roundActive && !progress.roundCatalogReady)
        if (!pending) settings.remove(restartKey(profileId))
        return pending
    }

    internal fun deferUntilPull(snapshot: CloudUploadSnapshot) {
        requireOutsideTransaction()
        database.transaction {
            requireActive(snapshot.session)
            settings.putLong(restartKey(snapshot.profileId), pulls.progress(snapshot.profileId).checkRound)
        }
    }

    /** Ausschließlich lesende skalare Aggregate; Herkunft und Revisionen entstehen an Schreib-/Bindungsstellen. */
    fun inventory(session: CloudUploadSession): CloudUploadInventory {
        requireOutsideTransaction()
        return database.transactionWithResult {
            requireActive(session)
            readInventory(session).also { requireActive(session) }
        }
    }

    private fun readInventory(session: CloudUploadSession): CloudUploadInventory =
        CloudUploadInventory(
            queries.pendingCountForProfile(session.profileId).executeAsOne(),
            queries.rejectedCountForProfile(session.profileId).executeAsOne(),
            database.cloudMetadataQueries.pendingCountForProfile(session.profileId).executeAsOne(),
        )

    /**
     * Vor dem Netzwerkversand und vor dem Ack erneut prüfen; B2b muss zusätzlich die verwendeten Credentials binden.
     * Abweichender Kontext, G/E oder ein inzwischen blockiertes Match: [CloudUploadLifecycleChangedException].
     */
    fun requireCurrent(snapshot: CloudUploadSnapshot) {
        requireActive(snapshot.session)
        val context = snapshot.context
        if (context != null && runCatching { replicas.validate(context) }.isFailure) throw CloudUploadLifecycleChangedException()
        if (!LifecycleProof(snapshot.profileId, context).matches(snapshot)) throw CloudUploadLifecycleChangedException()
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
        metadata: Set<CloudMetadataRevision> = emptySet(),
    ) {
        require(uploaded.intersect(rejected.keys).isEmpty())
        require(rejected.values.all { it.isNotBlank() })
        val ids = uploaded + rejected.keys
        database.transaction {
            requireCurrent(snapshot)
            require(metadata.all { it in snapshot.metadataRevisions }) { "Antwort enthält eine nicht ausgewählte Metadatenrevision" }
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
            metadata.forEach { version ->
                database.cloudMetadataQueries.acknowledge(version.revision, version.kind.name, version.id)
            }
        }
    }
}
