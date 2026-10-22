package eu.advantly.match.session

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.EventId
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.PlayerId
import eu.advantly.data.LocalData
import eu.advantly.data.RedactionPage
import eu.advantly.data.RedactionTransfer
import eu.advantly.data.ReplicaAccessLevel
import eu.advantly.data.ReplicaContext
import eu.advantly.data.ReplicaDelivery
import eu.advantly.data.ReplicaRedactionEvent
import eu.advantly.data.StoredEvent
import eu.advantly.sync.protocol.AccessAck
import eu.advantly.sync.protocol.AccessState
import eu.advantly.sync.protocol.AntiEntropy
import eu.advantly.sync.protocol.IdentityEnvelope
import eu.advantly.sync.protocol.LifecycleSummary
import eu.advantly.sync.protocol.MatchAccessLevel
import eu.advantly.sync.protocol.OwnerContext
import eu.advantly.sync.protocol.RedactedChunk
import eu.advantly.sync.protocol.RedactedChunkAck
import eu.advantly.sync.protocol.RedactionAck
import eu.advantly.sync.protocol.RedactionEvent
import eu.advantly.sync.protocol.Summary
import eu.advantly.sync.protocol.SyncCodec
import eu.advantly.sync.protocol.SyncMessage
import eu.advantly.sync.protocol.RedactionCursor as WireCursor

/**
 * Handy: dauerhafte Access- und Redaktionszustellung an gekoppelte Uhren (Vertrag W3, E3/E7/E8). Autorität kommt nur
 * aus den eigenen Data-Zeilen; Acks der Uhr bestätigen Zustellung, nie neue Rechte (die Data-Acks verlangen exakt das
 * eingereihte G, Stufe und E). Seitenstand nur im RAM: nach einem Neustart beginnt jeder Transfer wieder bei Wire-Index 0,
 * die Uhr verarbeitet Wiederholungen idempotent. Alle Aufrufe auf dem Scheduler-Thread, [fence] je Aufruf vom Aufrufer.
 */
internal class LifecycleDelivery(
    private val data: LocalData,
    private val send: (peer: String, message: SyncMessage) -> Unit,
) {
    /** Je Uhr-Gerät und Wire-Transfer: erster unbestätigter Wire-Index. */
    private val nextUnacked = HashMap<Pair<String, String>, Long>()

    /** Wire-Seiten je Transfer, deterministisch aus den gespeicherten Data-Seiten. */
    private val chunks = HashMap<String, List<RedactedChunk>>()

    /** Zuletzt zugestellter offener Stand je Uhr: Data-Signale lösen nur bei Änderung eine neue Runde aus. */
    private val delivered = HashMap<String, List<ReplicaDelivery>>()

    /** Unbestätigte Zustellungen an [devices] (Summe), z. B. für `SyncStatus.lifecyclePendingToPeers`. */
    fun pendingCount(devices: Collection<String>): Int {
        val ctx = data.replicas.captureContext()
        return devices.sumOf { pending(ctx, it).size }
    }

    /** E7: die Uhr aus dem bestätigten Hello registrieren; bekannte Zugriffe werden für sie eingereiht. */
    fun register(
        device: String,
        fence: () -> Unit,
    ) = data.replicas.registerPeer(data.replicas.captureContext(), device, fence)

    /** Offene Zustellungen an [peer] (Uhr [device]): gemeldeter Stand, Access, Redaktionsseiten ab dem ersten unbestätigten. */
    fun deliver(
        peer: String,
        device: String,
        fence: () -> Unit,
        onlyIfChanged: Boolean = false,
    ) {
        val ctx = data.replicas.captureContext()
        val open = pending(ctx, device)
        if (onlyIfChanged && delivered[device] == open) return
        delivered[device] = open
        if (open.isEmpty()) return
        // E9: gemeldeter Stand vor dem Access, damit die Uhr ready ohne eine weitere Runde setzen kann
        open.chunked(MAX_LIFECYCLE).forEach { page ->
            send(peer, Summary(emptyMap(), reply = true, v = 4, lifecycle = page.associate { it.matchId to it.lifecycle() }))
        }
        for (d in open) {
            val level = d.access.wire() ?: continue
            if (!d.accessAcknowledged) send(peer, AccessState(MatchId(d.matchId), d.generation, level))
            if (!d.redactionAcknowledged && d.redactionEpoch > 0 && d.access == ReplicaAccessLevel.MEMBER) {
                sendChunks(peer, device, ctx, d, fence)
            }
        }
    }

    /** Kumulativer Stand der Uhr aus ihrem `Summary.lifecycle` (E8): bestätigt genau eingereihte Zustellungen. */
    fun onLifecycle(
        device: String,
        reported: Map<String, LifecycleSummary>,
        fence: () -> Unit,
    ) {
        if (reported.isEmpty()) return
        val ctx = data.replicas.captureContext()
        for (d in pending(ctx, device)) {
            val state = reported[d.matchId] ?: continue
            if (state.generation != d.generation || state.access != d.access.wire()) continue
            if (!d.accessAcknowledged) data.replicas.ackAccess(ctx, device, d.matchId, d.generation, d.access, fence)
            if (!d.redactionAcknowledged && state.redactionApplied >= d.redactionEpoch) {
                data.replicas.ackRedaction(ctx, device, d.matchId, d.generation, d.redactionEpoch, fence)
            }
        }
    }

    /** E8: bei E = 0 (oder ohne Mitgliedschaft, also ohne Transfer) bestätigt der Access-Ack auch die Redaktion. */
    fun onAccessAck(
        device: String,
        ack: AccessAck,
        fence: () -> Unit,
    ) {
        val ctx = data.replicas.captureContext()
        val d = pending(ctx, device).firstOrNull { it.matchId == ack.matchId.value } ?: return
        if (d.generation != ack.generation || d.access.wire() != ack.access) return
        data.replicas.ackAccess(ctx, device, d.matchId, d.generation, d.access, fence)
        if (d.redactionEpoch == 0L || d.access != ReplicaAccessLevel.MEMBER) {
            data.replicas.ackRedaction(ctx, device, d.matchId, d.generation, d.redactionEpoch, fence)
        }
    }

    /** Transportseite angekommen: Wiederanlauf erst ab der nächsten (die Uhr nimmt Seiten nur lückenlos an). */
    fun onChunkAck(
        device: String,
        ack: RedactedChunkAck,
    ) {
        val key = device to ack.transferId
        nextUnacked[key] = maxOf(nextUnacked[key] ?: 0, ack.index + 1)
    }

    fun onRedactionAck(
        device: String,
        ack: RedactionAck,
        fence: () -> Unit,
    ) {
        val ctx = data.replicas.captureContext()
        val d = pending(ctx, device).firstOrNull { it.matchId == ack.matchId.value } ?: return
        if (d.generation != ack.generation || d.redactionEpoch != ack.epoch || wireTransferId(d) != ack.transferId) return
        data.replicas.ackRedaction(ctx, device, d.matchId, d.generation, d.redactionEpoch, fence)
    }

    private fun sendChunks(
        peer: String,
        device: String,
        ctx: ReplicaContext,
        d: ReplicaDelivery,
        fence: () -> Unit,
    ) {
        val progress = data.replicas.redactionProgress(ctx, d.matchId) ?: return
        val transfer = progress.transfer
        if (!progress.completed || transfer.generation != d.generation || transfer.epoch != d.redactionEpoch) return
        val id = wireTransferId(d)
        val all = chunks.getOrPut(id) { exportChunks(ctx, transfer, id, fence) }
        if (all.isEmpty()) return
        // Alles bestätigt, aber kein Abschluss-Ack: die letzte Seite löst ihn idempotent erneut aus
        val from = minOf(nextUnacked[device to id] ?: 0, all.lastIndex.toLong()).toInt()
        all.drop(from).forEach { send(peer, it) }
    }

    private fun exportChunks(
        ctx: ReplicaContext,
        transfer: RedactionTransfer,
        id: String,
        fence: () -> Unit,
    ): List<RedactedChunk> {
        val result = mutableListOf<RedactedChunk>()
        var index = 0L
        while (result.lastOrNull()?.let { it.idsDone && it.eventsDone } != true) {
            val page = data.replicas.exportRedactionPage(ctx, transfer.id, index++, fence) ?: break
            result += RedactionChunks.split(page, id, transfer, result.size.toLong())
        }
        return result
    }

    private fun pending(
        ctx: ReplicaContext,
        device: String,
    ): List<ReplicaDelivery> {
        val result = mutableListOf<ReplicaDelivery>()
        var after: String? = null
        do {
            val page = data.replicas.pendingDeliveries(ctx, device, after, MAX_LIFECYCLE)
            result += page.deliveries
            after = page.nextId
        } while (page.hasMore)
        return result
    }

    private companion object {
        const val MAX_LIFECYCLE = 200
    }
}

/** Größter zulässiger Envelope (Pairing begrenzt die Felder) für Byte-Proben, die nicht vom aktuellen abhängen dürfen. */
internal val WORST_ENVELOPE =
    IdentityEnvelope(OwnerContext("a".repeat(64), Long.MAX_VALUE, "s".repeat(128)), "n".repeat(64), "r".repeat(64))

/** Wire-Transfer-ID ohne Kontokennung: Account-IDs gehen nicht auf den Draht. */
private fun wireTransferId(d: ReplicaDelivery): String = "${d.matchId}:${d.generation}:${d.redactionEpoch}"

private fun ReplicaDelivery.lifecycle(): LifecycleSummary =
    LifecycleSummary(generation, access.wire() ?: MatchAccessLevel.ABSENT, redactionEpoch)

internal fun ReplicaAccessLevel.wire(): MatchAccessLevel? = MatchAccessLevel.entries.firstOrNull { it.name == name }

/**
 * Deterministischer Aufteiler Data-Seite → Wire-Seiten (Vertrag W3 §6): gleiche gespeicherte Seite ergibt gleiche
 * Seiten, denn die Uhr speichert jede als eigene Data-Seite und verlangt bei Wiederholung identisches JSON. Gemessen
 * wird mit dem größtmöglichen Envelope und Index, nie mit dem aktuellen (sonst verschöbe ein neues Pairing die Grenzen):
 * Wire-Paket samt Envelope und die daraus entstehende Data-Seite der Uhr bleiben je ≤ 64 KiB.
 */
internal object RedactionChunks {
    private const val MAX_ITEMS = 200
    private const val DATA_PAGE_BYTES = 64 * 1024 // Grenze von ReplicaLifecycleStore.stageRedaction (ohne Hülle)

    fun split(
        page: RedactionPage,
        transferId: String,
        transfer: RedactionTransfer,
        firstIndex: Long,
    ): List<RedactedChunk> {
        val items: List<Any> = page.playerIds.map(::PlayerId) + page.events.map { it.wire() }

        fun chunk(
            start: Int,
            group: List<Any>,
            index: Long,
        ): RedactedChunk {
            val before = items.subList(0, start)
            val rest = items.subList(start + group.size, items.size)
            val from =
                WireCursor(
                    before.filterIsInstance<PlayerId>().lastOrNull() ?: page.from.afterPlayerId?.let(::PlayerId),
                    before.filterIsInstance<RedactionEvent>().lastOrNull()?.serverSeq ?: page.from.afterServerSeq,
                )
            val ids = group.filterIsInstance<PlayerId>()
            val events = group.filterIsInstance<RedactionEvent>()
            return RedactedChunk(
                transferId,
                MatchId(transfer.matchId),
                transfer.generation,
                transfer.epoch,
                transfer.highWatermark,
                index,
                from,
                WireCursor(ids.lastOrNull() ?: from.afterPlayerId, events.lastOrNull()?.serverSeq ?: from.afterServerSeq),
                ids,
                events,
                idsDone = page.idsDone && rest.none { it is PlayerId },
                eventsDone = page.eventsDone && rest.none { it is RedactionEvent },
            )
        }
        if (items.isEmpty()) return listOf(chunk(0, emptyList(), firstIndex))
        var start = 0
        return AntiEntropy
            .batchesBy(items, MAX_ITEMS) { offset, group -> fits(chunk(offset, group, Long.MAX_VALUE)) }
            .mapIndexed { i, group -> chunk(start, group, firstIndex + i).also { start += group.size } }
    }

    private fun fits(chunk: RedactedChunk): Boolean {
        val wire =
            try {
                SyncCodec.encode(chunk, WORST_ENVELOPE)
            } catch (_: IllegalArgumentException) {
                return false
            }
        val stored = SyncCodec.json.encodeToString(RedactionPage.serializer(), chunk.page())
        return wire.isNotEmpty() && stored.encodeToByteArray().size <= DATA_PAGE_BYTES
    }
}

/** Uhr: eine Wire-Seite als eigene Data-Seite (gleicher Index, gleiche Cursor). */
internal fun RedactedChunk.page(): RedactionPage =
    RedactionPage(
        index,
        eu.advantly.data.RedactionCursor(from.afterPlayerId?.value, from.afterServerSeq),
        eu.advantly.data.RedactionCursor(next.afterPlayerId?.value, next.afterServerSeq),
        playerIds.map { it.value },
        events.map {
            ReplicaRedactionEvent(
                StoredEvent(
                    it.id.value,
                    it.matchId.value,
                    it.deviceId.value,
                    it.deviceSeq,
                    it.lamport,
                    it.payload,
                    it.wallClockMs,
                    it.parentId?.value,
                    it.type,
                ),
                it.serverSeq,
            )
        },
        idsDone,
        eventsDone,
    )

private fun ReplicaRedactionEvent.wire(): RedactionEvent =
    RedactionEvent(
        EventId(event.id),
        MatchId(event.matchId),
        DeviceId(event.deviceId),
        event.deviceSeq,
        event.lamport,
        event.wallClockMs,
        event.parentId?.let(::EventId),
        // Data erlaubt für Redaktionsbelege nur "" oder "PlayerAssigned"; der Draht verlangt einen Typ
        event.type.ifEmpty { "PlayerAssigned" },
        event.payload,
        serverSeq,
    )
