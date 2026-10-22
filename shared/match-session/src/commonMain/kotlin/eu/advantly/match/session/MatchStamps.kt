package eu.advantly.match.session

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.EventId
import eu.advantly.core.model.MatchId
import eu.advantly.core.scoring.MatchCreated
import eu.advantly.core.scoring.ScoreEvent
import eu.advantly.data.LocalData
import eu.advantly.data.ReplicaAccessLevel
import eu.advantly.sync.protocol.CloudMatchStamp
import eu.advantly.sync.protocol.Deleted
import eu.advantly.sync.protocol.Events
import eu.advantly.sync.protocol.Hello
import eu.advantly.sync.protocol.LifecycleSummary
import eu.advantly.sync.protocol.LocalMatchStamp
import eu.advantly.sync.protocol.MatchDataStamp
import eu.advantly.sync.protocol.MatchHeader
import eu.advantly.sync.protocol.MatchMetaMessage
import eu.advantly.sync.protocol.ReplicaControlMessage
import eu.advantly.sync.protocol.Summary
import eu.advantly.sync.protocol.SyncCodec
import eu.advantly.sync.protocol.SyncMessage

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

    /**
     * Senden über Protokoll 4: jede Matchangabe mit eigenem Beleg; ohne Beleg wird sie nicht gesendet, Events ohne Beleg ganz
     * nicht (`null`, [onBlocked]: Rückstand bleibt sichtbar blockiert). [lifecycle]: eigener Stand für `Summary.lifecycle`.
     */
    fun stamped(
        message: SyncMessage,
        lifecycle: (Collection<String>) -> Map<String, LifecycleSummary>,
        onBlocked: (MatchId) -> Unit,
    ): SyncMessage? =
        when (message) {
            is Events -> {
                val stamp = if (message.events.isEmpty()) null else of(message.matchId)
                if (message.events.isNotEmpty() && stamp == null) onBlocked(message.matchId)
                message.takeIf { it.events.isEmpty() || stamp != null }?.copy(v = 4, stamp = stamp)
            }

            is Summary -> {
                val own =
                    message.matches.keys
                        .mapNotNull { key -> of(MatchId(key))?.let { key to it } }
                        .toMap()
                val states = if (data.accountIdentities.managed) message.lifecycle + lifecycle(own.keys) else message.lifecycle
                message.copy(
                    v = 4,
                    matches = message.matches.filterKeys(own::containsKey),
                    stamps = own,
                    lifecycle = states.entries.take(MAX_LIFECYCLE).associate { it.toPair() },
                )
            }

            is MatchMetaMessage -> {
                val stamp = message.match?.let { of(it.matchId) }
                message.copy(v = 4, match = message.match?.takeIf { stamp != null }, stamp = stamp)
            }

            is Deleted -> {
                val own = message.matches.mapNotNull { m -> of(m.matchId)?.let { m.matchId.value to it } }.toMap()
                message.copy(v = 4, matches = message.matches.filter { it.matchId.value in own }, stamps = own)
            }

            is Hello, is ReplicaControlMessage -> {
                message
            }
        }

    /**
     * Empfang Protokoll 4: Belege vor jeder Mutation und jedem Ack prüfen; ungültige Matchangaben fallen weg, Events mit
     * ungültigem Beleg ganz (`null`: kein Speichern, keine Bestätigung).
     */
    fun verified(message: SyncMessage): SyncMessage? =
        when (message) {
            is Events -> {
                message.takeIf {
                    it.events.isEmpty() || accepts(it.matchId, it.stamp, it.events, requireGenesis = true)
                }
            }

            is Summary -> {
                message.copy(matches = message.matches.filterKeys { accepts(MatchId(it), message.stamps[it]) })
            }

            is MatchMetaMessage -> {
                val header = message.match
                if (header == null || acceptsHeader(header, message.stamp)) message else message.copy(match = null, stamp = null)
            }

            is Deleted -> {
                message.copy(matches = message.matches.filter { accepts(it.matchId, message.stamps[it.matchId.value]) })
            }

            is Hello, is ReplicaControlMessage -> {
                message
            }
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
        const val MAX_LIFECYCLE = 200
    }
}

/** Größter Beleg für Byte-Proben (unabhängig vom tatsächlichen, damit die Aufteilung stabil bleibt). */
internal val WORST_STAMP = LocalMatchStamp(DeviceId("d".repeat(128)), EventId("g".repeat(128)), "s".repeat(128))
