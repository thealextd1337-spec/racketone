package eu.advantly.data

import eu.advantly.data.db.AdvantlyDatabase
import kotlinx.serialization.json.jsonObject

/** D1-Persistenz auf demselben seriellen Kontext wie LocalData. Alle Commits verlangen einen synchronen Fence. */
class CloudPullStore internal constructor(
    private val database: AdvantlyDatabase,
    private val data: LocalData,
) {
    private val queries = database.cloudPullQueries

    fun progress(profileId: String): CloudPullProgress {
        require(profileId.isNotBlank())
        val row = queries.profile(profileId).executeAsOneOrNull()
        return CloudPullProgress(
            profileId,
            row?.visibility_epoch,
            row?.target_epoch,
            row?.scan_run ?: 0,
            row?.run_serial ?: 0,
            data.cloud.cursor(profileId, METADATA_STREAM),
            row?.metadata_complete == 1L,
            row?.check_round ?: 0,
            row?.round_active == 1L,
        )
    }

    fun beginRun(
        profileId: String,
        fence: () -> Unit,
    ): CloudPullProgress =
        data.transaction {
            fence()
            require(profileId.isNotBlank())
            queries.ensureProfile(profileId)
            val state = progress(profileId)
            check(state.runSerial < Long.MAX_VALUE)
            queries.beginRun(profileId)
            if (!state.roundActive) {
                check(state.checkRound < Long.MAX_VALUE)
                queries.startCheckRound(profileId)
            }
            fence()
            progress(profileId)
        }

    fun requireRun(
        profileId: String,
        runSerial: Long,
    ) {
        check(progress(profileId).runSerial == runSerial) { "Pull-Lauf wurde ersetzt" }
    }

    fun snapshot(
        profileId: String,
        kind: CloudPullEntityKind,
        id: String,
    ): CloudPullSnapshot? =
        queries.snapshot(profileId, kind.name, id).executeAsOneOrNull()?.let {
            CloudPullSnapshot(
                it.profile_id,
                kind,
                it.entity_id,
                it.server_version,
                it.snapshot_json,
                it.access,
                it.access_epoch,
                it.redaction_observed,
                it.redaction_applied,
                it.materialized_version,
                it.seen_run,
                it.removal_pending == 1L,
            )
        }

    /** false: Epoche wechselte hinter Cursor null; Antwort verwerfen und dauerhaft ab null neu beginnen. */
    fun commitMetadata(
        profileId: String,
        runSerial: Long,
        inputCursor: Long,
        page: CloudPullMetadataPage,
        fence: () -> Unit,
    ): Boolean =
        data.transaction {
            fence()
            requireRun(profileId, runSerial)
            var state = progress(profileId)
            check(state.cursor == inputCursor)
            require(page.cursor >= inputCursor && page.visibilityEpoch >= 0)
            require(!page.hasMore || page.cursor > inputCursor)
            val epoch = state.targetEpoch ?: state.visibilityEpoch
            if (epoch != page.visibilityEpoch) {
                check(state.scanRun < Long.MAX_VALUE)
                queries.startScan(page.visibilityEpoch, profileId)
                data.cloud.saveCursor(profileId, METADATA_STREAM, 0)
                state = progress(profileId)
                if (inputCursor != 0L) {
                    fence()
                    return@transaction false
                }
            }
            page.players.forEach { player ->
                val old = snapshot(profileId, CloudPullEntityKind.PLAYER, player.id)
                if (saveSnapshot(profileId, CloudPullEntityKind.PLAYER, player.id, player.serverVersion, player.json, state.scanRun)) {
                    if (data.cloud.materializePullPlayer(player, profileId, old?.materializedVersion)) {
                        queries.materialized(player.serverVersion, profileId, "PLAYER", player.id)
                    }
                }
            }
            page.friendships.forEach {
                saveSnapshot(profileId, CloudPullEntityKind.FRIENDSHIP, it.id, it.serverVersion, it.json, state.scanRun)
            }
            page.matches.forEach { match ->
                require(match.access in setOf("member", "feed") && match.redactionEpoch >= 0)
                val old = snapshot(profileId, CloudPullEntityKind.MATCH, match.id)
                val removal = match.deleted || (old?.access == "member" && match.access != "member")
                val saved =
                    saveSnapshot(
                        profileId,
                        CloudPullEntityKind.MATCH,
                        match.id,
                        match.serverVersion,
                        match.json,
                        state.scanRun,
                        match.access,
                        match.accessEpoch,
                        match.redactionEpoch,
                        removal,
                    )
                queries.markSeen(state.scanRun, profileId, match.id)
                if (saved && match.access == "member" && !match.deleted) {
                    val record = match.record
                    if (record != null) {
                        require(record.id.value == match.id)
                        if (data.cloud.materializePullMatch(record, profileId, old?.materializedVersion)) {
                            queries.materialized(match.serverVersion, profileId, "MATCH", match.id)
                        }
                    }
                    queries.ensureEventWork(profileId, match.id)
                    // Ohne Kopf ist noch kein Download-Herkunftsbeleg vor einer möglichen Genesis vorhanden.
                    val pending = database.matchHeaderQueries.countById(match.id).executeAsOne() == 0L
                    queries.setMaterializationPending(if (pending) 1 else 0, profileId, match.id)
                }
            }
            if (!page.hasMore) {
                if (state.targetEpoch != null) queries.markMissing(profileId, state.scanRun)
                queries.completeMetadata(page.visibilityEpoch, profileId)
            } else {
                queries.incompleteMetadata(profileId)
            }
            // Erst dieser Cursor bestätigt sämtliche Snapshots, Scanmarken und dauerhaften Eventaufträge.
            data.cloud.saveCursor(profileId, METADATA_STREAM, page.cursor)
            fence()
            true
        }

    private fun saveSnapshot(
        profileId: String,
        kind: CloudPullEntityKind,
        id: String,
        version: Long,
        json: String,
        scanRun: Long,
        access: String? = null,
        accessEpoch: Long? = null,
        redaction: Long = 0,
        removal: Boolean = false,
    ): Boolean {
        require(id.isNotBlank() && version > 0)
        DataJson.parseToJsonElement(json).jsonObject
        val old = snapshot(profileId, kind, id)
        if (old != null && (
                version < old.serverVersion ||
                    (version == old.serverVersion && !(kind == CloudPullEntityKind.MATCH && scanRun > old.seenRun))
            )
        ) {
            return false
        }
        if (old == null) {
            queries.insertSnapshot(profileId, kind.name, id, version, json, access, accessEpoch, redaction, scanRun, if (removal) 1 else 0)
        } else {
            queries.updateSnapshot(version, json, access, accessEpoch, redaction, scanRun, if (removal) 1 else 0, profileId, kind.name, id)
        }
        return true
    }

    fun nextEventWork(
        profileId: String,
        runSerial: Long,
    ): CloudPullEventWork? {
        val state = progress(profileId)
        if (!state.roundActive || !state.metadataComplete) return null
        requireRun(profileId, runSerial)
        return queries.nextEventWork(profileId, state.checkRound).executeAsOneOrNull()?.let { eventWork(it.profile_id, it.match_id) }
    }

    /** Auswahl ist dauerhaft fair, auch wenn die folgende Seite fehlschlägt oder der Prozess abbricht. Kein Wasserstands-Ack. */
    fun claimEventWork(
        work: CloudPullEventWork,
        runSerial: Long,
        fence: () -> Unit,
    ): CloudPullEventWork =
        data.transaction {
            fence()
            requireWork(work, runSerial)
            queries.recordEventAttempt(runSerial, work.profileId, work.matchId)
            fence()
            requireNotNull(eventWork(work.profileId, work.matchId))
        }

    /** Fehler bleiben sichtbar, quittieren aber weder Events, Cursor noch den Wasserstand. */
    fun recordEventFailure(
        profileId: String,
        matchId: String,
        runSerial: Long,
        notificationOnly: Boolean,
        fence: () -> Unit,
    ) = data.transaction {
        fence()
        val work = requireNotNull(eventWork(profileId, matchId))
        requireWork(work, runSerial)
        val failedRound = if (notificationOnly) work.failedRound else progress(profileId).checkRound
        queries.recordEventFailure(
            failedRound,
            if (notificationOnly) "cloud_pull_notification_failed" else "cloud_pull_event_work_failed",
            profileId,
            matchId,
        )
        fence()
    }

    /** Eine Runde endet erst nach allen erreichbaren Prüfungen; Fehler/Materialisierungsarbeit sind kein Erfolg. */
    fun finishRound(
        profileId: String,
        runSerial: Long,
        fence: () -> Unit,
    ) = data.transaction {
        fence()
        requireRun(profileId, runSerial)
        check(progress(profileId).metadataComplete)
        check(nextEventWork(profileId, runSerial) == null)
        queries.finishCheckRound(profileId)
        fence()
    }

    fun eventWork(
        profileId: String,
        matchId: String,
    ): CloudPullEventWork? =
        queries.eventWork(profileId, matchId).executeAsOneOrNull()?.let {
            CloudPullEventWork(
                profileId,
                matchId,
                it.repairing == 1L,
                it.repair_run,
                data.cloud.cursor(profileId, eventStream(matchId)),
                it.last_attempt,
                it.last_checked,
                it.failed_round,
                it.last_failure,
                it.needs_verification == 1L,
                it.materialization_pending == 1L,
            )
        }

    private fun requireWork(
        work: CloudPullEventWork,
        runSerial: Long,
    ) {
        requireRun(work.profileId, runSerial)
        val progress = progress(work.profileId)
        check(progress.roundActive && progress.metadataComplete && !work.materializationPending)
        check(eventWork(work.profileId, work.matchId) == work)
        val match = requireNotNull(snapshot(work.profileId, CloudPullEntityKind.MATCH, work.matchId))
        check(match.access == "member" && !match.removalPending)
        check(database.matchTombstoneQueries.countById(work.matchId).executeAsOne() == 0L)
    }

    /** Eigene atomare Grenze: Eventpayloads, Empfangsnachweise und Eventcursor; kein Metadatencursor. */
    fun commitEvents(
        work: CloudPullEventWork,
        runSerial: Long,
        received: List<CloudPullReceipt>,
        fence: () -> Unit,
        endOfStream: Boolean = false,
        afterCommit: () -> Unit = {},
    ) = data.transaction {
        fence()
        requireWork(work, runSerial)
        var cursor = work.cursor
        received.forEach {
            require(it.serverSeq > cursor && it.event.matchId == work.matchId)
            require(it.event.deviceSeq > 0 && it.event.id.isNotBlank() && it.event.deviceId.isNotBlank())
            it.event.toScoreEvent() // Keine still übersprungenen Engine-Payloads als Vollständigkeitsbeweis.
            cursor = it.serverSeq
        }
        val materializedVersion = snapshot(work.profileId, CloudPullEntityKind.MATCH, work.matchId)?.materializedVersion
        val participants =
            data.cloud.metadataState(CloudMetadataKind.PARTICIPANTS, work.matchId)?.takeIf {
                it.origin == CloudMetadataOrigin.CLOUD && it.selected.revision == it.uploadedRevision &&
                    it.sourceDeviceId == null && materializedVersion != null &&
                    queries.materializedOwner("MATCH", work.matchId).executeAsOneOrNull() == work.profileId
            }
        data.cloud.storeDownloaded(received.map { it.event })
        // Die lokale Assignment-Projektion dieser Serverevents ist keine ungeklärte lokale Bearbeitung.
        // Ausschließlich einen vorher sauberen Cloud-Teilnehmerstand quittieren; andere Revisionen/Acks bleiben erhalten.
        if (participants != null) {
            val projected = requireNotNull(data.cloud.metadataState(CloudMetadataKind.PARTICIPANTS, work.matchId))
            if (projected.origin == participants.origin && projected.sourceDeviceId == participants.sourceDeviceId &&
                projected.writerProfileId == participants.writerProfileId && projected.uploadedRevision == participants.uploadedRevision &&
                projected.selected.revision > participants.selected.revision
            ) {
                queries.cleanDownloadedParticipants(
                    projected.selected.revision,
                    work.matchId,
                    participants.writerProfileId,
                    participants.uploadedRevision,
                    work.profileId,
                    requireNotNull(materializedVersion),
                )
            }
        }
        received.forEach {
            val event = it.event
            val old = queries.receipt(work.profileId, work.matchId, event.id).executeAsOneOrNull()
            if (old == null) {
                queries.saveReceipt(work.profileId, work.matchId, event.id, it.serverSeq, event.deviceId, event.deviceSeq, work.repairRun)
            } else {
                check(old.server_seq == it.serverSeq && old.device_id == event.deviceId && old.device_seq == event.deviceSeq)
                queries.markReceiptRepair(work.repairRun, work.profileId, work.matchId, event.id)
            }
        }
        data.cloud.saveCursor(work.profileId, eventStream(work.matchId), cursor)
        queries.setNeedsVerification(if (endOfStream) 1 else 0, work.profileId, work.matchId)
        fence()
        if (received.isNotEmpty()) data.afterCommit(afterCommit)
    }

    fun receivedWatermarks(work: CloudPullEventWork): List<CloudPullWatermark> =
        queries
            .receiptWatermarks(work.profileId, work.matchId, if (work.repairing) 1 else 0, work.repairRun)
            .executeAsList()
            .map { CloudPullWatermark(it.device_id, requireNotNull(it.max_seq), it.event_count) }

    /** Abweichung beginnt einen dauerhaften Reparaturlauf ab null; auch eine erneute Reparatur bleibt begrenzt. */
    fun verifyEvents(
        work: CloudPullEventWork,
        runSerial: Long,
        server: List<CloudPullWatermark>,
        fence: () -> Unit,
    ): Boolean =
        data.transaction {
            fence()
            requireWork(work, runSerial)
            require(server.map { it.deviceId }.distinct().size == server.size)
            require(server.all { it.deviceId.isNotBlank() && it.maxSeq > 0 && it.eventCount > 0 && it.eventCount <= it.maxSeq })
            val matches = receivedWatermarks(work).toSet() == server.toSet()
            if (matches) {
                if (work.repairing) queries.finishRepair(work.profileId, work.matchId, work.repairRun)
                queries.completeEventWork(progress(work.profileId).checkRound, work.profileId, work.matchId)
            } else {
                check(work.repairRun < Long.MAX_VALUE)
                queries.startRepair(work.profileId, work.matchId)
                data.cloud.saveCursor(work.profileId, eventStream(work.matchId), 0)
            }
            fence()
            matches
        }

    fun pendingRemovals(profileId: String): Long = queries.pendingRemovalCount(profileId).executeAsOne()

    fun pendingRedactions(profileId: String): Long = queries.pendingRedactionCount(profileId).executeAsOne()

    fun failedEventJobs(profileId: String): Long = queries.eventFailureCount(profileId).executeAsOne()

    fun pendingMaterializations(profileId: String): Long = queries.pendingMaterializationCount(profileId).executeAsOne()

    companion object {
        const val METADATA_STREAM: String = "pull"

        fun eventStream(matchId: String): String = "pull:events:$matchId"
    }
}
