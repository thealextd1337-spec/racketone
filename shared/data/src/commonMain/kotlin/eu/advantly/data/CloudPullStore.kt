package eu.advantly.data

import eu.advantly.data.db.AdvantlyDatabase
import kotlinx.serialization.json.jsonObject

/** D1-Persistenz auf demselben seriellen Kontext wie LocalData. Alle Commits verlangen einen synchronen Fence. */
class CloudPullStore internal constructor(
    private val database: AdvantlyDatabase,
    private val data: LocalData,
) {
    private val queries = database.cloudPullQueries

    /** Nur der bei [beginRun] erfasste Replikatkontext trägt Commits dieses Laufs; Auswahlwechsel (auch A → B → A) brechen ab. */
    private var runContext: RunContext? = null

    private class RunContext(
        val profileId: String,
        val runSerial: Long,
        val ctx: ReplicaContext,
    )

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
            row?.round_catalog_ready == 1L,
            row?.event_requests_since_metadata ?: 0,
        )
    }

    fun beginRun(
        profileId: String,
        fence: () -> Unit,
    ): CloudPullProgress {
        val ctx = data.replicas.captureContext()
        val started =
            data.transaction {
                fence()
                require(profileId.isNotBlank())
                check(ctx.accountKey == profileId) { "Replikatkontext gehört zu einem anderen Konto" }
                queries.ensureProfile(profileId)
                val state = progress(profileId)
                check(state.runSerial < Long.MAX_VALUE)
                queries.beginRun(profileId)
                if (!state.roundActive) {
                    check(state.checkRound < Long.MAX_VALUE)
                    queries.startCheckRound(profileId)
                }
                fence()
                data.replicas.validate(ctx)
                progress(profileId)
            }
        runContext = RunContext(profileId, started.runSerial, ctx)
        return started
    }

    fun requireRun(
        profileId: String,
        runSerial: Long,
    ) {
        check(progress(profileId).runSerial == runSerial) { "Pull-Lauf wurde ersetzt" }
        context(profileId, runSerial)
    }

    private fun context(
        profileId: String,
        runSerial: Long,
    ): ReplicaContext {
        val current = runContext?.takeIf { it.profileId == profileId && it.runSerial == runSerial }
        checkNotNull(current) { "Pull-Lauf ohne gültigen Replikatkontext" }
        data.replicas.validate(current.ctx)
        return current.ctx
    }

    /**
     * ABSENT bei G für Matches, die der letzte vollständige Katalog dieser Epoche nicht mehr geliefert hat.
     * Begrenzt je Transaktion; während eines laufenden Neuscans geschieht nichts. Liefert die Zahl angewandter Matches.
     */
    fun applyMissing(
        profileId: String,
        runSerial: Long,
        fence: () -> Unit,
    ): Int =
        data.transaction {
            fence()
            requireRun(profileId, runSerial)
            val state = progress(profileId)
            val generation = state.visibilityEpoch
            if (state.targetEpoch != null || generation == null) return@transaction 0
            val ctx = context(profileId, runSerial)
            val missing = queries.missingMatches(profileId, state.scanRun, generation, MISSING_PAGE).executeAsList()
            missing.forEach { data.replicas.applyAccess(ctx, it, generation, ReplicaAccessLevel.ABSENT, fence) }
            fence()
            missing.size
        }

    /** restart_required: Handle verwerfen und zuerst einen frischen Katalog holen, bevor Event-/Readiness-Arbeit weiterläuft. */
    fun restartCatalog(
        profileId: String,
        runSerial: Long,
        fence: () -> Unit,
    ) = data.transaction {
        fence()
        requireRun(profileId, runSerial)
        queries.restartCatalog(profileId)
        fence()
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
            val ctx = context(profileId, runSerial)
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
                val level = if (match.access == "member") ReplicaAccessLevel.MEMBER else ReplicaAccessLevel.FEED
                // Zugriff bei G vor jeder Materialisierung; eine ältere Generation darf nichts mehr materialisieren.
                if (data.replicas.applyAccess(ctx, match.id, page.visibilityEpoch, level, fence) == TransitionResult.IGNORED) {
                    queries.markSeen(state.scanRun, profileId, match.id)
                    return@forEach
                }
                // Neues E bei gleichem G: Namen sind serverseitig schon redigiert, lokal also sofort sperren.
                if (match.redactionEpoch > (data.replicas.access(ctx, match.id)?.redactionEpoch ?: 0)) {
                    data.replicas.markNotReady(ctx, match.id, fence)
                }
                val retained = database.matchHeaderQueries.countById(match.id).executeAsOne() > 0
                // Ein vollzogener Entzug hinterlässt nichts; nur zurückgehaltene lokale Daten bleiben als D2-Rest sichtbar.
                val removal = match.deleted || (old?.access == "member" && match.access != "member" && retained)
                val materializedVersion = snapshot(profileId, CloudPullEntityKind.MATCH, match.id)?.materializedVersion
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
                if (match.access == "member" && !match.deleted) {
                    val record = match.record
                    if (saved && record != null) {
                        require(record.id.value == match.id)
                        if (data.cloud.materializePullMatch(record, profileId, materializedVersion)) {
                            queries.materialized(match.serverVersion, profileId, "MATCH", match.id)
                        }
                    }
                    // Auch bei unverändertem Snapshot: ein Generationswechsel hat die Eventarbeit bereits entfernt.
                    queries.ensureEventWork(profileId, match.id)
                    if (old != null && (
                            match.serverVersion > old.serverVersion || match.access != old.access ||
                                match.accessEpoch != old.accessEpoch || match.redactionEpoch != old.redactionObserved
                        )
                    ) {
                        queries.invalidateEventCheck(profileId, match.id)
                    }
                    // Ohne Kopf ist noch kein Download-Herkunftsbeleg vor einer möglichen Genesis vorhanden.
                    val pending = database.matchHeaderQueries.countById(match.id).executeAsOne() == 0L
                    queries.setMaterializationPending(if (pending) 1 else 0, profileId, match.id)
                }
            }
            if (!page.hasMore) {
                if (state.targetEpoch != null) {
                    // Fehlende Matches erst hier markieren; ABSENT folgt begrenzt über applyMissing.
                    queries.markMissing(profileId, state.scanRun)
                    queries.reopenUnready(profileId)
                }
                queries.completeMetadata(page.visibilityEpoch, profileId)
            } else {
                queries.incompleteMetadata(profileId)
            }
            // Erst dieser Cursor bestätigt sämtliche Snapshots, Scanmarken und dauerhaften Eventaufträge.
            data.cloud.saveCursor(profileId, METADATA_STREAM, page.cursor)
            queries.metadataTurnStored(profileId)
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
        if (!state.roundActive || !state.roundCatalogReady) return null
        requireRun(profileId, runSerial)
        return queries.nextEventWork(profileId, state.checkRound).executeAsOneOrNull()?.let { eventWork(it.profile_id, it.match_id) }
    }

    /** Vor Versand gezählt: auch erfolglose Versuche dürfen die nächste Metadatenseite nicht verdrängen. */
    fun recordEventRequest(
        work: CloudPullEventWork,
        runSerial: Long,
        fence: () -> Unit,
    ) = data.transaction {
        fence()
        requireWork(work, runSerial)
        check(progress(work.profileId).eventRequestsSinceMetadata < EVENT_REQUESTS_PER_METADATA_TURN)
        queries.recordEventRequest(work.profileId)
        fence()
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
        check(progress.roundActive && progress.roundCatalogReady && !work.materializationPending)
        check(eventWork(work.profileId, work.matchId) == work)
        val match = requireNotNull(snapshot(work.profileId, CloudPullEntityKind.MATCH, work.matchId))
        check(match.access == "member" && !match.removalPending)
        check(database.matchTombstoneQueries.countById(work.matchId).executeAsOne() == 0L)
        val access = data.replicas.access(context(work.profileId, runSerial), work.matchId)
        check(access?.access == ReplicaAccessLevel.MEMBER && access.generation != null) { "Kein Mitgliedszugriff bei G" }
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
            fence()
            require(it.serverSeq > cursor && it.event.matchId == work.matchId)
            require(it.event.deviceSeq > 0 && it.event.id.isNotBlank() && it.event.deviceId.isNotBlank())
            it.event.toScoreEvent() // Keine still übersprungenen Engine-Payloads als Vollständigkeitsbeweis.
            cursor = it.serverSeq
            fence()
        }
        val materializedVersion = snapshot(work.profileId, CloudPullEntityKind.MATCH, work.matchId)?.materializedVersion
        val participants =
            data.cloud.metadataState(CloudMetadataKind.PARTICIPANTS, work.matchId)?.takeIf {
                it.origin == CloudMetadataOrigin.CLOUD && it.selected.revision == it.uploadedRevision &&
                    it.sourceDeviceId == null && materializedVersion != null &&
                    queries.materializedOwner("MATCH", work.matchId).executeAsOneOrNull() == work.profileId
            }
        data.cloud.storeDownloaded(received.map { it.event }, fence)
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
            fence()
            val event = it.event
            val old = queries.receipt(work.profileId, work.matchId, event.id).executeAsOneOrNull()
            if (old == null) {
                queries.saveReceipt(work.profileId, work.matchId, event.id, it.serverSeq, event.deviceId, event.deviceSeq, work.repairRun)
            } else {
                check(old.server_seq == it.serverSeq && old.device_id == event.deviceId && old.device_seq == event.deviceSeq)
                queries.markReceiptRepair(work.repairRun, work.profileId, work.matchId, event.id)
            }
            fence()
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

    /**
     * Ausstehender Redaktionstransfer eines Mitgliedsmatches bei G: solange für E > 0 bei dieser Generation noch kein
     * vollständiger Transfer (G, E, H) angewandt ist. Ein Generationswechsel verlangt daher einen neuen Transfer.
     */
    fun redactionWork(
        work: CloudPullEventWork,
        runSerial: Long,
    ): CloudPullRedactionWork? {
        requireWork(work, runSerial)
        val ctx = context(work.profileId, runSerial)
        val access = checkNotNull(data.replicas.access(ctx, work.matchId))
        val generation = checkNotNull(access.generation)
        val epoch = requireNotNull(snapshot(work.profileId, CloudPullEntityKind.MATCH, work.matchId)).redactionObserved
        if (epoch == 0L || epoch < access.redactionEpoch) return null
        val transfer =
            data.replicas.redactionProgress(ctx, work.matchId)?.takeIf {
                it.transfer.generation == generation && it.transfer.epoch == epoch
            }
        if (transfer?.completed == true) return null
        return CloudPullRedactionWork(
            work.matchId,
            generation,
            epoch,
            transfer?.transfer?.highWatermark,
            transfer?.cursor ?: RedactionCursor(null, 0),
        )
    }

    /**
     * Übernimmt genau eine authentisierte Cloud-Redaktionsseite atomar: Transfer (G, E, H) bei Bedarf beginnen, in die
     * zulässigen Data-Seiten (≤ 200 IDs, ≤ 500 Events, ≤ 64 KiB) teilen und nach der letzten Seite vollständig anwenden.
     * true: Transfer abgeschlossen und E lokal angewandt.
     */
    fun stageRedaction(
        work: CloudPullEventWork,
        runSerial: Long,
        request: CloudPullRedactionWork,
        highWatermark: Long,
        playerIds: List<String>,
        events: List<ReplicaRedactionEvent>,
        idsDone: Boolean,
        eventsDone: Boolean,
        fence: () -> Unit,
    ): Boolean =
        data.transaction {
            fence()
            check(redactionWork(work, runSerial) == request) { "Redaktionsstand hat sich seit der Anfrage geändert" }
            require(highWatermark >= 0 && (request.highWatermark == null || request.highWatermark == highWatermark))
            val ctx = context(work.profileId, runSerial)
            val id = "${ctx.accountKey}:${request.matchId}:${request.generation}:${request.redactionEpoch}"
            if (request.highWatermark == null) {
                data.replicas.beginRedaction(
                    ctx,
                    RedactionTransfer(id, request.matchId, request.generation, request.redactionEpoch, highWatermark),
                    fence,
                )
            }
            val progress = checkNotNull(data.replicas.redactionProgress(ctx, request.matchId))
            check(progress.transfer.id == id && progress.cursor == request.cursor && !progress.completed)
            redactionPages(progress, playerIds, events, idsDone, eventsDone).forEach {
                data.replicas.stageRedaction(ctx, id, it, fence)
            }
            val complete = idsDone && eventsDone
            if (complete) {
                data.replicas.completeRedaction(ctx, id, fence)
                queries.redactionApplied(request.redactionEpoch, work.profileId, request.matchId)
            }
            fence()
            complete
        }

    private fun redactionPages(
        start: RedactionProgress,
        playerIds: List<String>,
        events: List<ReplicaRedactionEvent>,
        idsDone: Boolean,
        eventsDone: Boolean,
    ): List<RedactionPage> {
        val pages = mutableListOf<RedactionPage>()
        var from = start.cursor
        var rest = events
        do {
            val ids = if (pages.isEmpty()) playerIds else emptyList()
            val empty = RedactionPage(start.nextPageIndex + pages.size, from, from, ids, emptyList(), idsDone, eventsDone)
            var size = DataJson.encodeToString(RedactionPage.serializer(), empty).encodeToByteArray().size + CURSOR_SLACK
            var count = 0
            while (count < rest.size) {
                val extra = DataJson.encodeToString(ReplicaRedactionEvent.serializer(), rest[count]).encodeToByteArray().size + 1
                if (size + extra > MAX_REDACTION_PAGE_BYTES) break
                size += extra
                count++
            }
            // Ein einzelnes übergroßes Event weist stageRedaction zurück, statt endlos leere Seiten zu erzeugen.
            if (count == 0 && rest.isNotEmpty() && ids.isEmpty()) count = 1
            val chunk = rest.take(count)
            rest = rest.drop(count)
            val next = RedactionCursor(ids.lastOrNull() ?: from.afterPlayerId, chunk.lastOrNull()?.serverSeq ?: from.afterServerSeq)
            pages += empty.copy(next = next, events = chunk, eventsDone = eventsDone && rest.isEmpty())
            from = next
        } while (rest.isNotEmpty())
        return pages
    }

    /**
     * Readiness-Ziel nur für (Match, G) mit Mitgliedszugriff, abgeschlossenem Katalog derselben Epoche und angewandtem E.
     * H stammt für E > 0 aus dem vollständigen Transfer, für E = 0 aus dem festgehaltenen Serverwert (null = erst abrufen).
     */
    fun readinessWork(
        work: CloudPullEventWork,
        runSerial: Long,
    ): CloudPullReadinessWork? {
        requireWork(work, runSerial)
        val ctx = context(work.profileId, runSerial)
        val access = checkNotNull(data.replicas.access(ctx, work.matchId))
        val generation = checkNotNull(access.generation)
        val progress = progress(work.profileId)
        if (access.ready || progress.targetEpoch != null || progress.visibilityEpoch != generation) return null
        val epoch = access.redactionEpoch
        if (requireNotNull(snapshot(work.profileId, CloudPullEntityKind.MATCH, work.matchId)).redactionObserved != epoch) return null
        val high =
            if (epoch > 0) {
                data.replicas
                    .redactionProgress(ctx, work.matchId)
                    ?.takeIf { it.completed && it.transfer.generation == generation && it.transfer.epoch == epoch }
                    ?.transfer
                    ?.highWatermark ?: return null
            } else {
                queries
                    .readinessMark(work.profileId, work.matchId)
                    .executeAsOneOrNull()
                    ?.takeIf { it.generation == generation && it.redaction_epoch == 0L }
                    ?.high_watermark
            }
        return CloudPullReadinessWork(work.matchId, generation, epoch, high)
    }

    /**
     * Prüft einen authentisierten Readiness-Vektor bis H gegen heruntergeladene lokale Events und den materialisierten
     * Snapshot. Nur bei exakter Abdeckung wird ready unter Fence gesetzt; eigene ungesendete Events zählen nie mit.
     * Bei exakter Abdeckung gilt die Eventarbeit dieser Runde als geprüft, auch wenn nur der Snapshot noch veraltet ist.
     */
    fun applyReadiness(
        work: CloudPullEventWork,
        runSerial: Long,
        request: CloudPullReadinessWork,
        highWatermark: Long,
        serverVersion: Long,
        server: List<CloudPullWatermark>,
        fence: () -> Unit,
    ): CloudPullReadinessResult =
        data.transaction {
            fence()
            check(readinessWork(work, runSerial) == request) { "Readiness-Stand hat sich seit der Anfrage geändert" }
            require(highWatermark >= 0 && (request.highWatermark == null || request.highWatermark == highWatermark))
            require(server.map { it.deviceId }.distinct().size == server.size)
            require(server.all { it.deviceId.isNotBlank() && it.maxSeq > 0 && it.eventCount in 1..it.maxSeq })
            require(server.sumOf { it.eventCount } <= highWatermark)
            if (request.highWatermark == null) {
                check(request.redactionEpoch == 0L)
                queries.saveReadinessMark(work.profileId, work.matchId, request.generation, 0, highWatermark)
            }
            val local =
                queries
                    .readinessWatermarks(work.profileId, work.matchId, highWatermark, if (work.repairing) 1 else 0, work.repairRun)
                    .executeAsList()
                    .map { CloudPullWatermark(it.device_id, requireNotNull(it.max_seq), it.event_count) }
            val snapshot = requireNotNull(snapshot(work.profileId, CloudPullEntityKind.MATCH, work.matchId))
            val result =
                when {
                    local.toSet() != server.toSet() && work.cursor < highWatermark -> {
                        queries.reopenEventWork(work.profileId, work.matchId)
                        CloudPullReadinessResult.MISSING_EVENTS
                    }

                    local.toSet() != server.toSet() -> {
                        check(work.repairRun < Long.MAX_VALUE)
                        queries.startRepair(work.profileId, work.matchId)
                        queries.reopenEventWork(work.profileId, work.matchId)
                        data.cloud.saveCursor(work.profileId, eventStream(work.matchId), 0)
                        CloudPullReadinessResult.GAP
                    }

                    snapshot.serverVersion != serverVersion || snapshot.materializedVersion != serverVersion -> {
                        completeChecked(work)
                        CloudPullReadinessResult.STALE_SNAPSHOT
                    }

                    else -> {
                        completeChecked(work)
                        data.replicas.markReady(
                            context(work.profileId, runSerial),
                            work.matchId,
                            request.generation,
                            request.redactionEpoch,
                            fence,
                        )
                        CloudPullReadinessResult.READY
                    }
                }
            fence()
            result
        }

    /** Exakter Vektor bis H ersetzt für diese Runde den Wasserstandsvergleich. */
    private fun completeChecked(work: CloudPullEventWork) {
        if (work.repairing) queries.finishRepair(work.profileId, work.matchId, work.repairRun)
        queries.completeEventWork(progress(work.profileId).checkRound, work.profileId, work.matchId)
    }

    fun pendingReadiness(profileId: String): Long = queries.pendingReadinessCount(profileId).executeAsOne()

    fun pendingRemovals(profileId: String): Long = queries.pendingRemovalCount(profileId).executeAsOne()

    fun pendingRedactions(profileId: String): Long = queries.pendingRedactionCount(profileId).executeAsOne()

    fun failedEventJobs(profileId: String): Long = queries.eventFailureCount(profileId).executeAsOne()

    fun pendingMaterializations(profileId: String): Long = queries.pendingMaterializationCount(profileId).executeAsOne()

    companion object {
        const val METADATA_STREAM: String = "pull"
        const val EVENT_REQUESTS_PER_METADATA_TURN: Long = 12
        private const val MAX_REDACTION_PAGE_BYTES = 64 * 1024
        private const val MISSING_PAGE = 200L

        // Platz für wachsende Cursorziffern zwischen Messung und endgültiger Seite.
        private const val CURSOR_SLACK = 64

        fun eventStream(matchId: String): String = "pull:events:$matchId"
    }
}
