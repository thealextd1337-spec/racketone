package eu.advantly.data

import app.cash.sqldelight.db.SqlDriver
import eu.advantly.data.db.AdvantlyDatabase

/** [EventStore] auf SQLDelight. Der [driver] muss das Schema von [AdvantlyDatabase] bereits angelegt haben. */
class SqlDelightEventStore(
    driver: SqlDriver,
) : EventStore {
    private val queries = AdvantlyDatabase(driver).matchEventQueries

    override fun append(event: StoredEvent): AppendResult =
        queries.transactionWithResult {
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
                        payload = event.payload,
                    )
                    AppendResult.INSERTED
                }
            }
        }

    override fun eventsFor(matchId: String): List<StoredEvent> =
        queries
            .eventsForMatch(matchId) { id, match, device, seq, lamport, payload ->
                StoredEvent(id, match, device, seq, lamport, payload)
            }.executeAsList()

    override fun count(matchId: String): Long = queries.countForMatch(matchId).executeAsOne()
}
