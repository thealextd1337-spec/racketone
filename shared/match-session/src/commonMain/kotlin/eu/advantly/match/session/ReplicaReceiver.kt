package eu.advantly.match.session

import eu.advantly.data.LocalData
import eu.advantly.data.RedactionTransfer
import eu.advantly.data.ReplicaAccessLevel
import eu.advantly.data.ReplicaContext
import eu.advantly.sync.protocol.AccessAck
import eu.advantly.sync.protocol.AccessState
import eu.advantly.sync.protocol.LifecycleSummary
import eu.advantly.sync.protocol.MatchAccessLevel
import eu.advantly.sync.protocol.RedactedChunk
import eu.advantly.sync.protocol.RedactedChunkAck
import eu.advantly.sync.protocol.RedactionAck
import eu.advantly.sync.protocol.SyncMessage

/**
 * Uhr: wendet Access und Redaktion an, die ausschließlich vom bestätigten Handy kommen (Envelope und Pairing-Revision
 * prüft der Aufrufer, hier als [fence] je Mutation). Acks bestätigen nur die Zustellung; ready setzt die Uhr erst nach
 * E9 (MEMBER bei G, angewandtes E ≥ gemeldetem E, kein offener Transfer).
 */
internal class ReplicaReceiver(
    private val data: LocalData,
    private val send: (peer: String, message: SyncMessage) -> Unit,
) {
    /** Vom Handy gemeldeter Stand je Konto und Match (Summary.lifecycle bzw. Transfer), nur im Speicher. */
    private val reported = HashMap<Pair<String, String>, LifecycleSummary>()

    /** E2: Die erste gültige Access-Zustellung aktiviert das Replikat im selben Commit. */
    fun onAccessState(
        peer: String,
        message: AccessState,
        fence: () -> Unit,
    ) {
        val ctx = data.replicas.captureContext()
        val level = ReplicaAccessLevel.valueOf(message.access.name)
        data.transaction {
            data.replicas.activate(ctx, fence)
            data.replicas.applyAccess(ctx, message.matchId.value, message.generation, level, fence)
        }
        // Ack bestätigt die angewandte Zustellung; ready folgt unabhängig davon (E9)
        send(peer, AccessAck(message.matchId, message.generation, message.access))
        markReadyIfDue(ctx, message.matchId.value, fence)
    }

    /** Eine Wire-Seite als eigene Data-Seite; Wiederholungen sind idempotent, Lücken scheitern ohne Ack. */
    fun onChunk(
        peer: String,
        chunk: RedactedChunk,
        fence: () -> Unit,
    ) {
        val ctx = data.replicas.captureContext()
        val match = chunk.matchId.value
        // Wire-ID trägt keine Kontokennung; die lokale Transfer-ID ist je Konto eindeutig (A → B → A)
        val id = "${ctx.scopeId}:${chunk.transferId}"
        val complete = chunk.idsDone && chunk.eventsDone
        data.transaction {
            data.replicas.beginRedaction(ctx, RedactionTransfer(id, match, chunk.generation, chunk.epoch, chunk.highWatermark), fence)
            data.replicas.stageRedaction(ctx, id, chunk.page(), fence)
            if (complete) data.replicas.completeRedaction(ctx, id, fence)
        }
        send(peer, RedactedChunkAck(chunk.transferId, chunk.matchId, chunk.generation, chunk.epoch, chunk.index))
        if (!complete) return
        send(peer, RedactionAck(chunk.transferId, chunk.matchId, chunk.generation, chunk.epoch))
        remember(ctx, match, LifecycleSummary(chunk.generation, MatchAccessLevel.MEMBER, chunk.epoch))
        markReadyIfDue(ctx, match, fence)
    }

    fun onLifecycle(
        lifecycle: Map<String, LifecycleSummary>,
        fence: () -> Unit,
    ) {
        if (lifecycle.isEmpty()) return
        val ctx = data.replicas.captureContext()
        lifecycle.forEach { (match, state) ->
            remember(ctx, match, state)
            try {
                markReadyIfDue(ctx, match, fence)
            } catch (_: IllegalStateException) {
                // ein uneinheitlicher Stand eines Matches blockiert nicht die übrigen Meldungen
            }
        }
    }

    /** Eigener angewandter Stand für `Summary.lifecycle` (E8 am Handy). */
    fun summaryOf(matches: Collection<String>): Map<String, LifecycleSummary> {
        val ctx = data.replicas.captureContext()
        return matches
            .mapNotNull { id ->
                val access = data.replicas.access(ctx, id) ?: return@mapNotNull null
                val level = access.access.wire() ?: return@mapNotNull null
                id to LifecycleSummary(access.generation ?: return@mapNotNull null, level, access.redactionEpoch)
            }.take(MAX_LIFECYCLE)
            .toMap()
    }

    private fun remember(
        ctx: ReplicaContext,
        match: String,
        state: LifecycleSummary,
    ) {
        val key = ctx.accountKey to match
        val known = reported[key]
        if (known == null || state.generation > known.generation ||
            (state.generation == known.generation && state.redactionApplied >= known.redactionApplied)
        ) {
            reported[key] = state
        }
    }

    private fun markReadyIfDue(
        ctx: ReplicaContext,
        match: String,
        fence: () -> Unit,
    ) {
        val access = data.replicas.access(ctx, match) ?: return
        val generation = access.generation ?: return
        val report = reported[ctx.accountKey to match] ?: return
        if (access.access != ReplicaAccessLevel.MEMBER || access.ready || report.generation != generation) return
        if (report.access.name != access.access.name || access.redactionEpoch < report.redactionApplied) return
        if (data.replicas.redactionProgress(ctx, match)?.completed == false) return
        data.replicas.markReady(ctx, match, generation, access.redactionEpoch, fence)
    }

    private companion object {
        const val MAX_LIFECYCLE = 200
    }
}
