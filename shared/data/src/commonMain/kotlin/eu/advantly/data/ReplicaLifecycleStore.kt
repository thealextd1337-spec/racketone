package eu.advantly.data

import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchFormat
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.Player
import eu.advantly.core.scoring.MatchCreated
import eu.advantly.core.scoring.PlayerAssigned
import eu.advantly.data.db.AdvantlyDatabase
import eu.advantly.data.db.Replica_transfer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Persistenzgrenze. Der Pflicht-Fence beweist außen Auth-/Pairingautorität; hier gelten nur Dateninvarianten.
 * Die Integration muss zusätzlich die Manifestquelle und ihre Bindung an Kontext, Match und G/E verifizieren.
 * Autoritative kumulative Spieler-IDs wirken global, auch auf historische Self-IDs außerhalb der aktuellen Besetzung.
 * Eine aktive Sitzung allein beglaubigt keine frei gewählten Manifestinhalte.
 */
class ReplicaLifecycleStore internal constructor(
    private val database: AdvantlyDatabase,
    private val data: LocalData,
) {
    private val queries = database.replicaLifecycleQueries
    private val identity = Any()
    internal val activated: Boolean get() = data.settings.get("replica.activated") == "true"

    fun captureContext(): ReplicaContext {
        val scope = checkNotNull(data.accountIdentities.scope) { "Kein ausgewählter Owner-Scope" }
        return ReplicaContext(data.accountIdentities.profile ?: scope, scope, revision(), identity)
    }

    private fun revision() = data.settings.getLong("replica.contextRevision", 0)

    internal fun selectionChanged() {
        val previous = revision()
        check(previous < Long.MAX_VALUE)
        data.settings.putLong("replica.contextRevision", previous + 1)
    }

    internal fun validate(ctx: ReplicaContext) {
        check(
            ctx.store === identity && ctx.contextRevision == revision() &&
                ctx.scopeId == data.accountIdentities.scope &&
                ctx.accountKey == (data.accountIdentities.profile ?: data.accountIdentities.scope),
        ) { "Veralteter Replikatkontext" }
    }

    private fun <T> mutate(
        ctx: ReplicaContext,
        fence: () -> Unit,
        block: () -> T,
    ): T {
        fence()
        validate(ctx)
        return database.transactionWithResult {
            val result = block()
            fence()
            validate(ctx)
            result
        }
    }

    /** Einweg-Aktivierung erst mit vollständig verdrahteten D1-/V4-Aufrufern. */
    fun activate(
        ctx: ReplicaContext,
        fence: () -> Unit,
    ): Unit =
        mutate(ctx, fence) {
            if (!activated) normalizeLegacyAssignments(fence)
            data.settings.put("replica.activated", "true")
        }

    private fun normalizeLegacyAssignments(fence: () -> Unit) {
        data.settings.put("replica.redacting", "true")
        var after = 0L
        while (true) {
            fence()
            val rows = queries.assignmentPage(after).executeAsList()
            if (rows.isEmpty()) break
            for (row in rows) {
                if (row.player_id != null || row.player_json == null) continue
                val player = DataJson.decodeFromString(Player.serializer(), row.player_json)
                // Ohne Verzeichniseintrag bleibt die echte Legacyzeile migrationslesbar.
                if (queries.playerExists(player.id.value).executeAsOne() == 0L) continue
                queries.redactAssignment(player.id.value, row.assignment_rowid)
            }
            after = rows.last().assignment_rowid
        }
        data.settings.remove("replica.redacting")
    }

    fun isCloudSeen(matchId: String): Boolean = queries.cloudSeen(matchId).executeAsOne() != 0L

    fun recordCloudSeen(
        ctx: ReplicaContext,
        matchId: String,
        fence: () -> Unit,
    ): Unit =
        mutate(ctx, fence) {
            require(matchId.isNotBlank())
            queries.recordCloudSeen(matchId)
        }

    fun access(
        ctx: ReplicaContext,
        matchId: String,
    ): ReplicaAccess? {
        validate(ctx)
        return queries.access(ctx.accountKey, matchId).executeAsOneOrNull()?.let {
            ReplicaAccess(it.match_id, it.generation, ReplicaAccessLevel.valueOf(it.access), it.ready == 1L, it.applied_epoch)
        }
    }

    fun canReadMember(
        ctx: ReplicaContext,
        matchId: String,
    ): Boolean {
        validate(ctx)
        return readable(matchId)
    }

    internal fun readable(matchId: String): Boolean = !activated || queries.readable(matchId).executeAsOne() != 0L

    internal fun requireReadable(matchId: String) {
        check(readable(matchId)) { "Replikat ist nicht zum Mitgliedszugriff freigegeben" }
    }

    internal fun isRedacted(playerId: String): Boolean = queries.isRedacted(playerId).executeAsOne() != 0L

    internal fun readablePlayer(id: String): Boolean = !activated || queries.readablePlayer(id).executeAsOne() != 0L

    internal fun writablePlayer(id: String): Boolean =
        !activated ||
            queries.playerExists(id).executeAsOne() == 0L ||
            (readablePlayer(id) && queries.playerWriteAllowed(id).executeAsOne() > 0L)

    internal fun favoriteCandidates(
        sport: String?,
        limit: Long,
    ): List<String> {
        if (limit <= 0) return emptyList()
        val result = mutableListOf<String>()
        var afterTime: Long? = null
        var afterCount = 0L
        var afterId = ""
        while (result.size.toLong() < limit) {
            val rows = queries.favoriteCandidates(sport, afterTime, afterCount, afterId, 200).executeAsList()
            if (rows.isEmpty()) break
            for (row in rows) {
                if (readableFavorite(row.id)) result += row.id
                if (result.size.toLong() == limit) break
            }
            afterTime = rows.last().last_used_at_ms
            afterCount = rows.last().use_count
            afterId = rows.last().id
        }
        return result
    }

    internal fun requireHeader(matchId: String) {
        if (activated && (isCloudSeen(matchId) || access(captureContext(), matchId) != null)) requireReadable(matchId)
    }

    /**
     * Marker nur für einen Kopf über ungeklärtem Event-Bestand. Ein Kopf vor jeder Genesis bleibt unmarkiert und ohne
     * Quelle unlesbar; die Genesis muss dann [requireAppend] bestehen (W3, Anfrage 1: Kopf der eigenen Uhr vor ihrer Genesis).
     * Sein Inhalt ist bis dahin ungeprüft; [requireAppend] nimmt die Genesis nur an, wenn Format, Lineup, Metadaten und
     * Erzeugungszeit des Kopfs aus ihr stammen.
     */
    internal fun noteUnresolvedImport(matchId: String) {
        if (!activated || readable(matchId) || database.matchEventQueries.countForMatch(matchId).executeAsOne() == 0L) return
        val ctx = captureContext()
        if (access(ctx, matchId) == null) queries.putAccess(ctx.accountKey, matchId, null, ReplicaAccessLevel.UNKNOWN.name)
    }

    internal fun readableFavorite(id: String): Boolean {
        if (!activated) return true
        val row = queries.favoriteIdentities(id).executeAsOneOrNull() ?: return false
        val ids = (DataJson.parseToJsonElement(row.side_a) as JsonArray) + (DataJson.parseToJsonElement(row.side_b) as JsonArray)
        return ids.all { readablePlayer(it.jsonPrimitive.content) }
    }

    /**
     * Schreibspur eigener Matches (Vertrag P1-1): Die per UID dem aktuellen Konto gebundene Quelle darf an eigenen Matches
     * unabhängig von ready schreiben (Access fehlt oder MEMBER beim aktuellen G, während eines Neuscans auch beim zuletzt
     * vollständigen G); Events, Projektion und Kopf aus ihr offenbaren nichts, was die Sitzung nicht schon hält. Der
     * Verification-Hold der Wiederherstellung ist hier nicht sichtbar (Host-Gate): Pull und Session dürfen vor abgeschlossener
     * Restore-Prüfung nicht laufen, der Beweis stammt aus `cloud_device_owner`.
     */
    private fun ownWritable(
        matchId: String,
        deviceId: String?,
    ): Boolean = queries.ownWritable(matchId, deviceId).executeAsOne() != 0L

    /**
     * D-1: Rückstand der eigenen Quelle [deviceId] an einem Match mit offener Schreibspur. Zähler folgen ihr immer, Inhalte
     * ([content]) nur ohne ausstehende Redaktion, damit kein noch unbereinigtes Event an eine Gegenstelle geht.
     */
    internal fun ownBacklog(
        matchId: String,
        deviceId: String,
        content: Boolean,
    ): Boolean = ownWritable(matchId, deviceId) && (!content || queries.redactionSettled(matchId).executeAsOne() != 0L)

    /** Lese- oder Schreibspur. Für Zähler und Bestätigungsflags, nie für Inhalte (Teil 2, P1-1). */
    internal fun writable(matchId: String): Boolean = !activated || readable(matchId) || ownWritable(matchId, null)

    internal fun requireWritable(matchId: String) {
        check(writable(matchId)) { "Replikat ist nicht zum Schreiben freigegeben" }
    }

    /**
     * `true` nur nach dem Genesis-Durchlauf einer eigenen Quelle (lokal im aktuellen Scope oder per `cloud_device_owner` an das
     * aktuelle Konto gebunden, z. B. die eigene Uhr); Folgeevents im Batch sind dann an genau diese Quelle gebunden.
     */
    internal fun requireAppend(event: StoredEvent): Boolean {
        if (!activated || readable(event.matchId) || ownWritable(event.matchId, event.deviceId)) return false
        // Nur die belegte Erzeugung einer eigenen Quelle darf ohne Cloudstand beginnen, kein fremder Punkt.
        check(!isCloudSeen(event.matchId) && queries.hasLifecycle(event.matchId).executeAsOne() == 0L)
        if (database.matchEventQueries.countForMatch(event.matchId).executeAsOne() != 0L) {
            val existing = database.matchEventQueries.eventById(event.id).executeAsOneOrNull()
            check(
                existing != null && existing.match_id == event.matchId && existing.device_id == event.deviceId &&
                    existing.payload == event.payload,
            )
        }
        val source = database.localIdentityQueries.source(event.deviceId).executeAsOneOrNull()
        val created = event.toScoreEventOrNull()?.payload as? MatchCreated
        val creator = created?.let { (it.createdBy ?: it.lineup.a.first()).value }
        val owner = source?.let { database.cloudUploadQueries.deviceOwner(it.device_id).executeAsOneOrNull() }
        val ownCreator =
            source != null && creator != null && (
                creator == source.self_id ||
                    (owner != null && database.cloudMetadataQueries.ownedSelfAlias(creator, owner).executeAsOne() > 0)
            )
        // Eigene Quelle: lokal im aktuellen Scope oder per UID an das aktuelle Konto gebunden (z. B. die eigene Uhr am Handy).
        val ownSource =
            source != null &&
                (
                    (source.local == 1L && source.scope_id == data.accountIdentities.scope) ||
                        (owner != null && owner == (data.accountIdentities.profile ?: data.accountIdentities.scope))
                )
        check(ownSource && ownCreator) {
            "Ungeklärter Import benötigt autoritativen Lifecycle-Abgleich"
        }
        // Ein Kopf vor der Genesis (MatchMeta zuerst) wird nur angenommen, wenn er aus ihr stammt; sonst würde fremder
        // Kopfinhalt (Lineup, Ort) mit der eigenen Genesis lesbar. Ehrliche Erzeuger legen ihn genau so an (MatchSession).
        val header = database.matchHeaderQueries.selectById(event.matchId).executeAsOneOrNull()
        check(
            header == null ||
                (
                    created != null &&
                        DataJson.decodeFromString(MatchFormat.serializer(), header.format_json) == created.config &&
                        DataJson.decodeFromString(Lineup.serializer(), header.lineup_json) == created.lineup &&
                        DataJson.decodeFromString(MatchMeta.serializer(), header.meta_json) == created.meta &&
                        header.created_at_ms == event.wallClockMs
                ),
        ) { "Vorhandener Kopf passt nicht zur Genesis" }
        return true
    }

    fun applyAccess(
        ctx: ReplicaContext,
        matchId: String,
        generation: Long?,
        access: ReplicaAccessLevel,
        fence: () -> Unit,
    ): TransitionResult =
        mutate(ctx, fence) {
            require(matchId.isNotBlank() && (generation == null || generation >= 0))
            require(generation != null || access == ReplicaAccessLevel.UNKNOWN)
            val old = access(ctx, matchId)
            if (old != null && old.generation != null && (generation == null || generation < old.generation)) {
                return@mutate TransitionResult.IGNORED
            }
            if (old != null && old.generation == generation) {
                check(old.access == access) { "Widersprüchlicher Zugriff bei gleicher Generation" }
                return@mutate TransitionResult.UNCHANGED
            }
            if (generation != null) queries.recordCloudSeen(matchId)
            queries.putAccess(ctx.accountKey, matchId, generation, access.name)
            clearTransfer(ctx.accountKey, matchId)
            // Beim Member-Abgleich bleibt der eigene Materialisierungsbeleg erhalten; ready ist bereits zurückgesetzt.
            // Ein alter Member-Snapshot ist niemals eine zulässige Feedkarte. D1 liefert deren bereinigte Fassung neu.
            if (old?.access != ReplicaAccessLevel.MEMBER || access != ReplicaAccessLevel.MEMBER) {
                queries.removePrivateSnapshot(ctx.accountKey, matchId)
            }
            queries.removeWork(ctx.accountKey, matchId)
            if (generation != null) queries.queueAccess(matchId, generation, access.name, old?.redactionEpoch ?: 0, ctx.accountKey)
            if (access != ReplicaAccessLevel.MEMBER) purgeIfUnreferenced(matchId)
            TransitionResult.APPLIED
        }

    fun markReady(
        ctx: ReplicaContext,
        matchId: String,
        generation: Long,
        redactionEpoch: Long,
        fence: () -> Unit,
    ): Unit =
        mutate(ctx, fence) {
            val state = checkNotNull(access(ctx, matchId))
            check(state.generation == generation && state.access == ReplicaAccessLevel.MEMBER)
            check(state.redactionEpoch == redactionEpoch)
            val transfer = queries.transferForMatch(ctx.accountKey, matchId).executeAsOneOrNull()
            check(transfer == null || (transfer.completed != 0L && transfer.epoch <= redactionEpoch))
            queries.ready(ctx.accountKey, matchId, generation)
        }

    /** Sperrt Mitgliedslesen bis zum nächsten Readiness-Beleg; Materialisierung, Zugriff und Transfer bleiben. */
    fun markNotReady(
        ctx: ReplicaContext,
        matchId: String,
        fence: () -> Unit,
    ): TransitionResult =
        mutate(ctx, fence) {
            if (access(ctx, matchId)?.ready != true) return@mutate TransitionResult.UNCHANGED
            queries.notReady(ctx.accountKey, matchId)
            TransitionResult.APPLIED
        }

    /** G-Wechsel: alle Zugriffe des Kontos unterhalb von G verlieren ready; Materialisierung, Zugriff und Transfer bleiben. */
    fun markNotReadyBefore(
        ctx: ReplicaContext,
        generation: Long,
        fence: () -> Unit,
    ): Unit = mutate(ctx, fence) { queries.notReadyBefore(ctx.accountKey, generation) }

    private fun clearTransfer(
        account: String,
        match: String,
    ) {
        queries.deletePagesForMatch(account, match)
        queries.deleteTransfer(account, match)
    }

    private fun purgeIfUnreferenced(matchId: String) {
        if (queries.retain(matchId).executeAsOne()) return
        queries.removeReceipts(matchId)
        queries.removeEventCursors(matchId)
        queries.invalidateMaterialization(matchId)
        queries.invalidateWork(matchId)
        database.matchAssignmentQueries.clearMatch(matchId)
        database.matchEventQueries.deleteForMatch(matchId)
        database.matchHeaderQueries.deleteById(matchId)
    }

    fun beginRedaction(
        ctx: ReplicaContext,
        transfer: RedactionTransfer,
        fence: () -> Unit,
    ): RedactionCursor =
        mutate(ctx, fence) {
            require(transfer.id.isNotBlank() && transfer.epoch > 0 && transfer.highWatermark >= 0)
            val state = checkNotNull(access(ctx, transfer.matchId))
            check(state.generation == transfer.generation && state.access == ReplicaAccessLevel.MEMBER)
            check(transfer.epoch >= state.redactionEpoch)
            val existing = queries.transfer(transfer.id).executeAsOneOrNull()
            if (existing != null) {
                check(existing.completed >= 0)
                check(
                    existing.account_key == ctx.accountKey && existing.scope_id == ctx.scopeId &&
                        existing.match_id == transfer.matchId && existing.generation == transfer.generation &&
                        existing.epoch == transfer.epoch && existing.high_watermark == transfer.highWatermark,
                )
                return@mutate RedactionCursor(existing.after_player_id, existing.after_server_seq)
            }
            val previous = queries.transferForMatch(ctx.accountKey, transfer.matchId).executeAsOneOrNull()
            check(previous == null || previous.completed == -1L || previous.epoch < transfer.epoch)
            clearTransfer(ctx.accountKey, transfer.matchId)
            queries.recordCloudSeen(transfer.matchId)
            queries.notReady(ctx.accountKey, transfer.matchId)
            queries.insertTransfer(
                transfer.id,
                ctx.accountKey,
                ctx.scopeId,
                transfer.matchId,
                transfer.generation,
                transfer.epoch,
                transfer.highWatermark,
            )
            RedactionCursor(null, 0)
        }

    private fun transfer(
        ctx: ReplicaContext,
        id: String,
    ): Replica_transfer {
        val t = checkNotNull(queries.transfer(id).executeAsOneOrNull())
        check(t.completed >= 0 && t.account_key == ctx.accountKey && t.scope_id == ctx.scopeId)
        val a = checkNotNull(access(ctx, t.match_id))
        check(a.generation == t.generation && a.access == ReplicaAccessLevel.MEMBER && a.redactionEpoch <= t.epoch)
        return t
    }

    /** Aufrufer halten weiterhin ihren Auth-/Pairing-Fence; dieser Lesezugriff prüft den Store-Kontext. */
    fun redactionProgress(
        ctx: ReplicaContext,
        matchId: String,
    ): RedactionProgress? {
        validate(ctx)
        val row = queries.transferForMatch(ctx.accountKey, matchId).executeAsOneOrNull() ?: return null
        if (row.completed < 0) return null
        val current = transfer(ctx, row.id)
        return RedactionProgress(
            RedactionTransfer(current.id, current.match_id, current.generation, current.epoch, current.high_watermark),
            RedactionCursor(current.after_player_id, current.after_server_seq),
            current.next_index,
            current.ids_done == 1L,
            current.events_done == 1L,
            current.completed == 1L,
        )
    }

    /** Nur vollständig angewandte Manifeste dürfen weitergegeben werden; höchstens eine gespeicherte Seite je Abruf. */
    fun exportRedactionPage(
        ctx: ReplicaContext,
        transferId: String,
        index: Long,
        fence: () -> Unit = {},
    ): RedactionPage? =
        mutate(ctx, fence) {
            require(index >= 0)
            ensureRedactedEventIndex(fence)
            val current = transfer(ctx, transferId)
            check(current.completed == 1L) { "Unvollständige Redaktion darf nicht exportiert werden" }
            if (index >= current.next_index) return@mutate null
            DataJson.decodeFromString(
                RedactionPage.serializer(),
                queries.page(transferId, index).executeAsOne(),
            )
        }

    fun stageRedaction(
        ctx: ReplicaContext,
        transferId: String,
        page: RedactionPage,
        fence: () -> Unit,
    ): Unit =
        mutate(ctx, fence) {
            val t = transfer(ctx, transferId)
            require(page.playerIds.size <= 200 && page.events.size <= 500 && page.index >= 0)
            val json = DataJson.encodeToString(RedactionPage.serializer(), page)
            require(json.encodeToByteArray().size <= 64 * 1024)
            val previous = queries.page(transferId, page.index).executeAsOneOrNull()
            if (previous != null) {
                check(previous == json) { "Abweichende Wiederholung einer Redaktionsseite" }
                return@mutate
            }
            check(t.completed == 0L && page.index == t.next_index)
            require(page.from == RedactionCursor(t.after_player_id, t.after_server_seq))
            require(page.next.afterServerSeq in page.from.afterServerSeq..t.high_watermark)
            require(page.playerIds.all { it.isNotBlank() } && page.playerIds.zipWithNext().all { (a, b) -> a < b })
            require(page.playerIds.firstOrNull()?.let { page.from.afterPlayerId == null || it > page.from.afterPlayerId } != false)
            require(page.next.afterPlayerId == (page.playerIds.lastOrNull() ?: page.from.afterPlayerId))
            require(page.events.zipWithNext().all { (a, b) -> a.serverSeq < b.serverSeq })
            require(
                page.events.all {
                    it.serverSeq > page.from.afterServerSeq && it.serverSeq <= t.high_watermark &&
                        it.event.matchId == t.match_id
                },
            )
            require(page.next.afterServerSeq == (page.events.lastOrNull()?.serverSeq ?: page.from.afterServerSeq))
            require(t.ids_done == 0L || (page.idsDone && page.playerIds.isEmpty()))
            require(t.events_done == 0L || (page.eventsDone && page.events.isEmpty()))
            require((page.idsDone && page.eventsDone) || page.next != page.from)
            queries.insertPage(transferId, page.index, json)
            queries.advanceTransfer(
                page.next.afterPlayerId,
                page.next.afterServerSeq,
                if (page.idsDone) 1 else 0,
                if (page.eventsDone) 1 else 0,
                transferId,
            )
        }

    fun completeRedaction(
        ctx: ReplicaContext,
        transferId: String,
        fence: () -> Unit,
    ): Unit =
        mutate(ctx, fence) {
            ensureRedactedEventIndex(fence)
            val t = transfer(ctx, transferId)
            if (t.completed == 1L) return@mutate
            check(t.ids_done == 1L && t.events_done == 1L)

            fun pages(block: (RedactionPage) -> Unit) {
                var index = 0L
                while (index < t.next_index) {
                    fence()
                    validate(ctx)
                    block(DataJson.decodeFromString(RedactionPage.serializer(), queries.page(t.id, index).executeAsOne()))
                    index++
                }
            }
            pages { page -> page.playerIds.forEach { queries.redactPlayer(ctx.accountKey, t.match_id, it, t.epoch) } }
            pages { page -> page.events.forEach { applyRedactedEvent(it.event) } }
            // Globale Sperrmenge wirkt auch auf andere Konten und bereits bestätigte Altlogs.
            scrubStoredNames(ctx, fence)
            data.settings.put("replica.redacting", "true")
            data.rebuildAssignments(MatchId(t.match_id))
            data.settings.remove("replica.redacting")
            queries.setEpoch(ctx.accountKey, t.match_id, t.epoch)
            queries.finishTransfer(t.id)
            queries.queueRedaction(t.epoch, ctx.accountKey, t.match_id)
        }

    private fun applyRedactedEvent(event: StoredEvent) {
        validateRedactedEvent(event)
        requireRedactedSequence(event)
        // Die verifizierte Seite autorisiert dieses Event, nicht eine globale Player-ID.
        // Ohne Rohzeile wird nur der genaue Beleg gespeichert: kein Import, Receipt oder Readiness-Erfolg.
        val proof = queries.redactedEvent(event.id).executeAsOneOrNull()
        if (proof == null) {
            queries.rememberRedactedEvent(
                event.id,
                DataJson.encodeToString(StoredEvent.serializer(), event.copy(confirmed = false)),
                event.matchId,
                event.deviceId,
                event.deviceSeq,
            )
        } else {
            requireRedactedEventMatch(event, DataJson.decodeFromString(StoredEvent.serializer(), proof))
        }
        queries.redactEventPayload(event.payload, event.id)
    }

    private fun validateRedactedEvent(event: StoredEvent) {
        check(
            event.id.isNotBlank() && event.matchId.isNotBlank() && event.deviceId.isNotBlank() &&
                event.deviceSeq > 0 && event.lamport >= 0 && event.parentId?.isNotBlank() != false &&
                event.type in setOf("", "PlayerAssigned"),
        )
        val replacement = DataJson.parseToJsonElement(event.payload).jsonObject
        val assignment = event.toScoreEvent().payload as? PlayerAssigned ?: error("Kein Legacy-Zuweisungsevent")
        val player = assignment.player
        check(
            assignment.assignmentVersion == 1 && player != null &&
                player.id.value.isNotBlank() && player.displayName == DELETED_NAME &&
                assignment.slot.value.isNotBlank() && assignment.assignedBy.value.isNotBlank() &&
                assignment.creatorAlias?.value?.isNotBlank() != false,
        )
        val row = database.cloudUploadQueries.eventById(event.id).executeAsOneOrNull()
        check(
            row != null ||
                database.matchEventQueries.countBySequence(event.matchId, event.deviceId, event.deviceSeq).executeAsOne() == 0L,
        ) { "Redaktionsbeleg kollidiert mit einer vorhandenen Eventsequenz" }
        if (row != null) {
            check(
                event.matchId == row.match_id && event.deviceId == row.device_id && event.deviceSeq == row.device_seq &&
                    event.lamport == row.lamport && event.wallClockMs == row.wall_clock_ms && event.parentId == row.parent_id &&
                    row.type in setOf("", "PlayerAssigned"),
            )
            val original = DataJson.parseToJsonElement(row.payload).jsonObject
            check(original["type"]?.jsonPrimitive?.content == "PlayerAssigned")
            val person = original["player"] as? JsonObject ?: error("Kein Legacy-Spielerpayload")
            check(!person["id"]?.jsonPrimitive?.content.isNullOrBlank())
            val allowed = JsonObject(original + ("player" to JsonObject(person + ("displayName" to JsonPrimitive(DELETED_NAME)))))
            check(replacement == allowed) { "Redaktion verändert unveränderliche Eventfelder" }
        }
    }

    /**
     * Schema-9-Belege bleiben unverändert. Der abgeleitete Index wird innerhalb der aufrufenden
     * Transaktion vollständig geprüft, bevor eine Sequenzentscheidung getroffen wird. Seiten sind
     * begrenzt; einmalig werden alle Altbelege geprüft. Konflikte bleiben erhalten und brechen ab.
     */
    private fun ensureRedactedEventIndex(fence: () -> Unit) {
        while (true) {
            fence()
            val rows = queries.unindexedRedactedEvents().executeAsList()
            if (rows.isEmpty()) return
            for (row in rows) {
                fence()
                val event = DataJson.decodeFromString(StoredEvent.serializer(), row.event_json)
                check(
                    row.event_id == event.id &&
                        (row.match_id == null || row.match_id == event.matchId) &&
                        (row.device_id == null || row.device_id == event.deviceId) &&
                        (row.device_seq == null || row.device_seq == event.deviceSeq),
                ) { "Abweichende Koordinaten eines alten Redaktionsbelegs" }
                validateRedactedEvent(event)
                requireRedactedSequence(event)
                queries.indexRedactedEvent(event.matchId, event.deviceId, event.deviceSeq, event.id)
            }
        }
    }

    private fun requireRedactedSequence(event: StoredEvent) {
        val id = queries.redactedEventBySequence(event.matchId, event.deviceId, event.deviceSeq).executeAsOneOrNull()
        check(id == null || id == event.id) { "Gerätesequenz gehört zu einem anderen redigierten Event" }
    }

    private fun requireRedactedEventMatch(
        event: StoredEvent,
        proof: StoredEvent,
    ) {
        check(event.type in setOf("", "PlayerAssigned") && proof.type in setOf("", "PlayerAssigned"))
        check(
            event.copy(payload = proof.payload, confirmed = false, type = "PlayerAssigned") ==
                proof.copy(confirmed = false, type = "PlayerAssigned"),
        ) {
            "Redaktionsbeleg gehört zu anderen unveränderlichen Eventfeldern"
        }
        val payload = DataJson.parseToJsonElement(event.payload).jsonObject
        check(payload["type"]?.jsonPrimitive?.content == "PlayerAssigned")
        val player = payload["player"] as? JsonObject ?: error("Kein Legacy-Spielerpayload")
        val clean = JsonObject(payload + ("player" to JsonObject(player + ("displayName" to JsonPrimitive(DELETED_NAME)))))
        check(clean == DataJson.parseToJsonElement(proof.payload)) { "Abweichender Payload eines redigierten Events" }
    }

    internal fun sanitizeLegacyEvent(
        event: StoredEvent,
        fence: () -> Unit = {},
    ): StoredEvent =
        database.transactionWithResult {
            ensureRedactedEventIndex(fence)
            requireRedactedSequence(event)
            val result = sanitizeIndexedLegacyEvent(event)
            fence()
            result
        }

    private fun sanitizeIndexedLegacyEvent(event: StoredEvent): StoredEvent {
        queries.redactedEvent(event.id).executeAsOneOrNull()?.let { json ->
            val proof = DataJson.decodeFromString(StoredEvent.serializer(), json)
            requireRedactedEventMatch(event, proof)
            return event.copy(payload = proof.payload)
        }
        val payload = runCatching { DataJson.parseToJsonElement(event.payload).jsonObject }.getOrNull() ?: return event
        if (payload["type"]?.jsonPrimitive?.content != "PlayerAssigned") return event
        val player = payload["player"] as? JsonObject ?: return event
        val id = player["id"]?.jsonPrimitive?.content ?: return event
        if (!isRedacted(id)) return event
        return event.copy(
            payload = JsonObject(payload + ("player" to JsonObject(player + ("displayName" to JsonPrimitive(DELETED_NAME))))).toString(),
        )
    }

    private fun scrubStoredNames(
        ctx: ReplicaContext,
        fence: () -> Unit,
    ) {
        var after: String? = null
        while (true) {
            fence()
            validate(ctx)
            val rows = queries.identityPage(after).executeAsList()
            if (rows.isEmpty()) break
            rows.forEach { row ->
                val event =
                    StoredEvent(
                        row.id,
                        row.match_id,
                        row.device_id,
                        row.device_seq,
                        row.lamport,
                        row.payload,
                        row.wall_clock_ms,
                        row.parent_id,
                        row.type,
                    )
                val clean = sanitizeLegacyEvent(event, fence)
                if (event.payload != clean.payload) queries.redactEventPayload(clean.payload, row.id)
            }
            after = rows.last().id
        }
        // Alle positiven Belege zuerst sichern, unabhängig von der Reihenfolge der Alias-Snapshots.
        var playerSnapshotRow = 0L
        while (true) {
            fence()
            validate(ctx)
            val rows = queries.redactedPlayerSnapshotPage(playerSnapshotRow).executeAsList()
            if (rows.isEmpty()) break
            for (row in rows) {
                val player = DataJson.parseToJsonElement(row.snapshot_json) as? JsonObject ?: continue
                val id = (player["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                val profile = (player["profileId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                // Nur Self ist laut Server-Constraint an den Profileigentümer gebunden; Gastlinks beweisen keine Kontolöschung.
                val kind = (player["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (id == row.entity_id && kind == "self" && !profile.isNullOrBlank()) queries.rememberRedactedProfile(id, profile)
            }
            playerSnapshotRow = rows.last().snapshot_rowid
        }
        // Strukturierte IDs entscheiden; kein Vergleich oder Suchen nach Personennamen.
        var snapshotRow = 0L
        while (true) {
            fence()
            validate(ctx)
            val rows = queries.snapshotPage(snapshotRow).executeAsList()
            if (rows.isEmpty()) break
            rows.forEach { row ->
                val clean = sanitizeJson(DataJson.parseToJsonElement(row.snapshot_json))
                if (clean.toString() != row.snapshot_json) queries.redactSnapshot(clean.toString(), row.snapshot_rowid)
            }
            snapshotRow = rows.last().snapshot_rowid
        }
        var assignmentRow = 0L
        data.settings.put("replica.redacting", "true")
        while (true) {
            fence()
            validate(ctx)
            val rows = queries.assignmentPage(assignmentRow).executeAsList()
            if (rows.isEmpty()) break
            rows.forEach { row ->
                val player = row.player_json?.let { DataJson.decodeFromString(Player.serializer(), it) }
                if (player != null && isRedacted(player.id.value)) queries.redactAssignment(player.id.value, row.assignment_rowid)
            }
            assignmentRow = rows.last().assignment_rowid
        }
        after = null
        while (true) {
            fence()
            validate(ctx)
            val rows = queries.redactedDirectoryPage(after).executeAsList()
            if (rows.isEmpty()) break
            rows.forEach { row ->
                queries.clearPlayerName(row)
            }
            after = rows.last()
        }
        data.settings.remove("replica.redacting")
        after = null
        while (true) {
            fence()
            validate(ctx)
            val rows = queries.favoritePage(after).executeAsList()
            if (rows.isEmpty()) break
            rows.forEach { row ->
                val ids = (DataJson.parseToJsonElement(row.side_a) as JsonArray) + (DataJson.parseToJsonElement(row.side_b) as JsonArray)
                if (ids.any { isRedacted(it.jsonPrimitive.content) }) queries.redactFavorite(row.id)
            }
            after = rows.last().id
        }
    }

    internal fun sanitizeJson(value: JsonElement): JsonElement =
        when (value) {
            is JsonArray -> {
                JsonArray(value.map(::sanitizeJson))
            }

            is JsonObject -> {
                val redacted =
                    listOf("id", "playerId", "player_id").any { key ->
                        (value[key] as? JsonPrimitive)?.contentOrNull?.let(::isRedacted) == true
                    } || (value["profileId"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let {
                        queries.isRedactedProfile(it).executeAsOne()
                    } == true
                JsonObject(
                    value.mapValues { (key, child) ->
                        if (redacted && key in setOf("displayName", "display_name", "alias")) JsonPrimitive("") else sanitizeJson(child)
                    },
                )
            }

            else -> {
                value
            }
        }

    fun registerPeer(
        ctx: ReplicaContext,
        peerId: String,
        fence: () -> Unit,
    ): Unit =
        mutate(ctx, fence) {
            require(peerId.isNotBlank())
            queries.registerPeer(ctx.accountKey, peerId)
            queries.seedPeer(peerId, ctx.accountKey)
        }

    fun pendingDeliveries(
        ctx: ReplicaContext,
        peerId: String,
        afterId: String?,
        limit: Int,
    ): DeliveryPage {
        validate(ctx)
        require(limit in 1..200)
        val rows = queries.pending(ctx.accountKey, peerId, afterId, limit.toLong() + 1).executeAsList()
        val entries =
            rows.take(limit).map {
                ReplicaDelivery(
                    it.match_id,
                    it.match_id,
                    it.generation,
                    ReplicaAccessLevel.valueOf(it.access),
                    it.redaction_epoch,
                    it.access_ack == 1L,
                    it.redaction_ack == 1L,
                )
            }
        return DeliveryPage(entries, entries.lastOrNull()?.id, rows.size > limit)
    }

    fun ackAccess(
        ctx: ReplicaContext,
        peerId: String,
        matchId: String,
        generation: Long,
        access: ReplicaAccessLevel,
        fence: () -> Unit,
    ): Unit = mutate(ctx, fence) { queries.ackAccess(ctx.accountKey, peerId, matchId, generation, access.name) }

    fun ackRedaction(
        ctx: ReplicaContext,
        peerId: String,
        matchId: String,
        generation: Long,
        epoch: Long,
        fence: () -> Unit,
    ): Unit =
        mutate(ctx, fence) {
            val current = checkNotNull(access(ctx, matchId))
            check(epoch <= current.redactionEpoch)
            queries.ackRedaction(ctx.accountKey, peerId, matchId, generation, epoch)
        }

    private companion object {
        const val DELETED_NAME = "Gelöschter Spieler"
    }
}
