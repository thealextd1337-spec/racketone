package eu.advantly.data

import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchFormat
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.Placeholders
import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId
import eu.advantly.core.scoring.MatchCreated
import eu.advantly.data.db.Cloud_metadata
import eu.advantly.data.db.Match_header

fun CloudSyncRepository.metadataState(
    kind: CloudMetadataKind,
    id: String,
): CloudMetadataState? =
    database.cloudMetadataQueries.byKey(kind.name, id).executeAsOneOrNull()?.let {
        CloudMetadataState(
            it.version(),
            it.uploaded_revision,
            CloudMetadataOrigin.valueOf(it.origin),
            it.source_device_id,
            it.writer_profile_id,
        )
    }

internal fun Cloud_metadata.version() = CloudMetadataRevision(CloudMetadataKind.valueOf(kind), entity_id, revision)

/** Lokale Anlage ist ein eigener Herkunftsbeleg; Umbenennen eines empfangenen Gastes erteilt keine Rechte. */
internal fun CloudSyncRepository.recordLocalPlayer(id: String) {
    val source = database.cloudMetadataQueries.currentSource().executeAsOneOrNull()
    if (source == null) {
        // Ohne aktive lokale Identität zählt weiterhin ausschließlich ein bereits vorhandener U3-Quellenbeleg.
        resolvePlayerMetadataSources(id)
    } else {
        database.cloudMetadataQueries.recordSource("LOCAL", source, id, listOf("PLAYER"))
    }
}

/**
 * Genesis allein ist kein Eigentumsbeleg. U2 muss die Quelle und die Self-ID direkt belegen;
 * bei abweichendem Self-Alias müssen beide Quellen zusätzlich demselben Konto gehören.
 * Downloads behalten ihre explizite Herkunft und können durch einen eigenen Scorer nicht zu eigenen Köpfen werden.
 */
internal fun CloudSyncRepository.resolveMetadataSources() {
    val queries = database.cloudMetadataQueries
    queries.unknownMatches().executeAsList().forEach(::resolveMatchMetadataSource)
    resolvePlayerMetadataSources()
}

/** Quellenbelege wie bisher auflösen, ohne Spielerlisten oder Einzelabfragen je Spieler zu laden. */
internal fun CloudSyncRepository.resolvePlayerMetadataSources(id: String? = null) = database.cloudMetadataQueries.resolvePlayerSources(id)

/** Nur bei belegter Quellen-/Kontobindung; ungeklärte Kandidaten blockieren den fortschreitenden Cursor nicht. */
internal fun CloudSyncRepository.resolveOwnedMatchMetadataSources(profileId: String) {
    var after: String? = null
    do {
        val ids = database.cloudMetadataQueries.unknownMatchesForProfilePage(after, profileId).executeAsList()
        ids.forEach(::resolveMatchMetadataSource)
        after = ids.lastOrNull() ?: return
    } while (ids.size == 500)
}

internal fun CloudSyncRepository.resolveMatchMetadataSource(id: String) {
    if (metadataState(CloudMetadataKind.HEADER, id)?.origin != CloudMetadataOrigin.UNKNOWN) return
    val genesis =
        database.cloudMetadataQueries
            .genesisForMatch(id) { eventId, match, device, seq, lamport, at, parent, type, payload ->
                StoredEvent(eventId, match, device, seq, lamport, payload, at, parent, type)
            }.executeAsList()
            .firstNotNullOfOrNull { it.toScoreEventOrNull()?.takeIf { event -> event.payload is MatchCreated } } ?: return
    val created = genesis.payload as MatchCreated
    val source = database.localIdentityQueries.source(genesis.deviceId.value).executeAsOneOrNull() ?: return
    val creator = (created.createdBy ?: created.lineup.a.first()).value
    if (creator != source.self_id) {
        val owner = database.cloudUploadQueries.deviceOwner(source.device_id).executeAsOneOrNull() ?: return
        if (database.cloudMetadataQueries.ownedSelfAlias(creator, owner).executeAsOne() == 0L) return
    }
    database.cloudMetadataQueries.recordSource(
        if (source.local ==
            1L
        ) {
            "LOCAL"
        } else {
            "PEER"
        },
        source.device_id,
        id,
        listOf("HEADER", "PARTICIPANTS", "PROJECTION"),
    )
}

internal fun CloudSyncRepository.uploadHeader(id: String): CloudMatchHeader? =
    database.matchHeaderQueries.selectById(id).executeAsOneOrNull()?.let {
        CloudMatchHeader(it.id, it.sport, it.discipline, it.format_json, it.meta_json, it.created_at_ms, it.previous_match_id)
    }

internal fun CloudSyncRepository.uploadParticipants(
    id: String,
    profileId: String,
): CloudParticipants? {
    val header = database.matchHeaderQueries.selectById(id).executeAsOneOrNull() ?: return null
    val lineup = DataJson.decodeFromString(Lineup.serializer(), header.lineup_json)
    val redactedSlots = redactedAssignmentSlots(id)
    val assigned =
        database.matchAssignmentQueries
            .identitiesForMatch(id)
            .executeAsList()
            .associate { it.slot_id to it }
    val people =
        listOf("A" to lineup.a, "B" to lineup.b).flatMap { (team, slots) ->
            slots.mapIndexed { index, slot ->
                val row = assigned[slot.value]
                val legacy =
                    row
                        ?.player_json
                        ?.let { DataJson.decodeFromString(Player.serializer(), it) }
                        ?.takeIf { row.player_id == null || row.player_id == it.id.value }
                val playerId =
                    row?.player_id?.let(::PlayerId) ?: legacy?.id
                        ?: slot.takeUnless { row != null || Placeholders.isPlaceholder(it) }
                val player = playerId?.let { database.playerQueries.selectById(it.value).executeAsOneOrNull() }
                // Ungeklärte oder fremde Gäste bleiben benannte Slots, ohne eine Cloud-Spielerzuordnung zu behaupten.
                val reference =
                    playerId?.value?.takeIf {
                        player != null && player.kind != PlayerKind.PLACEHOLDER.name &&
                            database.cloudMetadataQueries.playerReferenceAllowed(it, profileId).executeAsOne() > 0
                    }
                val redacted =
                    slot.value in redactedSlots ||
                        playerId?.let { database.replicaLifecycleQueries.isRedacted(it.value).executeAsOne() > 0 } == true
                val name = if (redacted) "" else player?.display_name ?: legacy?.displayName.orEmpty()
                // Freund: Profil-ID nur aus der Zuordnung des hochladenden Kontos, nie aus Slot-ID oder Lineup.
                val friend =
                    playerId?.value?.takeIf { !redacted && player?.kind == PlayerKind.FRIEND.name }?.let {
                        database.friendPlayerQueries.profileFor(profileId, it).executeAsOneOrNull()
                    }
                CloudParticipant(slot.value, team, index + 1, name, reference, friend)
            }
        }
    return CloudParticipants(id, people)
}

internal fun CloudSyncRepository.uploadPlayer(id: String): CloudPlayer? =
    database.playerQueries
        .selectById(id)
        .executeAsOneOrNull()
        ?.takeUnless {
            it.kind == PlayerKind.PLACEHOLDER.name || it.kind == PlayerKind.FRIEND.name
        }?.let {
            CloudPlayer(
                it.id,
                if (database.cloudMetadataQueries.sourceForSelf(id).executeAsOneOrNull() !=
                    null
                ) {
                    "self"
                } else {
                    "guest"
                },
                it.display_name,
                it.kind == PlayerKind.ARCHIVED.name,
            )
        }

internal fun CloudSyncRepository.uploadProjection(id: String): CloudProjection? =
    database.matchHeaderQueries.selectById(id).executeAsOneOrNull()?.let {
        CloudProjection(it.id, it.status.lowercase(), it.score_json, it.created_at_ms)
    }

/**
 * Pull-Seam für D1: neue fremde Metadaten atomar speichern. Vorhandene lokale/Peer-Bestände werden bewahrt.
 * Schreibrechte kommen ausdrücklich aus dem geprüften Serverzugriff, niemals aus Eventquellen.
 * Cursor muss D1 in derselben LocalData-Transaktion speichern.
 */
fun CloudSyncRepository.storeDownloadedMatch(
    record: MatchRecord,
    receivingProfileId: String,
    writable: Set<CloudMetadataKind> = emptySet(),
) {
    require(receivingProfileId.isNotBlank() && CloudMetadataKind.PLAYER !in writable)
    database.transaction {
        val id = record.id.value
        if (database.matchTombstoneQueries.countById(id).executeAsOne() > 0) return@transaction
        val existing = metadataState(CloudMetadataKind.HEADER, id)
        if (existing != null &&
            (existing.origin != CloudMetadataOrigin.CLOUD || existing.selected.revision > existing.uploadedRevision)
        ) {
            return@transaction
        }
        // Vorhandene Kopfwerte unverändert erhalten; D1 erweitert später die Versions-/Merge-Regeln.
        if (existing != null) return@transaction
        database.matchHeaderQueries.insertOrIgnore(
            id,
            record.sport.name,
            record.discipline.name,
            DataJson.encodeToString(MatchFormat.serializer(), record.format),
            DataJson.encodeToString(Lineup.serializer(), record.lineup),
            DataJson.encodeToString(MatchMeta.serializer(), record.meta),
            record.meta.surface?.name,
            record.meta.environment?.name,
            record.createdAtMs,
            record.updatedAtMs,
            record.status.name,
            record.previousMatchId?.value,
        )
        database.matchHeaderQueries.updateProgress(
            record.status.name,
            record.score.winner?.name,
            record.score.reason?.name,
            record.score.setsWonA.toLong(),
            record.score.setsWonB.toLong(),
            DataJson.encodeToString(ScoreSummary.serializer(), record.score),
            record.updatedAtMs,
            id,
        )
        listOf(CloudMetadataKind.HEADER, CloudMetadataKind.PARTICIPANTS, CloudMetadataKind.PROJECTION).forEach {
            database.cloudMetadataQueries.recordDownload(receivingProfileId.takeIf { _ -> it in writable }, it.name, id)
        }
    }
}

/** Fremde Spieler bleiben auch nach lokalen Namenskorrekturen vom Upload ausgeschlossen. */
fun CloudSyncRepository.storeDownloadedPlayer(
    player: LocalPlayer,
    receivingProfileId: String,
    owned: Boolean = false,
) {
    require(receivingProfileId.isNotBlank() && player.kind != PlayerKind.SELF)
    database.transaction {
        if (database.playerQueries.selectById(player.id.value).executeAsOneOrNull() != null) return@transaction
        database.playerQueries.insertOrIgnore(
            player.id.value,
            player.displayName,
            player.kind.name,
            player.createdAtMs,
            player.lastUsedAtMs,
        )
        database.cloudMetadataQueries.recordDownload(receivingProfileId.takeIf { owned }, "PLAYER", player.id.value)
    }
}

/**
 * Nur vom selben Konto materialisierte, vollständig saubere Cloud-Köpfe aktualisieren. Ein eigener LOCAL/PEER-Kopf,
 * dessen Quelle diesem Konto gehört, wird nie überschrieben: Lokal ist die Autorität, der Server hält nur die Kopie.
 * Nur abgeleitete Felder folgen einer strikt neueren Serverfassung ([adoptOwnDerived]). Er gilt nur dann als
 * materialisiert, wenn alle drei Teile per Ack bestätigt sind und der Server sie feldgenau trägt ([carriesOwnHeader]);
 * sonst wird ein älterer Beleg dieses Kontos entzogen. So dreht eine veraltete Seite nie zurück.
 */
internal fun CloudSyncRepository.materializePullMatch(
    record: MatchRecord,
    profileId: String,
    previousMaterializedVersion: Long?,
): Boolean {
    val id = record.id.value
    if (database.matchTombstoneQueries.countById(id).executeAsOne() > 0) return false
    val exists = database.matchHeaderQueries.countById(id).executeAsOne() > 0
    if (!exists) {
        storeDownloadedMatch(record, profileId, writable = emptySet())
        return true
    }
    val kinds = listOf(CloudMetadataKind.HEADER, CloudMetadataKind.PARTICIPANTS, CloudMetadataKind.PROJECTION)
    val states = kinds.map { metadataState(it, id) }
    val own =
        states.all {
            it != null && (it.origin == CloudMetadataOrigin.LOCAL || it.origin == CloudMetadataOrigin.PEER) &&
                it.sourceDeviceId != null && database.cloudUploadQueries.deviceOwner(it.sourceDeviceId).executeAsOneOrNull() == profileId
        }
    if (own) {
        val settled = states.all { it!!.selected.revision == it.uploadedRevision }
        if (settled) adoptOwnDerived(record, profileId, states.sumOf { it!!.uploadedRevision })
        val carried = settled && carriesOwnHeader(record)
        if (!carried) database.cloudPullQueries.materialized(null, profileId, "MATCH", id)
        return carried
    }
    if (previousMaterializedVersion == null ||
        database.cloudPullQueries.materializedOwner("MATCH", id).executeAsOneOrNull() != profileId ||
        states.any { it == null || it.origin != CloudMetadataOrigin.CLOUD || it.selected.revision != it.uploadedRevision }
    ) {
        return false
    }
    database.cloudPullQueries.updateDownloadedMatch(
        record.sport.name,
        record.discipline.name,
        DataJson.encodeToString(MatchFormat.serializer(), record.format),
        DataJson.encodeToString(Lineup.serializer(), record.lineup),
        DataJson.encodeToString(MatchMeta.serializer(), record.meta),
        record.meta.surface?.name,
        record.meta.environment?.name,
        record.status.name,
        record.previousMatchId?.value,
        record.updatedAtMs,
        record.score.winner?.name,
        record.score.reason?.name,
        record.score.setsWonA.toLong(),
        record.score.setsWonB.toLong(),
        DataJson.encodeToString(ScoreSummary.serializer(), record.score),
        id,
    )
    kinds.forEach { database.cloudMetadataQueries.recordDownload(null, it.name, id) }
    return true
}

/**
 * Teil 2, P2-3: Status, Summary und ein vom Server verworfener, lokal nicht mehr vorhandener Vorgänger ([livePredecessor])
 * eines vollständig bestätigten eigenen Kopfs folgen einer strikt neueren serverVersion mit mindestens so vielen Events;
 * Format, Lineup, Meta, Start und `updated_at_ms` bleiben lokal; der übernommene Stand gilt als hochgeladen.
 *
 * Vergleichsstand ist `own_version` am Snapshot: die serverVersion der letzten Seite, die den bestätigten eigenen Stand trug
 * oder übernommen wurde. Der U4-Ack liefert keine serverVersion; deshalb hält `own_revisions` die Summe der Upload-Revisionen
 * dieses Stands. Hat ein späterer Ack sie geändert, ist offen, welche serverVersion ihn trägt: Dann gilt eine Seite nur als
 * neuer, wenn sie den bestätigten Stand trägt, abgesehen von einem verworfenen Vorgänger oder einer Projektion mit mehr Events
 * (ein anderes eigenes Gerät zählte weiter). Ältere Fassungen drehen so nie zurück.
 */
private fun CloudSyncRepository.adoptOwnDerived(
    record: MatchRecord,
    profileId: String,
    revisions: Long,
) {
    val id = record.id.value
    val head = database.cloudPullQueries.ownHead(profileId, id).executeAsOneOrNull() ?: return
    val local = database.matchHeaderQueries.selectById(id).executeAsOneOrNull() ?: return
    if (head.server_version <= (head.own_version ?: 0)) return
    val score = localScore(local)
    // Runde 4, P3-A: nie weniger Events übernehmen, auch ohne Ack seit dem Beleg (nicht nur auf die Servermonotonie verlassen).
    if (record.score.eventCount < (score?.eventCount ?: 0)) return
    if (head.own_revisions != revisions) {
        val previous = record.previousMatchId == null || record.previousMatchId.value == local.previous_match_id
        val projection =
            (record.status.name == local.status && record.score == score) ||
                record.score.eventCount > (score?.eventCount ?: Int.MAX_VALUE)
        if (!carriesOwnFields(record, local) || !previous || !projection) return
    }
    database.cloudPullQueries.adoptOwnDerived(
        record.status.name,
        local.previous_match_id.takeIf { record.previousMatchId != null || livePredecessor(it) },
        record.score.winner?.name,
        record.score.reason?.name,
        record.score.setsWonA.toLong(),
        record.score.setsWonB.toLong(),
        DataJson.encodeToString(ScoreSummary.serializer(), record.score),
        id,
    )
    // Die Trigger heben bei Änderung die Revision; der Server trägt genau diesen Stand, also gilt er als hochgeladen.
    val uploaded =
        listOf(CloudMetadataKind.HEADER, CloudMetadataKind.PARTICIPANTS, CloudMetadataKind.PROJECTION).sumOf { kind ->
            val revision = checkNotNull(metadataState(kind, id)).selected.revision
            database.cloudMetadataQueries.acknowledge(revision, kind.name, id)
            revision
        }
    database.cloudPullQueries.setOwnHead(head.server_version, uploaded, profileId, id)
}

/**
 * Feldgenauer Vergleich des eigenen Kopfs mit dem, was der Server davon trägt (`PullMapping.memberRecord`):
 * Format (mit Sport und Disziplin), Meta (mit Belag und Umgebung), Vorgänger, Start (= `created_at_ms`), Slot-IDs je Team in
 * Positionsfolge, Status und Summary (mit Sieger, Grund, Sätzen). Nicht getragen und daher nie verglichen: `firstServer`,
 * `receiveRight`, `startEnds`, `updated_at_ms`, Teilnehmernamen und Verknüpfungen (00700 bereinigt sie serverseitig, ohne
 * Slots, Teams oder Positionen zu ändern). Ein verworfener Vorgänger oder eine weiter gezählte Projektion wird zuvor
 * übernommen ([adoptOwnDerived]); die Lesespur eigener Matches hängt vom Beleg nicht ab.
 */
private fun CloudSyncRepository.carriesOwnHeader(record: MatchRecord): Boolean {
    val local = database.matchHeaderQueries.selectById(record.id.value).executeAsOneOrNull() ?: return false
    return carriesOwnFields(record, local) &&
        (
            record.previousMatchId?.value == local.previous_match_id ||
                (record.previousMatchId == null && livePredecessor(local.previous_match_id))
        ) &&
        record.status.name == local.status &&
        record.score == localScore(local)
}

/**
 * Runde 4, P2-A: Der Server verwirft einen Vorgänger schon dann, wenn er ihn beim Anlegen nicht sieht, also auch, solange er
 * nur noch nicht hochgeladen ist; ein späteres Kopf-Update heilt per coalesce. Ein lokal vorhandener, nicht gelöschter
 * Vorgänger bleibt deshalb und gilt als getragen. Nur ein lokal fehlender oder gelöschter Vorgänger wird übernommen.
 */
private fun CloudSyncRepository.livePredecessor(id: String?): Boolean =
    id != null && database.matchHeaderQueries.countById(id).executeAsOne() > 0 &&
        database.matchTombstoneQueries.countById(id).executeAsOne() == 0L

/** Vom Server getragene, lokal autoritative Felder: Format, Meta, Start und Slot-IDs. */
private fun carriesOwnFields(
    record: MatchRecord,
    local: Match_header,
): Boolean {
    val lineup = DataJson.decodeFromString(Lineup.serializer(), local.lineup_json)
    return record.format == DataJson.decodeFromString(MatchFormat.serializer(), local.format_json) &&
        record.meta == DataJson.decodeFromString(MatchMeta.serializer(), local.meta_json) &&
        record.createdAtMs == local.created_at_ms &&
        record.lineup.a == lineup.a &&
        record.lineup.b == lineup.b
}

private fun localScore(local: Match_header): ScoreSummary? =
    runCatching { DataJson.decodeFromString(ScoreSummary.serializer(), local.score_json) }.getOrNull()

/** `owned` ist die einzige Schreibrechtsquelle; auch ein Cloud-Self bleibt lokal ein Gast. */
internal fun CloudSyncRepository.materializePullPlayer(
    downloaded: CloudPullPlayer,
    profileId: String,
    previousMaterializedVersion: Long?,
): Boolean =
    database.transactionWithResult {
        // Nur kleine Revisionsbelege, keine Match-/Eventpayloads. Alle Vorherstände liegen vor der einzigen Player-Mutation.
        val participants = mutableListOf<eu.advantly.data.db.PlayerDownloadParticipantPage>()
        var afterId: String? = null
        while (true) {
            val page = database.cloudMetadataQueries.playerDownloadParticipantPage(profileId, afterId, downloaded.id).executeAsList()
            if (page.isEmpty()) break
            participants.addAll(page)
            afterId = page.last().entity_id
        }
        val applied = applyPullPlayer(downloaded, profileId, previousMaterializedVersion)
        if (applied != PullPlayerApplication.UNCHANGED) {
            for (before in participants) {
                val after = metadataState(CloudMetadataKind.PARTICIPANTS, before.entity_id) ?: continue
                if (after.origin == CloudMetadataOrigin.CLOUD && after.sourceDeviceId == null &&
                    after.writerProfileId == before.writer_profile_id && after.uploadedRevision == before.revision &&
                    after.selected.revision > before.revision &&
                    (applied == PullPlayerApplication.MATERIALIZED || after.selected.revision - before.revision == 1L)
                ) {
                    database.cloudPullQueries.cleanDownloadedParticipants(
                        after.selected.revision,
                        before.entity_id,
                        before.writer_profile_id,
                        before.revision,
                        profileId,
                        requireNotNull(before.materialized_version),
                    )
                }
            }
        }
        applied == PullPlayerApplication.MATERIALIZED
    }

/** Ein reiner Rechtewechsel bestätigt weder den ausstehenden Player-Payload noch dessen Materialisierungsversion. */
private enum class PullPlayerApplication { UNCHANGED, AUTHORITY_ONLY, MATERIALIZED }

private fun CloudSyncRepository.applyPullPlayer(
    downloaded: CloudPullPlayer,
    profileId: String,
    previousMaterializedVersion: Long?,
): PullPlayerApplication {
    val player = downloaded.player
    require(player.kind != PlayerKind.SELF && downloaded.id == player.id.value)
    if (database.playerQueries.selectById(downloaded.id).executeAsOneOrNull() == null) {
        storeDownloadedPlayer(player, profileId, downloaded.owned)
        return PullPlayerApplication.MATERIALIZED
    }
    val state = metadataState(CloudMetadataKind.PLAYER, downloaded.id) ?: return PullPlayerApplication.UNCHANGED
    if (previousMaterializedVersion == null || state.origin != CloudMetadataOrigin.CLOUD ||
        database.cloudPullQueries.materializedOwner("PLAYER", downloaded.id).executeAsOneOrNull() != profileId ||
        database.playerQueries
            .selectById(downloaded.id)
            .executeAsOne()
            .kind == PlayerKind.SELF.name
    ) {
        return PullPlayerApplication.UNCHANGED
    }
    // Rechte folgen dem geprüften Serverstand auch dann, wenn ein lokaler Namenskonflikt offen bleibt.
    val writer = profileId.takeIf { downloaded.owned }
    database.cloudPullQueries.updatePullPlayerWriter(writer, downloaded.id)
    if (state.selected.revision != state.uploadedRevision) {
        return if (state.writerProfileId != writer) PullPlayerApplication.AUTHORITY_ONLY else PullPlayerApplication.UNCHANGED
    }
    database.playerQueries.rename(player.displayName, downloaded.id)
    database.playerQueries.updateKind(player.kind.name, downloaded.id)
    database.cloudMetadataQueries.recordDownload(profileId.takeIf { downloaded.owned }, "PLAYER", downloaded.id)
    return PullPlayerApplication.MATERIALIZED
}
