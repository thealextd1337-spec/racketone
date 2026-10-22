package eu.advantly.match.session

import eu.advantly.core.model.MatchId
import eu.advantly.core.scoring.MatchCreated
import eu.advantly.core.scoring.ScoreEvent
import eu.advantly.data.LocalData
import eu.advantly.data.ReplicaAccessLevel
import eu.advantly.sync.protocol.CloudMatchStamp
import eu.advantly.sync.protocol.LocalMatchStamp
import eu.advantly.sync.protocol.MatchDataStamp
import eu.advantly.sync.protocol.MatchHeader
import eu.advantly.sync.protocol.SyncCodec

/**
 * Herkunftsbelege von Protokoll 4 (Vertrag W3): beim Senden aus dem eigenen Stand erzeugt, beim Empfang **vor** jeder
 * Datenmutation und jedem Ack geprüft. Ein Cloud-Match (gesehen oder mit Access-Zeile) wird nie über einen
 * [LocalMatchStamp] legitimiert; ein [CloudMatchStamp] gilt nur bei genau dem lokalen MEMBER-Stand (G, E).
 * Ohne Konto-Kontext (P1) sind Belege nur Pflichtfeld und werden nicht gegen Rechte geprüft.
 */
internal class MatchStamps(
    private val data: LocalData,
    private val genesisOf: (MatchId) -> ScoreEvent?,
) {
    fun of(matchId: MatchId): MatchDataStamp? {
        val scope = data.accountIdentities.scope ?: return null
        if (data.accountIdentities.managed) {
            val access = data.replicas.access(data.replicas.captureContext(), matchId.value)
            if (access != null) {
                val generation = access.generation ?: return null
                return CloudMatchStamp(generation, access.redactionEpoch).takeIf { access.access == ReplicaAccessLevel.MEMBER }
            }
            if (data.replicas.isCloudSeen(matchId.value)) return null
        }
        val genesis = genesisOf(matchId) ?: deletedStamp(matchId)?.let { return it } ?: return null
        return LocalMatchStamp(genesis.deviceId, genesis.id, scope)
    }

    /**
     * [batch]: mitgelieferte Events (Genesis darf darin stehen). [requireGenesis]: Events und Köpfe brauchen eine
     * gespeicherte oder mitgelieferte Genesis; Summary und Löschmarkierung kommen ohne aus.
     */
    fun accepts(
        matchId: MatchId,
        stamp: MatchDataStamp?,
        batch: List<ScoreEvent> = emptyList(),
        requireGenesis: Boolean = false,
    ): Boolean {
        stamp ?: return false
        if (!data.accountIdentities.managed) return true
        val access = data.replicas.access(data.replicas.captureContext(), matchId.value)
        return when (stamp) {
            is CloudMatchStamp -> {
                access?.access == ReplicaAccessLevel.MEMBER && access.generation == stamp.generation &&
                    access.redactionEpoch == stamp.redactionEpoch
            }

            is LocalMatchStamp -> {
                val genesis = genesisOf(matchId) ?: batch.firstOrNull { it.payload is MatchCreated }
                access == null && !data.replicas.isCloudSeen(matchId.value) &&
                    stamp.scope == data.accountIdentities.scope &&
                    stamp.sourceId.value in data.accountIdentities.currentSources() &&
                    (genesis?.let { it.id == stamp.genesisId && it.deviceId == stamp.sourceId } ?: !requireGenesis)
            }
        }
    }

    /**
     * Kopf nur vom Genesis-Besitzer: Beleg gültig und, sobald die Genesis gespeichert ist, Format, Besetzung und Metadaten
     * wie dort. Vor der Genesis genügt der Beleg einer eigenen Quelle (Data speichert neue Matches nur mit Kopf vorab).
     */
    fun acceptsHeader(
        header: MatchHeader,
        stamp: MatchDataStamp?,
    ): Boolean {
        if (!data.accountIdentities.managed) return stamp != null
        if (!accepts(header.matchId, stamp)) return false
        val created = genesisOf(header.matchId)?.payload as? MatchCreated ?: return true
        return created.config == header.format && created.lineup == header.lineup && created.meta == header.meta
    }

    /** Beleg zum Löschzeitpunkt merken: Danach fehlt die Genesis, die Löschmarkierung braucht ihn trotzdem. */
    fun rememberForDeletion(matchId: MatchId) {
        val stamp = of(matchId) ?: return
        data.settings.put(DELETED_STAMP_PREFIX + matchId.value, SyncCodec.json.encodeToString(MatchDataStamp.serializer(), stamp))
    }

    private fun deletedStamp(matchId: MatchId): MatchDataStamp? =
        data.settings.get(DELETED_STAMP_PREFIX + matchId.value)?.let {
            runCatching { SyncCodec.json.decodeFromString(MatchDataStamp.serializer(), it) }.getOrNull()
        }

    private companion object {
        const val DELETED_STAMP_PREFIX = "session.deletedStamp."
    }
}
