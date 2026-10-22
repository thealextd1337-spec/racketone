package eu.advantly.data

import eu.advantly.data.db.MatchEventQueries

/** öffentliche Eventgrenze; Rohzugriffe werden ausschließlich von Data-Transaktionen gehalten. */
internal class ScopedEventStore(
    private val raw: EventStore,
    private val queries: MatchEventQueries,
    private val replicas: ReplicaLifecycleStore,
    private val transaction: (() -> List<AppendResult>) -> List<AppendResult>,
) : EventStore {
    override fun append(event: StoredEvent): AppendResult = appendAll(listOf(event)).single()

    override fun appendAll(events: List<StoredEvent>): List<AppendResult> =
        transaction {
            val context = if (replicas.activated) replicas.captureContext() else null
            val localCreations = mutableMapOf<String, String>()
            events.forEach { event ->
                val creationDevice = localCreations[event.matchId]
                if (creationDevice == null) {
                    if (replicas.requireAppend(event)) localCreations[event.matchId] = event.deviceId
                } else {
                    check(event.deviceId == creationDevice) { "Lokale Genesis autorisiert keine fremde Eventquelle" }
                }
            }
            val result = raw.appendAll(events.map { event -> replicas.sanitizeLegacyEvent(event) { context?.let(replicas::validate) } })
            context?.let(replicas::validate)
            result
        }

    override fun eventsFor(matchId: String): List<StoredEvent> = if (replicas.readable(matchId)) raw.eventsFor(matchId) else emptyList()

    override fun eventsInRange(
        matchId: String,
        deviceId: String,
        fromSeq: Long,
        toSeq: Long,
    ): List<StoredEvent> =
        if (replicas.readable(matchId) || replicas.ownBacklog(matchId, deviceId, content = true)) {
            raw.eventsInRange(matchId, deviceId, fromSeq, toSeq)
        } else {
            emptyList()
        }

    // Zähler und Bestätigungen folgen der Schreibspur (Teil 2, P1-1): sonst vergibt die Session im Fenster „schreiben ja, lesen
    // nein“ seq 1 und der Punkt geht still als SEQUENCE_CONFLICT verloren. Inhalte bleiben an der Lesespur; nur Rückstand,
    // Sequenzen und Nachlieferung eigener Quellen folgen der Schreibspur (D-1), damit eigene Punkte die Gegenstelle erreichen.
    override fun sequences(matchId: String): List<StoredSequence> {
        if (replicas.readable(matchId)) return raw.sequences(matchId)
        val own = ownSources(matchId, content = true)
        return raw.sequences(matchId).filter { own(it.deviceId) }
    }

    override fun maxLamport(matchId: String): Long = if (replicas.writable(matchId)) raw.maxLamport(matchId) else 0

    override fun maxDeviceSeq(
        matchId: String,
        deviceId: String,
    ): Long = if (replicas.writable(matchId)) raw.maxDeviceSeq(matchId, deviceId) else 0

    override fun count(matchId: String): Long = if (replicas.readable(matchId)) raw.count(matchId) else 0

    override fun markConfirmed(
        matchId: String,
        sequences: Collection<StoredSequence>,
    ) {
        replicas.requireWritable(matchId)
        raw.markConfirmed(matchId, sequences)
    }

    override fun markConfirmedUpTo(
        matchId: String,
        deviceId: String,
        seq: Long,
    ) {
        replicas.requireWritable(matchId)
        raw.markConfirmedUpTo(matchId, deviceId, seq)
    }

    override fun unconfirmedCount(): Long = unconfirmedCountByMatch().values.sum()

    override fun unconfirmedMatches(): List<String> =
        raw.unconfirmedMatches().filter { replicas.readable(it) || ownUnconfirmed(it, content = true).isNotEmpty() }

    // Zähler auch bei ausstehender Redaktion: sie verraten keinen Inhalt, halten aber „alles zugestellt“ ehrlich.
    override fun unconfirmedCountByMatch(): Map<String, Long> =
        raw
            .unconfirmedCountByMatch()
            .mapValues { (matchId, count) ->
                if (replicas.readable(matchId)) count else ownUnconfirmed(matchId, content = false).values.sum()
            }.filterValues { it > 0 }

    override fun unconfirmedEvents(matchId: String): List<StoredEvent> {
        if (replicas.readable(matchId)) return raw.unconfirmedEvents(matchId)
        val own = ownUnconfirmed(matchId, content = true).keys
        return if (own.isEmpty()) emptyList() else raw.unconfirmedEvents(matchId).filter { it.deviceId in own }
    }

    /** D-1: Gerät → eigene Quelle mit offener Schreibspur (je Aufruf zwischengespeichert); fremde Quellen nie. */
    private fun ownSources(
        matchId: String,
        content: Boolean,
    ): (String) -> Boolean {
        val own = mutableMapOf<String, Boolean>()
        return { device -> own.getOrPut(device) { replicas.ownBacklog(matchId, device, content) } }
    }

    /** Rückstand eigener Quellen eines Matches ohne Lesespur (Gerät → Anzahl); fremde Quellen kosten einen Indexblick, nie Payloads. */
    private fun ownUnconfirmed(
        matchId: String,
        content: Boolean,
    ): Map<String, Long> =
        queries
            .devicesForMatch(matchId)
            .executeAsList()
            .filter { replicas.ownBacklog(matchId, it, content) }
            .associateWith { queries.unconfirmedCountForDevice(matchId, it).executeAsOne() }
            .filterValues { it > 0 }

    override fun deleteMatch(matchId: String) {
        replicas.requireReadable(matchId)
        raw.deleteMatch(matchId)
    }
}
