package eu.advantly.match.session

import eu.advantly.core.model.MatchId
import eu.advantly.data.SettingsRepository
import eu.advantly.sync.protocol.Fork
import eu.advantly.sync.protocol.SyncCodec
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * Erkannte Gabelungen je Match, gerätelokal in den Settings gespeichert (überlebt Neustarts).
 * Die Erkennung passiert beim Empfang ([eu.advantly.sync.protocol.Forks.detect]) und ist deshalb nicht aus dem Log
 * allein rekonstruierbar. „Version i behalten“ wirkt über Undo-Events auf allen Geräten; „zusammenführen“ ([merged])
 * ist nur eine lokale Quittung.
 */
@Serializable
internal data class ForkRecord(
    val fork: Fork,
    val merged: Boolean = false,
)

internal class ForkLedger(
    private val settings: SettingsRepository,
) {
    private val serializer = ListSerializer(ForkRecord.serializer())

    /** Lese-Cache je Match (der Zustand wird bei jeder Änderung neu gebaut). */
    private val cache = HashMap<MatchId, List<ForkRecord>>()

    fun records(matchId: MatchId): List<ForkRecord> = cache.getOrPut(matchId) { load(matchId) }

    private fun load(matchId: MatchId): List<ForkRecord> {
        val json = settings.get(key(matchId)) ?: return emptyList()
        return try {
            SyncCodec.json.decodeFromString(serializer, json)
        } catch (e: IllegalArgumentException) {
            emptyList()
        }
    }

    /** Fügt neue Gabelungen hinzu (bekannte IDs werden ignoriert). */
    fun add(
        matchId: MatchId,
        forks: List<Fork>,
    ) {
        if (forks.isEmpty()) return
        val existing = records(matchId)
        val known = existing.mapTo(HashSet()) { it.fork.id }
        val added = forks.filter { it.id !in known }.map { ForkRecord(it) }
        if (added.isNotEmpty()) save(matchId, existing + added)
    }

    fun markMerged(
        matchId: MatchId,
        forkId: String,
    ) {
        save(matchId, records(matchId).map { if (it.fork.id == forkId) it.copy(merged = true) else it })
    }

    private fun save(
        matchId: MatchId,
        records: List<ForkRecord>,
    ) {
        settings.put(key(matchId), SyncCodec.json.encodeToString(serializer, records))
        cache[matchId] = records
    }

    private fun key(matchId: MatchId) = "session.forks.${matchId.value}"
}
