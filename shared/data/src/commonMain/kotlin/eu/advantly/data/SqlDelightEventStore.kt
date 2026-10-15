package eu.advantly.data

import app.cash.sqldelight.db.SqlDriver
import eu.advantly.data.db.AdvantlyDatabase
import eu.advantly.data.db.MatchEventQueries

/** [EventStore] auf SQLDelight. Der [driver] muss das Schema von [AdvantlyDatabase] bereits angelegt haben. */
class SqlDelightEventStore internal constructor(
    private val queries: MatchEventQueries,
) : EventStore {
    constructor(driver: SqlDriver) : this(AdvantlyDatabase(driver).matchEventQueries)

    override fun append(event: StoredEvent): AppendResult = queries.transactionWithResult { insert(event) }

    override fun appendAll(events: List<StoredEvent>): List<AppendResult> =
        if (events.isEmpty()) emptyList() else queries.transactionWithResult { events.map { insert(it) } }

    private fun insert(event: StoredEvent): AppendResult =
        when {
            queries.countById(event.id).executeAsOne() > 0 -> {
                AppendResult.DUPLICATE
            }

            queries.countBySequence(event.matchId, event.deviceId, event.deviceSeq).executeAsOne() > 0 -> {
                AppendResult.SEQUENCE_CONFLICT
            }

            else -> {
                queries.insert(
                    id = event.id,
                    match_id = event.matchId,
                    device_id = event.deviceId,
                    device_seq = event.deviceSeq,
                    lamport = event.lamport,
                    wall_clock_ms = event.wallClockMs,
                    parent_id = event.parentId,
                    type = event.type,
                    payload = event.payload,
                )
                AppendResult.INSERTED
            }
        }

    override fun eventsFor(matchId: String): List<StoredEvent> = queries.eventsForMatch(matchId, ::stored).executeAsList()

    override fun eventsInRange(
        matchId: String,
        deviceId: String,
        fromSeq: Long,
        toSeq: Long,
    ): List<StoredEvent> = queries.eventsInRange(matchId, deviceId, fromSeq, toSeq, ::stored).executeAsList()

    override fun sequences(matchId: String): List<StoredSequence> =
        queries.sequencesForMatch(matchId) { device, seq -> StoredSequence(device, seq) }.executeAsList()

    override fun maxLamport(matchId: String): Long = queries.maxLamport(matchId).executeAsOne()

    override fun maxDeviceSeq(
        matchId: String,
        deviceId: String,
    ): Long = queries.maxDeviceSeq(matchId, deviceId).executeAsOne()

    override fun count(matchId: String): Long = queries.countForMatch(matchId).executeAsOne()

    override fun deleteMatch(matchId: String) {
        queries.deleteForMatch(matchId)
    }

    @Suppress("LongParameterList")
    private fun stored(
        id: String,
        match: String,
        device: String,
        seq: Long,
        lamport: Long,
        wallClockMs: Long,
        parentId: String?,
        type: String,
        payload: String,
    ) = StoredEvent(id, match, device, seq, lamport, payload, wallClockMs, parentId, type)
}
