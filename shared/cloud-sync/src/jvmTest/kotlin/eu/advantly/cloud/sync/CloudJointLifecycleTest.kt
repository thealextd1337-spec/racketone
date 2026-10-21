package eu.advantly.cloud.sync

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.EventId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.Placeholders
import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId
import eu.advantly.core.scoring.EventPayload
import eu.advantly.core.scoring.MatchCreated
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.core.scoring.PlayerAssigned
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.ScoreEvent
import eu.advantly.data.CloudMetadataKind
import eu.advantly.data.CloudPullEntityKind
import eu.advantly.data.LocalData
import eu.advantly.data.ReplicaAccessLevel
import eu.advantly.data.ScoreSummary
import eu.advantly.data.StoredEvent
import eu.advantly.data.createInMemoryJvmDriver
import eu.advantly.data.metadataState
import eu.advantly.data.toStoredEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val JOINT_RESTART = """{"code":"40001","message":"restart_required","details":null,"hint":null}"""
private const val DELETED_NAME = "Gelöschter Spieler"

/**
 * 00700 `guard_push_lifecycle`: Mit Beleg aktuelles G und für jedes dem Server bekannte referenzierte Match dessen aktuelles E;
 * zusätzliche Einträge sind erlaubt. Ohne Beleg erhält jedes bekannte Match mit E > 0 den Fehler 40001, bereits vorhandene
 * Event-IDs zählen dann nicht (reiner No-op). [epoch] liefert null für unbekannte Matches. Die Namensprüfung ohne Beleg
 * (`v_affected`) bildet dieser Mock nicht nach.
 */
internal fun restartRequired00700(
    batch: PushBatch,
    generation: Long,
    epoch: (String) -> Long?,
    existingEvent: (String) -> Boolean,
): Boolean {
    val lifecycle = batch.lifecycle
    val referenced = batch.matches.map { it.id } + batch.events.filter { lifecycle != null || !existingEvent(it.id) }.map { it.matchId }
    val known = referenced.toSet().mapNotNull { id -> epoch(id)?.let { id to it } }
    return if (lifecycle == null) {
        known.any { it.second > 0 }
    } else {
        lifecycle.generation != generation || known.any { (id, e) -> lifecycle.redactionEpochs[id]?.toLong() != e }
    }
}

/**
 * Gemeinsamer Mock-Server für D1 und U4: Katalog, Events, Wasserstände, Redaktion (00600), Readiness (00800) und Push
 * mit den Regeln aus 00700 und 00900. Jede Matchänderung zieht wie `touch_row` eine neue globale `server_version`.
 */
private class JointServer {
    class Match(
        val id: String,
        val members: MutableSet<String>,
    ) {
        var version = 0L
        var epoch = 0L
        val log = mutableListOf<PulledEvent>()
        val redactedIds = sortedSetOf<String>()

        /** Zuletzt hochgeladener Kopf, Teilnehmer und Projektion (jeweils nur gesendete Teile); null = generischer Fremdkopf. */
        var uploaded: MatchDto? = null
    }

    /** Wie `pull_matches`: hochgeladene Köpfe kommen zurück, Teilnehmernamen redigierter Identitäten bereinigt (00700). */
    private fun pulled(match: Match): PulledMatch {
        val base = pullMatch(match.id, match.version).copy(redactionEpoch = match.epoch.toInt())
        val dto = match.uploaded ?: return base
        val projection = dto.projection
        return base.copy(
            sport = dto.sport ?: base.sport,
            discipline = dto.discipline ?: base.discipline,
            format = dto.format ?: base.format,
            meta = dto.meta ?: base.meta,
            startedAt = dto.startedAt,
            previousMatchId = dto.previousMatchId,
            participants =
                dto.participants?.map {
                    val name = if (it.playerId in redactedPlayers) DELETED_NAME else it.displayName
                    PulledParticipant(it.slotId, it.team, it.position, it.playerId, displayName = name)
                } ?: base.participants,
            status = projection?.status ?: base.status,
            winner = projection?.winner,
            resultReason = projection?.resultReason,
            endedAt = projection?.endedAt,
            summary = projection?.summary,
            projectionEventCount = projection?.eventCount ?: 0,
        )
    }

    private var clock = 0L
    val generations = mutableMapOf<String, Long>()
    val matches = mutableMapOf<String, Match>()

    /** Serverseitig redigierte Identitäten; 00700 kanonisiert ihre Namen in neuen Events, 00900 erhöht dann E. */
    val redactedPlayers = mutableSetOf<String>()
    val pushes = mutableListOf<PushBatch>()
    var restarts = 0

    /** Läuft vor jedem Pull-RPC, z. B. für ein fremdes Live-Match. */
    var onPullRequest: () -> Unit = {}
    var hold: CompletableDeferred<Unit>? = null
    var sent = CompletableDeferred<Unit>()

    fun generation(profile: String) = generations.getOrPut(profile) { 1 }

    fun touch(match: Match) {
        match.version = ++clock
    }

    fun foreign(
        id: String,
        events: Int,
        vararg members: String = arrayOf("alice"),
    ): Match =
        Match(id, members.toMutableSet()).also { m ->
            matches[id] = m
            (1..events).forEach { appendRemote(m) }
            touch(m)
        }

    fun appendRemote(match: Match) {
        val seq = match.log.size + 1L
        match.log += pullEvent(seq, match.id, seq).copy(id = "${match.id}-r$seq")
    }

    /** Redaktion außerhalb des Uploads (z. B. Kontolöschung): E steigt, `server_version` ebenfalls. */
    fun raiseEpoch(id: String) {
        val match = matches.getValue(id)
        match.epoch++
        touch(match)
    }

    fun install(f: PullFixture) {
        fun profile() = requireNotNull(f.credentials).profileId
        f.metadata = { cursor, limit ->
            onPullRequest()
            val visible = matches.values.filter { profile() in it.members && it.version > cursor }.sortedBy { it.version }
            val page = visible.take(limit)
            val more = visible.size > limit
            PullResponse(
                page.map(::pulled),
                cursor = if (more) page.last().version else maxOf(cursor, clock),
                hasMore = more,
                visibilityEpoch = generation(profile()),
            )
        }
        f.events = { match, after, limit ->
            onPullRequest()
            matches[match]
                ?.log
                .orEmpty()
                .filter { it.serverSeq > after }
                .take(limit)
        }
        f.serverLog = { matches[it]?.log.orEmpty() }
        f.serverVersion = { matches.getValue(it).version }
        f.watermarks = { match ->
            onPullRequest()
            matches[match]
                ?.log
                .orEmpty()
                .groupBy { it.deviceId }
                .map { (device, rows) -> EventWatermark(device, rows.maxOf { it.deviceSeq }, rows.size.toLong()) }
        }

        // 00600/00800: falsches G oder E heißt restart_required, nie Rechteverlust.
        fun stale(body: JsonObject): Match? {
            val match = matches.getValue(body.getValue("p_match_id").jsonPrimitive.content)
            val current = body.long("p_generation") == generation(profile()) && body.long("p_redaction_epoch") == match.epoch
            return match.takeIf { current }
        }
        f.redaction = redaction@{ body ->
            onPullRequest()
            val match = stale(body) ?: return@redaction JOINT_RESTART
            val high = body["p_high_watermark"]?.jsonPrimitive?.content?.toLong() ?: match.log.size.toLong()
            val afterPlayer = body["p_after_player_id"]?.jsonPrimitive?.content
            val afterSeq = body.long("p_after_server_seq")
            val ids = match.redactedIds.filter { afterPlayer == null || it > afterPlayer }
            val events = match.log.filter { it.redacted && it.serverSeq > afterSeq && it.serverSeq <= high }
            val pageIds = ids.take(body.long("p_player_limit").toInt())
            val pageEvents = events.take(body.long("p_event_limit").toInt())
            CloudJson.encodeToString(
                CloudRedactionPage.serializer(),
                CloudRedactionPage(
                    match.id,
                    generation(profile()),
                    match.epoch.toInt(),
                    high,
                    pageIds,
                    pageEvents,
                    pageIds.lastOrNull() ?: afterPlayer,
                    pageEvents.lastOrNull()?.serverSeq ?: afterSeq,
                    ids.size > pageIds.size,
                    events.size > pageEvents.size,
                ),
            )
        }
        f.readiness = readiness@{ body ->
            onPullRequest()
            stale(body) ?: return@readiness JOINT_RESTART
            f.servedReadiness(body)
        }
        f.push = { body ->
            val batch = CloudJson.decodeFromJsonElement(PushBatch.serializer(), body.getValue("p_batch"))
            pushes += batch
            // Der Server prüft G/E vor jedem Write; eine spätere lokale Änderung trifft den Commit nicht mehr.
            val restart =
                restartRequired00700(batch, generation(profile()), { matches[it]?.epoch }) { id ->
                    matches.values.any { m -> m.log.any { it.id == id } }
                }
            val response = if (restart) null else commit(profile(), batch)
            if (restart) restarts++
            hold?.let {
                sent.complete(Unit)
                it.await()
            }
            response?.let { CloudJson.encodeToString(PushResponse.serializer(), it) } ?: JOINT_RESTART
        }
    }

    private fun commit(
        profile: String,
        batch: PushBatch,
    ): PushResponse {
        fun match(id: String) = matches.getOrPut(id) { Match(id, mutableSetOf(profile)).also(::touch) }
        batch.matches.forEach { dto ->
            val m = match(dto.id)
            val old = m.uploaded
            m.uploaded =
                if (old == null) {
                    dto
                } else {
                    old.copy(
                        sport = dto.sport ?: old.sport,
                        discipline = dto.discipline ?: old.discipline,
                        format = dto.format ?: old.format,
                        meta = dto.meta ?: old.meta,
                        startedAt = dto.startedAt ?: old.startedAt,
                        previousMatchId = dto.previousMatchId ?: old.previousMatchId,
                        participants = dto.participants ?: old.participants,
                        projection = dto.projection ?: old.projection,
                    )
                }
            touch(m)
        }
        val canonicalized = mutableSetOf<String>()
        val events =
            batch.events.map { row ->
                val m = match(row.matchId)
                if (m.log.any { it.id == row.id }) return@map ItemResult(row.id, "duplicate")
                // 00700 guard_redacted_names: Namen redigierter Identitäten werden still durch den Löschnamen ersetzt.
                val person = row.payload["player"] as? JsonObject
                val redact = row.type == "PlayerAssigned" && person?.get("id")?.jsonPrimitive?.content in redactedPlayers
                val payload =
                    if (redact) {
                        JsonObject(
                            row.payload + ("player" to JsonObject(requireNotNull(person) + ("displayName" to JsonPrimitive(DELETED_NAME)))),
                        )
                    } else {
                        row.payload
                    }
                if (redact) canonicalized += m.id
                m.log +=
                    PulledEvent(
                        row.id,
                        row.matchId,
                        row.deviceId,
                        row.deviceSeq,
                        row.lamport,
                        row.wallClockMs,
                        row.parentId,
                        row.type,
                        payload,
                        m.log.size + 1L,
                        redact,
                    )
                ItemResult(row.id, "accepted")
            }
        // 00900: E steigt einmal je Transaktion und Match; das UPDATE auf matches zieht eine neue server_version.
        canonicalized.forEach { raiseEpoch(it) }
        return PushResponse(
            devices = batch.devices.map { ItemResult(it.id, "ok") },
            players = batch.players.map { ItemResult(it.id, "ok") },
            matches = batch.matches.map { ItemResult(it.id, "updated", projection = it.projection?.let { "applied" }) },
            events = events,
        )
    }
}

/** EINE LocalData (aktivierter Replikat-Store), EIN Mock-Server: D1 über [PullFixture], U4 über dieselbe Sitzung. */
private class JointFixture(
    driver: SqlDriver = createInMemoryJvmDriver(),
    /** Neustart: derselbe Server und dieselbe Datenbank, aber neue LocalData, neuer Runner und neue Bindung. */
    val server: JointServer = JointServer(),
) : AutoCloseable {
    val pull =
        PullFixture(driver, select = {
            players.ensureSelf("Alex", 0) { PlayerId("me") }
            accountIdentities.initialize(SOURCE, "me", "PHONE")
            account("alice")
        })
    val data: LocalData get() = pull.data

    init {
        server.install(pull)
        val session = data.cloudUploads.activate("alice")
        data.cloudUploads.bindOwnedDevices(session, setOf(SOURCE))
        data.cloudUploads.deactivate()
    }

    fun LocalData.account(profile: String) = accountIdentities.select("legacy:$SOURCE", profile, 0, "PHONE") { error("keine neue ID") }

    suspend fun switchTo(profile: String) {
        data.account(profile)
        pull.credentials = CloudCredentials(profile, "session-$profile", "token-$profile")
        pull.gate.completeTransition(pull.gate.stop())
        pull.bind()
    }

    fun upload(maxEvents: Int = 500) = CloudUploadRunner(pull.backend, { 1L }, 1, maxEvents)

    /** Nur die Katalogphase einer Runde: eine Seite, danach Budgetende vor jeder Eventarbeit. */
    suspend fun catalogOnly(): CloudPullState = CloudPullRunner(pull.backend, data, maxRpcsPerRun = 1).pullNow()

    /** Läufe mit großzügigem Budget, bis die laufende Runde abgeschlossen ist. */
    suspend fun finishRound(profile: String = "alice"): CloudPullState {
        repeat(20) {
            val state = CloudPullRunner(pull.backend, data, maxRpcsPerRun = 100, maxPagesPerRun = 100, maxEventJobsPerRun = 100).pullNow()
            if (!data.cloudPulls.progress(profile).roundActive) return state
        }
        error("Runde endet nicht")
    }

    fun access(match: String) = data.replicas.access(data.replicas.captureContext(), match)

    fun readable(match: String) = pull.readable(match)

    fun snapshotEpoch(match: String) = data.cloudPulls.snapshot("alice", CloudPullEntityKind.MATCH, match)?.redactionObserved

    /** Payload an der Lesesperre vorbei, um auch einen gesperrten Bestand prüfen zu können. */
    fun storedPayload(id: String): String? =
        pull.driver
            .executeQuery(null, "SELECT payload FROM match_event WHERE id = '$id'", { c ->
                QueryResult.Value(if (c.next().value) c.getString(0) else null)
            }, 0)
            .value

    fun pending() = data.cloud.pendingCount()

    fun ready(match: String) = access(match)?.ready == true

    /** Kopfwerte an der Lesesperre vorbei. */
    fun headerColumn(
        match: String,
        column: String,
    ): String? =
        pull.driver
            .executeQuery(null, "SELECT $column FROM match_header WHERE id = '$match'", { c ->
                QueryResult.Value(if (c.next().value) c.getString(0) else null)
            }, 0)
            .value

    /** (revision, uploaded_revision, origin) einer Metadateneinheit. */
    fun metadata(
        kind: String,
        id: String,
    ): Triple<Long, Long, String>? =
        data.cloud
            .metadataState(
                CloudMetadataKind.valueOf(kind),
                id,
            )?.let { Triple(it.selected.revision, it.uploadedRevision, it.origin.name) }

    /** Zeilen in Events, Spielerverzeichnis, Matchkopf und Zuweisungen, die [text] enthalten (an der Lesesperre vorbei). */
    fun localRowsContaining(text: String): Long =
        pull.driver
            .executeQuery(
                null,
                """
                SELECT (SELECT count(*) FROM match_event WHERE instr(payload, '$text') > 0)
                    + (SELECT count(*) FROM player WHERE instr(display_name, '$text') > 0)
                    + (SELECT count(*) FROM match_header WHERE instr(lineup_json || meta_json, '$text') > 0)
                    + (SELECT count(*) FROM match_assignment WHERE instr(coalesce(player_json, ''), '$text') > 0)
            """,
                { c ->
                    c.next()
                    QueryResult.Value(requireNotNull(c.getLong(0)))
                },
                0,
            ).value

    fun serverEvents(match: String) = server.matches[match]?.log.orEmpty()

    fun createMatch(
        id: String,
        opponent: PlayerId = PlayerId("guest"),
    ) {
        val format = Presets.TENNIS_SINGLES_BO3.format()
        val lineup = Lineup(listOf(PlayerId("me")), listOf(opponent), PlayerId("me"))
        if (data.players.get(PlayerId("guest")) == null) data.players.addGuest(PlayerId("guest"), "Bea", 0)
        data.events.append(
            ScoreEvent(EventId("$id-e1"), MatchId(id), DeviceId(SOURCE), 1, 1, 1, null, MatchCreated(format, lineup)).toStoredEvent(),
        )
        data.matches.insertIfAbsent(MatchId(id), format, lineup, MatchMeta(), 1)
        data.matches.updateProgress(MatchId(id), MatchStatus.IN_PROGRESS, ScoreSummary(eventCount = 1), 1)
    }

    fun point(
        match: String,
        seq: Long,
    ) = data.events.append(
        StoredEvent("$match-e$seq", match, SOURCE, seq, seq, """{"type":"PointWon","side":"A"}""", type = "PointWon"),
    )

    fun progress(
        match: String,
        events: Int,
    ) = data.matches.updateProgress(MatchId(match), MatchStatus.IN_PROGRESS, ScoreSummary(eventCount = events), 100L + events)

    /** Zeilen in match_assignment mit Spieler-ID oder Text [text] (an der Lesesperre vorbei). */
    fun assignmentRows(text: String): Long =
        pull.driver
            .executeQuery(
                null,
                "SELECT count(*) FROM match_assignment WHERE player_id = '$text' OR instr(coalesce(player_json, ''), '$text') > 0",
                { c ->
                    c.next()
                    QueryResult.Value(requireNotNull(c.getLong(0)))
                },
                0,
            ).value

    /** Legacy-Zuweisung mit Klarnamen auf [slot]; nur ein Platzhalter-Slot ergibt eine echte match_assignment-Zeile. */
    fun assign(
        match: String,
        seq: Long,
        player: String,
        name: String,
        slot: PlayerId = PlayerId("guest"),
    ) = data.events.append(
        StoredEvent(
            "$match-e$seq",
            match,
            SOURCE,
            seq,
            seq,
            CloudJson.encodeToString(
                EventPayload.serializer(),
                PlayerAssigned(slot, Player(PlayerId(player), name), PlayerId("me")),
            ),
            type = "PlayerAssigned",
        ),
    )

    override fun close() {
        server.hold?.complete(Unit)
        pull.close()
    }
}

/** Integrationspunkt B: D1 (Pull, Redaktion, Readiness) und U4 (Upload-Beleg, Neustartsperre) gemeinsam. */
class CloudJointLifecycleTest {
    @Test
    fun aServerEpochWithoutAccessRowIsCaughtUpByD1AndU4UploadsWithoutPermanentLock(): Unit =
        runBlocking {
            JointFixture().use { f ->
                f.pull.bind()
                assertEquals(CloudPullOutcome.COMPLETE, f.finishRound().outcome)
                f.createMatch(OWN)
                assertEquals(CloudUploadOutcome.DRAINED, f.upload().uploadNow().outcome)
                assertEquals(
                    0L,
                    f.server.matches
                        .getValue(OWN)
                        .epoch,
                )
                // Server-E > 0 ohne lokale Access-Zeile: U4 sendet E = 0 und erhält 40001 ohne Writes.
                f.server.raiseEpoch(OWN)
                f.point(OWN, 2)
                val runner = f.upload(maxEvents = 1)
                val restart = runner.uploadNow()
                assertEquals("restart_required", restart.failure?.reason)
                assertEquals(1, f.server.restarts)
                assertNull(f.access(OWN))
                // Ein unbeteiligtes Match wartet während der Sperre mit.
                f.createMatch(OTHER)
                assertEquals(CloudUploadOutcome.BLOCKED, runner.uploadNow().outcome)
                assertEquals(1, f.server.restarts)
                // D1 legt die Access-Zeile im Katalog an; U4 ist frei, sperrt aber nur dieses Match bis zum Transfer.
                f.catalogOnly()
                val fresh = assertNotNull(f.access(OWN))
                assertEquals(ReplicaAccessLevel.MEMBER, fresh.access)
                assertEquals(0, fresh.redactionEpoch)
                assertEquals(1, f.snapshotEpoch(OWN))
                assertFalse(f.readable(OWN))
                repeat(3) { runner.uploadNow() }
                assertEquals(1, f.server.restarts)
                assertTrue(f.serverEvents(OTHER).isNotEmpty())
                assertTrue(f.serverEvents(OWN).none { it.id == "$OWN-e2" })
                // Transfer (G, E = 1, H) abgeschlossen: E lokal angewandt, danach lädt U4 mit E = 1 hoch.
                f.finishRound()
                assertEquals(1, f.access(OWN)!!.redactionEpoch)
                val done = (1..5).map { runner.uploadNow().outcome }
                assertEquals(CloudUploadOutcome.DRAINED, done.last())
                assertEquals(1, f.server.restarts)
                assertTrue(f.serverEvents(OWN).any { it.id == "$OWN-e2" })
                assertEquals(
                    1,
                    f.server.pushes
                        .last { b -> b.events.any { it.id == "$OWN-e2" } }
                        .lifecycle
                        ?.redactionEpochs
                        ?.get(OWN),
                )
                assertEquals(0, f.pending())
                // Der vollständig bestätigte eigene Kopf gilt als materialisiert: ready bei (G, E = 1), lesbar und beschreibbar.
                assertTrue(f.access(OWN)!!.ready)
                assertTrue(f.readable(OWN))
                f.point(OWN, 3)
                assertEquals(CloudUploadOutcome.DRAINED, runner.uploadNow().outcome)
                assertTrue(f.serverEvents(OWN).any { it.id == "$OWN-e3" })
            }
        }

    @Test
    fun bServerCanonicalizesOwnUploadThenRestartTransferAndReadinessCleanTheLocalEvent(): Unit =
        runBlocking {
            JointFixture().use { f ->
                f.pull.bind()
                f.finishRound()
                f.server.redactedPlayers += KLARA
                f.createMatch(OWN, SLOT)
                f.assign(OWN, 2, KLARA, "Klara", SLOT)
                f.point(OWN, 3)
                // Echte Zuweisung: ohne Verzeichniseintrag trägt die Legacy-Zeile den Klarnamen.
                assertEquals(1, f.assignmentRows("Klara"))
                val runner = f.upload(maxEvents = 2)
                // Batch 1: Genesis und Zuweisung; der Server ersetzt den Namen und erhöht E von 0 auf 1 (00900).
                runner.uploadNow()
                val stored = f.serverEvents(OWN).single { it.id == "$OWN-e2" }
                assertTrue(stored.redacted)
                assertEquals(
                    1L,
                    f.server.matches
                        .getValue(OWN)
                        .epoch,
                )
                assertTrue("Klara" in f.storedPayload("$OWN-e2")!!)
                // Batch 2 mit E = 0: 40001 ohne Writes.
                while (f.server.restarts == 0 && f.server.pushes.size < 10) runner.uploadNow()
                assertEquals(1, f.server.restarts)
                assertEquals(
                    0,
                    f.server.pushes
                        .last()
                        .lifecycle
                        ?.redactionEpochs
                        ?.get(OWN),
                )
                assertTrue(f.serverEvents(OWN).none { it.id == "$OWN-e3" })
                f.createMatch(OTHER)
                // Katalog: E = 1 sichtbar, Sperre frei, das Match einzeln blockiert; kein zweiter 40001.
                f.catalogOnly()
                assertEquals(1, f.snapshotEpoch(OWN))
                repeat(3) { runner.uploadNow() }
                assertEquals(1, f.server.restarts)
                assertTrue(f.serverEvents(OTHER).isNotEmpty())
                // Pull, Transfer und Readiness: das lokale Event ist bereinigt, danach lädt U4 weiter.
                f.finishRound()
                assertEquals(1, f.access(OWN)!!.redactionEpoch)
                val local = assertNotNull(f.storedPayload("$OWN-e2"))
                assertFalse("Klara" in local)
                assertTrue(DELETED_NAME in local)
                repeat(3) { runner.uploadNow() }
                assertEquals(1, f.server.restarts)
                assertTrue(f.serverEvents(OWN).any { it.id == "$OWN-e3" })
                assertEquals(
                    1,
                    f.server.pushes
                        .last { b -> b.events.any { it.id == "$OWN-e3" } }
                        .lifecycle
                        ?.redactionEpochs
                        ?.get(OWN),
                )
                assertEquals(0, f.pending())
                assertEquals(1, f.serverEvents(OWN).count { it.id == "$OWN-e2" })
                // Eigenes Match nach 00900: ready bei E = 1, lokal nur der bereinigte Name, nie der Klarname.
                f.finishRound()
                assertTrue(f.ready(OWN))
                assertTrue(f.readable(OWN))
                assertEquals(0, f.localRowsContaining("Klara"))
                assertEquals(1, f.assignmentRows(KLARA))
                assertEquals(1, f.assignmentRows(DELETED_NAME))
                assertEquals(2, f.localRowsContaining(DELETED_NAME))
                assertTrue(f.serverEvents(OWN).none { "Klara" in it.payload.toString() })
            }
        }

    @Test
    fun cGenerationChangeDuringARunningUploadDiscardsTheAckAndResendsIdempotently(): Unit =
        runBlocking {
            JointFixture().use { f ->
                f.pull.bind()
                f.server.foreign(FOREIGN, 2)
                f.finishRound()
                assertTrue(f.readable(FOREIGN))
                f.createMatch(OWN)
                val runner = f.upload()
                f.server.hold = CompletableDeferred()
                val upload = async { runner.uploadNow() }
                withTimeout(5_000) { f.server.sent.await() }
                // Der Server hat mit G = 1 committet; jetzt wechselt G, D1 sieht G' vor dem Ack.
                f.server.generations["alice"] = 2
                f.catalogOnly()
                assertEquals(
                    2,
                    f.data.cloudPulls
                        .progress("alice")
                        .targetEpoch,
                )
                f.server.hold!!.complete(Unit)
                f.server.hold = null
                val stale = upload.await()
                assertEquals(CloudUploadOutcome.FAILED, stale.outcome)
                assertEquals("cloud_lifecycle_changed", stale.failure?.reason)
                assertTrue(f.pending() > 0)
                assertEquals(CloudUploadOutcome.BLOCKED, runner.uploadNow().outcome)
                assertEquals(1, f.server.pushes.size)
                // G' abgeschlossen: Neuversand mit G = 2 wird idempotent bestätigt, nichts doppelt gespeichert.
                f.finishRound()
                assertEquals(CloudUploadOutcome.DRAINED, runner.uploadNow().outcome)
                assertEquals(
                    2,
                    f.server.pushes
                        .last()
                        .lifecycle
                        ?.generation,
                )
                assertEquals(0, f.server.restarts)
                assertEquals(0, f.pending())
                assertEquals(f.serverEvents(OWN).map { it.id }.distinct(), f.serverEvents(OWN).map { it.id })
                assertEquals(2, f.access(FOREIGN)!!.generation)
                assertTrue(f.readable(FOREIGN))
            }
        }

    @Test
    fun dAccountSwitchAToBToABetweenPullAndUploadKeepsEachAccountsProof(): Unit =
        runBlocking {
            JointFixture().use { f ->
                f.server.foreign(FOREIGN, 2)
                f.pull.bind()
                f.finishRound()
                assertTrue(f.readable(FOREIGN))
                f.createMatch(OWN)
                val selected = f.pull.backend.captureUpload()
                assertEquals(CloudPushLifecycle(1, mapOf(OWN to 0)), selected.batch.lifecycle)
                f.switchTo("bob")
                // Bob sieht weder Alices Replikat noch ihre Auswahl; er hat noch kein G.
                assertFalse(f.readable(FOREIGN))
                assertEquals(CloudUploadOutcome.DRAINED, f.upload().uploadNow().outcome)
                assertTrue(f.server.pushes.isEmpty())
                f.switchTo("alice")
                // Der vor dem Wechsel gewählte Snapshot bleibt ungültig, auch nach der Rückkehr zu A.
                assertTrue(runCatching { f.pull.backend.requireCurrentUpload(selected) }.isFailure)
                assertTrue(runCatching { f.pull.backend.pushSelected(selected, selected.batch) }.isFailure)
                assertTrue(f.server.pushes.isEmpty())
                assertTrue(f.readable(FOREIGN))
                assertEquals(CloudUploadOutcome.DRAINED, f.upload().uploadNow().outcome)
                assertEquals(
                    CloudPushLifecycle(1, mapOf(OWN to 0)),
                    f.server.pushes
                        .single()
                        .lifecycle,
                )
                assertEquals(0, f.server.restarts)
                assertEquals(0, f.pending())
                assertEquals(1, f.access(FOREIGN)!!.generation)
            }
        }

    @Test
    fun eForeignLiveMatchWithEndlessEventsDoesNotLockU4Permanently(): Unit =
        runBlocking {
            JointFixture().use { f ->
                f.pull.bind()
                val live = f.server.foreign(FOREIGN, 3)
                f.finishRound()
                assertTrue(f.readable(FOREIGN))
                f.createMatch(OWN)
                assertEquals(CloudUploadOutcome.DRAINED, f.upload().uploadNow().outcome)
                f.server.raiseEpoch(OWN)
                f.point(OWN, 2)
                val runner = f.upload()
                assertEquals("restart_required", runner.uploadNow().failure?.reason)
                // Jede Serveranfrage bringt ein neues Live-Event samt neuer Matchversion (Projektion).
                f.server.onPullRequest = {
                    f.server.appendRemote(live)
                    f.server.touch(live)
                }
                var uploadedAfter: Int? = null
                var roundsFinished = 0
                for (run in 1..20) {
                    CloudPullRunner(f.pull.backend, f.data).pullNow()
                    if (!f.data.cloudPulls
                            .progress("alice")
                            .roundActive
                    ) {
                        roundsFinished++
                    }
                    runner.uploadNow()
                    if (uploadedAfter == null && f.serverEvents(OWN).any { it.id == "$OWN-e2" }) uploadedAfter = run
                }
                assertNotNull(uploadedAfter, "U4 blieb 20 D1-Läufe gesperrt")
                assertEquals(1, f.server.restarts)
                assertEquals(0, f.pending())
                // Endliche Runden: festes H je Runde, das Live-Match verhindert den Rundenabschluss nicht.
                assertTrue(roundsFinished > 0, "keine D1-Runde endet trotz Live-Match")
                assertTrue(live.log.size > 20)
                assertTrue(f.readable(FOREIGN))
                assertTrue(f.data.events.count(FOREIGN) >= 3)
            }
        }

    @Test
    fun fFirstUploadBeforeTheFirstPullSendsNoProofThenD1AndU4Continue(): Unit =
        runBlocking {
            JointFixture().use { f ->
                f.pull.bind()
                f.createMatch(OWN)
                assertTrue(f.readable(OWN))
                assertEquals(CloudUploadOutcome.DRAINED, f.upload().uploadNow().outcome)
                assertNull(
                    f.server.pushes
                        .single()
                        .lifecycle,
                )
                assertTrue(f.serverEvents(OWN).isNotEmpty())
                f.server.foreign(FOREIGN, 1)
                f.finishRound()
                assertEquals(
                    1,
                    f.data.cloudPulls
                        .progress("alice")
                        .visibilityEpoch,
                )
                val own = assertNotNull(f.access(OWN))
                assertEquals(ReplicaAccessLevel.MEMBER, own.access)
                assertTrue(f.readable(FOREIGN))
                f.createMatch(OTHER)
                assertEquals(CloudUploadOutcome.DRAINED, f.upload().uploadNow().outcome)
                assertEquals(
                    CloudPushLifecycle(1, mapOf(OTHER to 0)),
                    f.server.pushes
                        .last()
                        .lifecycle,
                )
                assertEquals(0, f.server.restarts)
                assertEquals(0, f.pending())
                // Das eigene, schon hochgeladene Match bleibt nach dem ersten Pull lesbar (bestätigter Kopf, eigene Events).
                assertTrue(f.access(OWN)!!.ready)
                assertTrue(f.readable(OWN))
            }
        }

    /** P1 Integrationspunkt B: eigenes Match lokal anlegen und zählen, hochladen, Pull, weiter zählen und hochladen. */
    @Test
    fun gOwnLocalMatchStaysReadableWritableAndUploadableAcrossUploadAndPull(): Unit =
        runBlocking {
            JointFixture().use { f ->
                f.pull.bind()
                f.finishRound()
                f.createMatch(OWN)
                f.point(OWN, 2)
                assertTrue(f.readable(OWN))
                assertEquals(CloudUploadOutcome.DRAINED, f.upload().uploadNow().outcome)
                assertEquals(CloudPullOutcome.COMPLETE, f.finishRound().outcome)
                assertTrue(f.ready(OWN))
                assertTrue(f.readable(OWN))
                // Kopfherkunft bleibt eigen (Upload-Recht), Kopf vollständig bestätigt.
                assertEquals("LOCAL", f.metadata("HEADER", OWN)!!.third)
                listOf("HEADER", "PARTICIPANTS", "PROJECTION").forEach { kind ->
                    val (revision, uploaded, _) = f.metadata(kind, OWN)!!
                    assertEquals(revision, uploaded, kind)
                }
                f.point(OWN, 3)
                assertTrue(f.readable(OWN))
                assertEquals(CloudUploadOutcome.DRAINED, f.upload().uploadNow().outcome)
                assertTrue(f.serverEvents(OWN).any { it.id == "$OWN-e3" })
                assertEquals(CloudPullOutcome.COMPLETE, f.finishRound().outcome)
                assertTrue(f.ready(OWN))
                // Ein neues lokales Event an einem bereiten Match ist weder Lücke noch Abdeckung.
                f.point(OWN, 4)
                assertEquals(CloudPullOutcome.COMPLETE, f.finishRound().outcome)
                assertTrue(f.ready(OWN))
                assertTrue(f.readable(OWN))
                assertEquals(1, f.pending())
                assertEquals(CloudUploadOutcome.DRAINED, f.upload().uploadNow().outcome)
                assertEquals(CloudPullOutcome.COMPLETE, f.finishRound().outcome)
                assertTrue(f.ready(OWN))
                assertEquals(listOf(1L, 2L, 3L, 4L), f.serverEvents(OWN).map { it.deviceSeq })
                assertEquals(4, f.data.events.count(OWN))
                assertEquals(0, f.pending())
                assertEquals(0, f.server.restarts)
            }
        }

    @Test
    fun hUnconfirmedLocalHeaderChangeIsNotOverwrittenByTheCloudHeader(): Unit =
        runBlocking {
            JointFixture().use { f ->
                f.pull.bind()
                f.finishRound()
                f.createMatch(OWN)
                assertEquals(CloudUploadOutcome.DRAINED, f.upload().uploadNow().outcome)
                // Neuer lokaler Kopf, noch nicht hochgeladen; der Server kennt den alten.
                f.data.matches.updateMeta(MatchId(OWN), MatchMeta(venue = "Halle Nord"))
                val (revision, uploaded, _) = f.metadata("HEADER", OWN)!!
                assertTrue(revision > uploaded)
                f.finishRound()
                assertTrue("Halle Nord" in f.headerColumn(OWN, "meta_json")!!)
                assertEquals(Triple(revision, uploaded, "LOCAL"), f.metadata("HEADER", OWN))
                // Unbestätigter Kopf ist kein Snapshotbeweis.
                assertFalse(f.ready(OWN))
                // Nach dem Ack liefert der Server genau diesen Kopf; erst jetzt Übernahme und ready.
                assertEquals(CloudUploadOutcome.DRAINED, f.upload().uploadNow().outcome)
                assertEquals(CloudPullOutcome.COMPLETE, f.finishRound().outcome)
                assertTrue(f.ready(OWN))
                assertTrue(f.readable(OWN))
                assertTrue("Halle Nord" in f.headerColumn(OWN, "meta_json")!!)
                assertEquals("LOCAL", f.metadata("HEADER", OWN)!!.third)
            }
        }

    @Test
    fun iForeignDeviceUploadPlusOwnConfirmedEventsGiveExactCoverage(): Unit =
        runBlocking {
            JointFixture().use { f ->
                f.pull.bind()
                f.finishRound()
                f.createMatch(OWN)
                f.point(OWN, 2)
                assertEquals(CloudUploadOutcome.DRAINED, f.upload().uploadNow().outcome)
                // Ein weiteres Gerät (z. B. Mitzähler) lädt in dasselbe Match hoch.
                val match = f.server.matches.getValue(OWN)
                match.log += pullEvent(1, OWN, match.log.size + 1L).copy(id = uuid("remote-1"))
                f.server.touch(match)
                assertEquals(CloudPullOutcome.COMPLETE, f.finishRound().outcome)
                assertTrue(f.ready(OWN))
                assertTrue(f.readable(OWN))
                assertEquals(3, f.data.events.count(OWN))
            }
        }

    @Test
    fun jForgedOwnEventWithWrongIdNeverCountsAsCoverage(): Unit =
        runBlocking {
            JointFixture().use { f ->
                f.pull.bind()
                f.finishRound()
                f.createMatch(OWN)
                assertEquals(CloudUploadOutcome.DRAINED, f.upload().uploadNow().outcome)
                // Server hat (OWN, SOURCE, 2) unter anderer ID; lokal steht (OWN, SOURCE, 2) mit gefälschtem Ack.
                val match = f.server.matches.getValue(OWN)
                match.log += pullEvent(2, OWN, match.log.size + 1L).copy(id = uuid("server-2"), deviceId = SOURCE)
                f.server.touch(match)
                f.point(OWN, 2)
                f.data.cloud.acknowledge(setOf("$OWN-e2"))
                assertEquals(0, f.pending())
                f.finishRound()
                assertFalse(f.ready(OWN))
                // Lesespur: Abdeckung betrifft am eigenen Match die Integrität (ready), nicht die Lesbarkeit.
                assertTrue(f.readable(OWN))
                assertTrue(f.data.cloudPulls.failedEventJobs("alice") > 0)
            }
        }

    @Test
    fun kOwnMatchReadinessSurvivesRestartAndKeepsScoring(): Unit =
        runBlocking {
            val first = JointFixture()
            first.pull.bind()
            first.finishRound()
            first.createMatch(OWN)
            assertEquals(CloudUploadOutcome.DRAINED, first.upload().uploadNow().outcome)
            first.finishRound()
            assertTrue(first.ready(OWN))
            // Neustart: neue LocalData, neuer Runner, neue Bindung auf derselben Datenbank.
            JointFixture(first.pull.driver, first.server).use { f ->
                f.pull.bind()
                assertTrue(f.ready(OWN))
                assertTrue(f.readable(OWN))
                f.point(OWN, 2)
                assertEquals(CloudUploadOutcome.DRAINED, f.upload().uploadNow().outcome)
                assertEquals(CloudPullOutcome.COMPLETE, f.finishRound().outcome)
                assertTrue(f.ready(OWN))
                assertEquals(2, f.data.events.count(OWN))
            }
        }

    @Test
    fun lAccountSwitchAToBToANeverLetsBAdoptOrCoverAsOwnMatch(): Unit =
        runBlocking {
            JointFixture().use { f ->
                f.pull.bind()
                f.finishRound()
                f.createMatch(OWN)
                assertEquals(CloudUploadOutcome.DRAINED, f.upload().uploadNow().outcome)
                // Bob ist Mitglied desselben Matches, aber nicht Eigentümer der Kopfquelle.
                f.server.matches
                    .getValue(OWN)
                    .members += "bob"
                f.switchTo("bob")
                assertFalse(f.readable(OWN))
                f.finishRound("bob")
                assertFalse(f.ready(OWN))
                assertFalse(f.readable(OWN))
                assertNull(
                    f.data.cloudPulls
                        .snapshot("bob", CloudPullEntityKind.MATCH, OWN)
                        ?.materializedVersion,
                )
                assertEquals("LOCAL", f.metadata("HEADER", OWN)!!.third)
                f.switchTo("alice")
                assertEquals(CloudPullOutcome.COMPLETE, f.finishRound().outcome)
                assertTrue(f.ready(OWN))
                assertTrue(f.readable(OWN))
                f.point(OWN, 2)
                assertEquals(CloudUploadOutcome.DRAINED, f.upload().uploadNow().outcome)
                assertTrue(f.serverEvents(OWN).any { it.id == "$OWN-e2" })
            }
        }

    /** P1-1: Punkte, Projektion und Kopf an einem eigenen Match zwischen Katalogseite und Readiness, auch unbestätigt. */
    @Test
    fun mOwnLiveMatchKeepsCountingWhileAPullIsRunning(): Unit =
        runBlocking {
            JointFixture().use { f ->
                f.pull.bind()
                f.finishRound()
                f.createMatch(OWN)
                f.point(OWN, 2)
                f.progress(OWN, 2)
                assertEquals(CloudUploadOutcome.DRAINED, f.upload().uploadNow().outcome)
                // Laufende Projektion, noch nicht hochgeladen.
                f.point(OWN, 3)
                f.progress(OWN, 3)
                f.catalogOnly()
                assertTrue(
                    f.data.cloudPulls
                        .progress("alice")
                        .roundActive,
                )
                assertFalse(f.ready(OWN))
                assertTrue(f.readable(OWN))
                f.point(OWN, 4)
                f.progress(OWN, 4)
                f.data.matches.updateMeta(MatchId(OWN), MatchMeta(venue = "Halle"))
                assertEquals(4, f.data.events.count(OWN))
                f.finishRound()
                assertTrue(f.readable(OWN))
                assertEquals(CloudUploadOutcome.DRAINED, f.upload().uploadNow().outcome)
                assertEquals(CloudPullOutcome.COMPLETE, f.finishRound().outcome)
                assertTrue(f.ready(OWN))
                assertTrue("Halle" in f.headerColumn(OWN, "meta_json")!!)
                assertEquals("104", f.headerColumn(OWN, "updated_at_ms"))
                assertEquals(listOf(1L, 2L, 3L, 4L), f.serverEvents(OWN).map { it.deviceSeq })
                assertEquals(0, f.pending())
                assertEquals(0, f.server.restarts)
            }
        }

    /**
     * Kontolöschung nach dem Upload (00600/00700): Der Server redigiert Name, Events und Matchidentität, E 0→1. Das eigene
     * Match ist bis zum Transfer nicht lesbar, zählt aber weiter; danach steht der Klarname nirgends mehr, auch nicht im Verzeichnis.
     */
    @Test
    fun nOwnMatchWithARealGuestIsScrubbedAfterAccountDeletionAndKeepsCounting(): Unit =
        runBlocking {
            JointFixture().use { f ->
                f.pull.bind()
                f.finishRound()
                f.data.players.addGuest(PlayerId(KLARA), "Klara", 0)
                f.createMatch(OWN, SLOT)
                f.assign(OWN, 2, KLARA, "Klara", SLOT)
                assertEquals(1, f.assignmentRows(KLARA))
                assertEquals(2, f.localRowsContaining("Klara"))
                assertEquals(CloudUploadOutcome.DRAINED, f.upload().uploadNow().outcome)
                assertEquals(CloudPullOutcome.COMPLETE, f.finishRound().outcome)
                assertTrue(f.ready(OWN))
                val match = f.server.matches.getValue(OWN)
                f.server.redactedPlayers += KLARA
                match.redactedIds += KLARA
                match.log.replaceAll { e ->
                    val person = e.payload["player"] as? JsonObject
                    if (person?.get("id")?.jsonPrimitive?.content != KLARA) {
                        e
                    } else {
                        val clean = JsonObject(person + ("displayName" to JsonPrimitive(DELETED_NAME)))
                        e.copy(payload = JsonObject(e.payload + ("player" to clean)), redacted = true)
                    }
                }
                f.server.raiseEpoch(OWN)
                f.catalogOnly()
                assertFalse(f.readable(OWN))
                f.point(OWN, 3)
                f.progress(OWN, 3)
                f.finishRound()
                assertTrue(f.readable(OWN))
                assertEquals(1, f.access(OWN)!!.redactionEpoch)
                assertEquals(0, f.localRowsContaining("Klara"))
                assertEquals(1, f.assignmentRows(KLARA))
                assertEquals(3, f.data.events.count(OWN))
                assertEquals(CloudUploadOutcome.DRAINED, f.upload().uploadNow().outcome)
                assertEquals(CloudPullOutcome.COMPLETE, f.finishRound().outcome)
                assertTrue(f.ready(OWN))
                assertTrue(f.serverEvents(OWN).any { it.id == "$OWN-e3" })
                assertEquals(0, f.server.restarts)
            }
        }

    /** Neustart zwischen Katalog-Commit und Readiness: gleiche Datenbank, neuer Runner, Zählen ohne Unterbrechung. */
    @Test
    fun oRestartBetweenCatalogAndReadinessKeepsCounting(): Unit =
        runBlocking {
            val first = JointFixture()
            first.pull.bind()
            first.finishRound()
            first.createMatch(OWN)
            assertEquals(CloudUploadOutcome.DRAINED, first.upload().uploadNow().outcome)
            first.catalogOnly()
            assertFalse(first.ready(OWN))
            JointFixture(first.pull.driver, first.server).use { f ->
                f.pull.bind()
                assertFalse(f.ready(OWN))
                assertTrue(f.readable(OWN))
                f.point(OWN, 2)
                f.progress(OWN, 2)
                f.finishRound()
                assertEquals(CloudUploadOutcome.DRAINED, f.upload().uploadNow().outcome)
                assertEquals(CloudPullOutcome.COMPLETE, f.finishRound().outcome)
                assertTrue(f.ready(OWN))
                assertEquals(listOf(1L, 2L), f.serverEvents(OWN).map { it.deviceSeq })
            }
        }

    /**
     * Messung Integrationspunkt B: 205 fremde Matches, Standardbudget des D1-Runners. Gezählt werden D1-Läufe nach dem 40001,
     * bis U4 wieder hochlädt (unbeteiligtes Match) und bis das betroffene Match selbst hochgeladen ist.
     */
    @Test
    fun measureRunsUntilU4UploadsAgainWith205Matches(): Unit =
        runBlocking {
            val results =
                listOf(false, true).associateWith { midRound ->
                    JointFixture().use { f ->
                        f.pull.bind()
                        (1..205).forEach { f.server.foreign(uuid("m$it"), 1) }
                        f.createMatch(OWN)
                        // Vor dem Pull anhängen: nur dieses Event bleibt bis zum 40001 ausstehend.
                        f.point(OWN, 2)
                        f.upload(maxEvents = 1).uploadNow()
                        assertTrue(f.serverEvents(OWN).none { it.id == "$OWN-e2" })
                        var roundRuns = 0
                        do {
                            CloudPullRunner(f.pull.backend, f.data).pullNow()
                            roundRuns++
                        } while (f.data.cloudPulls
                                .progress("alice")
                                .roundActive && roundRuns < 400
                        )
                        if (midRound) repeat(3) { CloudPullRunner(f.pull.backend, f.data).pullNow() }
                        assertEquals(
                            midRound,
                            f.data.cloudPulls
                                .progress("alice")
                                .roundActive,
                        )
                        f.server.raiseEpoch(OWN)
                        val runner = f.upload()
                        assertEquals("restart_required", runner.uploadNow().failure?.reason)
                        f.createMatch(OTHER)
                        var other: Int? = null
                        var own: Int? = null
                        var runs = 0
                        while ((other == null || own == null) && runs < 400) {
                            CloudPullRunner(f.pull.backend, f.data).pullNow()
                            runs++
                            runner.uploadNow()
                            if (other == null && f.serverEvents(OTHER).isNotEmpty()) other = runs
                            if (own == null && f.serverEvents(OWN).any { it.id == "$OWN-e2" }) own = runs
                        }
                        assertEquals(1, f.server.restarts, "kein zweiter 40001 (midRound=$midRound)")
                        Triple(roundRuns, other, own)
                    }
                }
            val text =
                results.entries.joinToString("; ") { (mid, r) ->
                    "${if (mid) "40001 mitten in der Runde" else "40001 zwischen Runden"}: Runde=${r.first} Läufe, " +
                        "U4 wieder aktiv nach ${r.second}, betroffenes Match nach ${r.third}"
                }
            println("MESSUNG 205 Matches: $text")
            // Zwischen Runden: der Katalog des ersten Laufs gibt frei. Mitten in der Runde: höchstens Rest der Runde + 1 Lauf.
            assertEquals(1, results.getValue(false).second, text)
            assertTrue(results.getValue(true).second!! <= results.getValue(true).first + 1, text)
        }

    private companion object {
        val OWN = uuid("own")
        val OTHER = uuid("other")
        val FOREIGN = uuid("foreign")
        val KLARA = uuid("klara")
        val SLOT = Placeholders.id("opponent")
    }
}

// Echte Geräte-IDs sind UUIDs; der Readiness-Vektor weist andere zurück.
private val SOURCE = uuid("phone-source")
