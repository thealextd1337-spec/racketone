package eu.advantly.data

/** öffentliche Eventgrenze; Rohzugriffe werden ausschließlich von Data-Transaktionen gehalten. */
internal class ScopedEventStore(
    private val raw: EventStore,
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
                    replicas.requireAppend(event)
                    if (context != null && !replicas.readable(event.matchId)) localCreations[event.matchId] = event.deviceId
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
    ): List<StoredEvent> = if (replicas.readable(matchId)) raw.eventsInRange(matchId, deviceId, fromSeq, toSeq) else emptyList()

    override fun sequences(matchId: String): List<StoredSequence> = if (replicas.readable(matchId)) raw.sequences(matchId) else emptyList()

    override fun maxLamport(matchId: String): Long = if (replicas.readable(matchId)) raw.maxLamport(matchId) else 0

    override fun maxDeviceSeq(
        matchId: String,
        deviceId: String,
    ): Long = if (replicas.readable(matchId)) raw.maxDeviceSeq(matchId, deviceId) else 0

    override fun count(matchId: String): Long = if (replicas.readable(matchId)) raw.count(matchId) else 0

    override fun markConfirmed(
        matchId: String,
        sequences: Collection<StoredSequence>,
    ) {
        replicas.requireReadable(matchId)
        raw.markConfirmed(matchId, sequences)
    }

    override fun markConfirmedUpTo(
        matchId: String,
        deviceId: String,
        seq: Long,
    ) {
        replicas.requireReadable(matchId)
        raw.markConfirmedUpTo(matchId, deviceId, seq)
    }

    override fun unconfirmedCount(): Long = unconfirmedCountByMatch().values.sum()

    override fun unconfirmedMatches(): List<String> = raw.unconfirmedMatches().filter(replicas::readable)

    override fun unconfirmedCountByMatch(): Map<String, Long> = raw.unconfirmedCountByMatch().filterKeys(replicas::readable)

    override fun unconfirmedEvents(matchId: String): List<StoredEvent> =
        if (replicas.readable(matchId)) raw.unconfirmedEvents(matchId) else emptyList()

    override fun deleteMatch(matchId: String) {
        replicas.requireReadable(matchId)
        raw.deleteMatch(matchId)
    }
}
