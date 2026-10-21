package eu.advantly.cloud.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId
import eu.advantly.core.scoring.EventPayload
import eu.advantly.core.scoring.PlayerAssigned
import eu.advantly.data.CloudPullEntityKind
import eu.advantly.data.LocalData
import eu.advantly.data.ReplicaAccess
import eu.advantly.data.ReplicaAccessLevel
import eu.advantly.data.StoredEvent
import eu.advantly.data.db.AdvantlyDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val RESTART = """{"code":"40001","message":"restart_required","details":null,"hint":null}"""

private fun LocalData.access(match: String = MATCH): ReplicaAccess? = replicas.access(replicas.captureContext(), match)

private fun PullFixture.requests(rpc: String): List<JsonObject> = calls.filter { it.first == rpc }.map { it.second }

private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.content

/** Redigiertes Legacy-Zuweisungsevent, wie es Server-Manifest und Eventseiten gleichermaßen ausliefern. */
private fun redactedEvent(seq: Long): PulledEvent =
    PulledEvent(
        "redacted-$seq",
        MATCH,
        REMOTE,
        seq,
        seq,
        seq,
        type = "PlayerAssigned",
        payload =
            CloudJson
                .encodeToJsonElement(
                    EventPayload.serializer(),
                    PlayerAssigned(PlayerId("slot-a"), Player(PlayerId(uuid("p-$seq")), "Gelöschter Spieler"), PlayerId("slot-b")),
                ).jsonObject,
        serverSeq = seq,
        redacted = true,
    )

/** Begrenzter, an G/E/H gebundener Manifestserver mit getrennten Cursorn; veraltetes G/E heißt restart_required. */
private class ManifestServer(
    val ids: List<String>,
    val events: List<PulledEvent>,
    var generation: Long = 1,
    var epoch: Int = 1,
) {
    val requests = mutableListOf<JsonObject>()

    fun respond(body: JsonObject): String {
        requests += body
        if (body.long("p_generation") != generation || body.long("p_redaction_epoch").toInt() != epoch) return RESTART
        val high = body.text("p_high_watermark")?.toLong() ?: (events.maxOfOrNull { it.serverSeq } ?: 0)
        val afterPlayer = body.text("p_after_player_id")
        val afterSeq = body.long("p_after_server_seq")
        val openIds = ids.filter { afterPlayer == null || it > afterPlayer }
        val openEvents = events.filter { it.serverSeq > afterSeq && it.serverSeq <= high }
        val pageIds = openIds.take(body.long("p_player_limit").toInt())
        val pageEvents = openEvents.take(body.long("p_event_limit").toInt())
        return CloudJson.encodeToString(
            CloudRedactionPage.serializer(),
            CloudRedactionPage(
                MATCH,
                generation,
                epoch,
                high,
                pageIds,
                pageEvents,
                pageIds.lastOrNull() ?: afterPlayer,
                pageEvents.lastOrNull()?.serverSeq ?: afterSeq,
                openIds.size > pageIds.size,
                openEvents.size > pageEvents.size,
            ),
        )
    }
}

class CloudPullLifecycleTest {
    private fun databaseFile(): File =
        File.createTempFile("d1-lifecycle", ".db").also { file ->
            JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { AdvantlyDatabase.Schema.create(it).value }
        }

    private fun reopen(file: File) = PullFixture(JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}"))

    private fun PullFixture.serveManifestMatch(
        server: ManifestServer,
        version: () -> Long = { 1 },
        visibility: () -> Long = { server.generation },
    ) {
        metadata = { cursor, _ ->
            val row = pullMatch(version = version()).copy(redactionEpoch = server.epoch)
            PullResponse(listOf(row).filter { it.serverVersion > cursor }, cursor = version(), visibilityEpoch = visibility())
        }
        events = { _, after, limit -> server.events.filter { it.serverSeq > after }.take(limit) }
        serverLog = { server.events }
        redaction = { server.respond(it) }
    }

    @Test
    fun epochZeroTakesHighWatermarkFromServerPersistsItAndIsReadyOnlyAfterExactCoverage() =
        runBlocking {
            PullFixture().use { f ->
                f.bind()
                var log = (1L..3L).map { pullEvent(it) }
                f.metadata = { _, _ -> PullResponse(listOf(pullMatch()), cursor = 1, visibilityEpoch = 1) }
                f.events = { _, after, limit -> log.filter { it.serverSeq > after }.take(limit) }
                f.serverLog = { log }
                // Ein legaler Upload zwischen letzter Eventseite und Readiness: der Server hält H oberhalb des lokalen Cursors fest.
                var first = true
                f.readiness = { body ->
                    if (first) log = log + pullEvent(4)
                    first = false
                    f.servedReadiness(body)
                }
                val result = CloudPullRunner(f.backend, f.data).pullNow()
                assertEquals(CloudPullOutcome.COMPLETE, result.outcome)
                val requests = f.requests("cloud_match_readiness_v1")
                assertEquals(2, requests.size)
                assertNull(requests[0].text("p_high_watermark"))
                assertEquals(4, requests[1].long("p_high_watermark"))
                assertTrue(requests.all { it.long("p_generation") == 1L && it.long("p_redaction_epoch") == 0L })
                assertTrue(f.data.access()!!.ready)
                assertEquals(4, f.data.events.count(MATCH))
                assertTrue(f.requests("cloud_pull_match_redaction_v1").isEmpty())
            }
        }

    @Test
    fun readinessVectorWithMissingEventStaysNotReady() =
        runBlocking {
            for (gapBelowCursor in listOf(false, true)) {
                PullFixture().use { f ->
                    f.bind()
                    val delivered = if (gapBelowCursor) listOf(pullEvent(1), pullEvent(3)) else listOf(pullEvent(1), pullEvent(2))
                    f.metadata = { _, _ -> PullResponse(listOf(pullMatch()), cursor = 1, visibilityEpoch = 1) }
                    f.events = { _, after, limit -> delivered.filter { it.serverSeq > after }.take(limit) }
                    f.watermarks = { listOf(EventWatermark(REMOTE, 3, delivered.size.toLong())) }
                    // Der Vektor bis H = 3 enthält ein Event, das nie ausgeliefert wird; more=false beweist nichts.
                    f.readiness = { body ->
                        CloudJson.encodeToString(
                            CloudMatchReadiness.serializer(),
                            CloudMatchReadiness(MATCH, body.long("p_generation"), 0, 3, 1, listOf(EventWatermark(REMOTE, 3, 3))),
                        )
                    }
                    val result = CloudPullRunner(f.backend, f.data).pullNow()
                    assertFalse(result.outcome == CloudPullOutcome.COMPLETE, "gap=$gapBelowCursor")
                    assertEquals(0, result.failedEventJobs)
                    assertFalse(f.data.access()!!.ready)
                    assertTrue(f.requests("cloud_match_readiness_v1").size >= 2)
                    if (gapBelowCursor) {
                        assertTrue(
                            f.data.cloudPulls
                                .eventWork("alice", MATCH)!!
                                .repairing,
                        )
                    }
                }
            }
        }

    @Test
    fun ownUnuploadedEventsNeitherHideNorFakeAGap() =
        runBlocking {
            // Fake: ein eigenes, nie hochgeladenes Event darf den exakten Vektor nicht verfälschen.
            PullFixture().use { f ->
                f.bind()
                val log = listOf(pullEvent(1), pullEvent(2))
                f.metadata = { _, _ -> PullResponse(listOf(pullMatch()), cursor = 1, visibilityEpoch = 1) }
                f.events = { _, after, limit -> log.filter { it.serverSeq > after }.take(limit) }
                f.serverLog = { log }
                val own =
                    f.data.accountIdentities
                        .localSources()
                        .single()
                        .deviceId
                f.readiness = { body ->
                    f.data.events.append(StoredEvent("own-1", MATCH, own, 1, 9, """{"type":"PointWon","side":"B"}""", type = "PointWon"))
                    f.servedReadiness(body)
                }
                assertEquals(CloudPullOutcome.COMPLETE, CloudPullRunner(f.backend, f.data).pullNow().outcome)
                assertTrue(f.data.access()!!.ready)
                assertEquals(
                    listOf("own-1"),
                    f.data.cloud
                        .pendingEvents(10)
                        .map { it.id },
                )
            }
            // Hide: ein lokal vorhandenes, aber nie heruntergeladenes Event schließt die Lücke nicht.
            PullFixture().use { f ->
                f.bind()
                val delivered = listOf(pullEvent(1), pullEvent(2))
                f.metadata = { _, _ -> PullResponse(listOf(pullMatch()), cursor = 1, visibilityEpoch = 1) }
                f.events = { _, after, limit -> delivered.filter { it.serverSeq > after }.take(limit) }
                f.serverLog = { delivered + pullEvent(3) }
                f.readiness = { body ->
                    if (f.data.events.count(MATCH) == 2L) f.data.events.append(EventRows.toStored(pullEvent(3).copy(id = "peer-3").toRow()))
                    f.servedReadiness(body)
                }
                val result = CloudPullRunner(f.backend, f.data).pullNow()
                assertFalse(result.outcome == CloudPullOutcome.COMPLETE)
                assertFalse(f.data.access()!!.ready)
                assertEquals(3, f.data.events.count(MATCH))
            }
        }

    @Test
    fun restartRequiredDiscardsHandleRefreshesCatalogAndRepeatsWithoutFailure() =
        runBlocking {
            PullFixture().use { f ->
                f.bind()
                val log = listOf(pullEvent(1))
                var metadataCalls = 0
                f.metadata = { _, _ ->
                    metadataCalls++
                    PullResponse(listOf(pullMatch()), cursor = 1, visibilityEpoch = 1)
                }
                f.events = { _, after, limit -> log.filter { it.serverSeq > after }.take(limit) }
                f.serverLog = { log }
                var restarts = 1
                f.readiness = { body -> if (restarts-- > 0) RESTART else f.servedReadiness(body) }
                val result = CloudPullRunner(f.backend, f.data).pullNow()
                assertEquals(CloudPullOutcome.COMPLETE, result.outcome)
                assertNull(result.failure)
                assertEquals(0, result.failedEventJobs)
                assertEquals(2, f.requests("cloud_match_readiness_v1").size)
                assertEquals(2, metadataCalls)
                assertTrue(f.data.access()!!.ready)
                assertNull(
                    f.data.cloudPulls
                        .eventWork("alice", MATCH)!!
                        .failure,
                )
            }
        }

    @Test
    fun persistentRestartRequiredIsNeitherPermissionLossNorPermanentFailure() =
        runBlocking {
            PullFixture().use { f ->
                f.bind()
                val log = listOf(pullEvent(1))
                f.metadata = { _, _ -> PullResponse(listOf(pullMatch()), cursor = 1, visibilityEpoch = 1) }
                f.events = { _, after, limit -> log.filter { it.serverSeq > after }.take(limit) }
                f.serverLog = { log }
                f.readiness = { RESTART }
                val result = CloudPullRunner(f.backend, f.data).pullNow()
                assertEquals(CloudPullOutcome.LIMIT_REACHED, result.outcome)
                assertEquals("cloud_pull_restart_required", result.failure)
                assertEquals(0, result.failedEventJobs)
                assertEquals(3, f.requests("cloud_match_readiness_v1").size)
                val access = f.data.access()!!
                assertEquals(ReplicaAccessLevel.MEMBER, access.access)
                assertFalse(access.ready)
                f.readiness = { f.servedReadiness(it) }
                assertEquals(CloudPullOutcome.COMPLETE, CloudPullRunner(f.backend, f.data).pullNow().outcome)
                assertTrue(f.data.access()!!.ready)
            }
        }

    @Test
    fun revocationAndRegrantPurgeThenRequireFreshReadinessAtNewGeneration() =
        runBlocking {
            PullFixture().use { f ->
                f.bind()
                var epoch = 1L
                var visible = true
                val log = (1L..3L).map { pullEvent(it) }
                f.metadata =
                    { _, _ -> PullResponse(if (visible) listOf(pullMatch()) else emptyList(), cursor = 1, visibilityEpoch = epoch) }
                f.events = { _, after, limit -> log.filter { it.serverSeq > after }.take(limit) }
                f.serverLog = { log }
                assertEquals(CloudPullOutcome.COMPLETE, CloudPullRunner(f.backend, f.data).pullNow().outcome)
                assertTrue(f.data.access()!!.ready)
                // Entzug: erst der vollständige Katalog bei G = 2 belegt ABSENT; der private Bestand verschwindet.
                epoch = 2
                visible = false
                assertEquals(CloudPullOutcome.COMPLETE, CloudPullRunner(f.backend, f.data).pullNow().outcome)
                val revoked = f.data.access()!!
                assertEquals(ReplicaAccessLevel.ABSENT, revoked.access)
                assertEquals(2, revoked.generation)
                assertFalse(revoked.ready)
                assertEquals(0, f.data.events.count(MATCH))
                assertNull(f.data.matches.get(MatchId(MATCH)))
                assertNull(f.data.cloudPulls.snapshot("alice", CloudPullEntityKind.MATCH, MATCH))
                // Wiederfreigabe: neue Generation, vollständiger Neudownload und frische Readiness bei G = 3.
                epoch = 3
                visible = true
                val before = f.requests("cloud_match_readiness_v1").size
                assertEquals(CloudPullOutcome.COMPLETE, CloudPullRunner(f.backend, f.data).pullNow().outcome)
                val regranted = f.data.access()!!
                assertEquals(ReplicaAccessLevel.MEMBER, regranted.access)
                assertEquals(3, regranted.generation)
                assertTrue(regranted.ready)
                assertEquals(3, f.data.events.count(MATCH))
                val fresh = f.requests("cloud_match_readiness_v1").drop(before)
                assertEquals(listOf(3L), fresh.map { it.long("p_generation") })
            }
        }

    @Test
    fun memberToMemberKeepsOwnMaterializationResetsReadyAndRecreatesEventWorkForUnchangedSnapshot() =
        runBlocking {
            PullFixture().use { f ->
                f.bind()
                var epoch = 1L
                val log = (1L..2L).map { pullEvent(it) }
                f.metadata = { cursor, _ -> PullResponse(listOf(pullMatch()), cursor = maxOf(cursor, 1), visibilityEpoch = epoch) }
                f.events = { _, after, limit -> log.filter { it.serverSeq > after }.take(limit) }
                f.serverLog = { log }
                assertEquals(CloudPullOutcome.COMPLETE, CloudPullRunner(f.backend, f.data).pullNow().outcome)
                assertTrue(f.data.access()!!.ready)
                epoch = 2
                // Nur die Katalogseiten (Epochenwechsel plus Neuscan): das nicht-ready-Fenster bleibt beobachtbar.
                assertEquals(CloudPullOutcome.LIMIT_REACHED, CloudPullRunner(f.backend, f.data, maxRpcsPerRun = 2).pullNow().outcome)
                val window = f.data.access()!!
                assertEquals(ReplicaAccessLevel.MEMBER, window.access)
                assertEquals(2, window.generation)
                assertFalse(window.ready)
                val snapshot = f.data.cloudPulls.snapshot("alice", CloudPullEntityKind.MATCH, MATCH)!!
                assertEquals(1, snapshot.serverVersion)
                assertEquals(1, snapshot.materializedVersion)
                assertNotNull(f.data.matches.get(MatchId(MATCH)))
                assertEquals(2, f.data.events.count(MATCH))
                val work = assertNotNull(f.data.cloudPulls.eventWork("alice", MATCH))
                assertEquals(0, work.checkedRound)
                assertEquals(2, work.cursor)
                assertEquals(CloudPullOutcome.COMPLETE, CloudPullRunner(f.backend, f.data).pullNow().outcome)
                assertTrue(f.data.access()!!.ready)
                assertEquals(2L, f.requests("cloud_match_readiness_v1").last().long("p_generation"))
            }
        }

    @Test
    fun moreThanTwoHundredMemberMatchesAndFiveHundredEventsAllBecomeReady() =
        runBlocking {
            PullFixture().use { f ->
                f.bind()
                val ids = (1..205).map { uuid("m-$it") }
                val long = (1L..501L).map { pullEvent(it, ids.first()) }
                f.metadata = { cursor, limit ->
                    val rows = (1L..205L).filter { it > cursor }.take(limit)
                    PullResponse(
                        rows.map { pullMatch(ids[it.toInt() - 1], it) },
                        cursor = rows.lastOrNull() ?: 205,
                        hasMore = rows.size == limit && rows.last() < 205,
                        visibilityEpoch = 1,
                    )
                }
                f.events = { id, after, limit -> if (id == ids.first()) long.filter { it.serverSeq > after }.take(limit) else emptyList() }
                f.serverLog = { id -> if (id == ids.first()) long else emptyList() }
                var result = CloudPullState()
                for (run in 1..20) {
                    result =
                        CloudPullRunner(f.backend, f.data, maxRpcsPerRun = 100, maxPagesPerRun = 100, maxEventJobsPerRun = 100).pullNow()
                    if (result.outcome == CloudPullOutcome.COMPLETE) break
                    assertEquals(CloudPullOutcome.LIMIT_REACHED, result.outcome)
                }
                assertEquals(CloudPullOutcome.COMPLETE, result.outcome)
                assertTrue(ids.all { f.data.access(it)!!.ready })
                assertEquals(ids.toSet(), f.requests("cloud_match_readiness_v1").map { it.text("p_match_id") }.toSet())
                assertEquals(501, f.data.events.count(ids.first()))
            }
        }

    @Test
    fun redactionTransferSplitsCloudPagesIntoDataPagesAppliesEpochAndBecomesReady() =
        runBlocking {
            PullFixture().use { f ->
                f.bind()
                val server = ManifestServer(PLAYERS, (1L..600L).map { redactedEvent(it) })
                f.serveManifestMatch(server)
                val result = CloudPullRunner(f.backend, f.data, maxRpcsPerRun = 20, maxPagesPerRun = 20).pullNow()
                assertEquals(CloudPullOutcome.COMPLETE, result.outcome)
                assertEquals(0, result.pendingRedactions)
                val requests = server.requests
                assertEquals(2, requests.size)
                assertNull(requests[0].text("p_high_watermark"))
                assertEquals(600, requests[1].long("p_high_watermark"))
                assertEquals(500, requests[1].long("p_after_server_seq"))
                assertEquals(PLAYERS[199], requests[1].text("p_after_player_id"))
                val ctx = f.data.replicas.captureContext()
                val progress = f.data.replicas.redactionProgress(ctx, MATCH)!!
                assertTrue(progress.completed && progress.idsDone && progress.eventsDone)
                assertEquals(600, progress.transfer.highWatermark)
                assertTrue(progress.nextPageIndex > 2, "500 Events je Cloudseite brauchen mehrere 64-KiB-Datenseiten")
                val access = f.data.access()!!
                assertEquals(1, access.redactionEpoch)
                assertTrue(access.ready)
                val readiness = f.requests("cloud_match_readiness_v1").single()
                assertEquals(600, readiness.long("p_high_watermark"))
                assertEquals(1, readiness.long("p_redaction_epoch"))
                assertEquals(600, f.data.events.count(MATCH))
            }
        }

    @Test
    fun restartMidTransferResumesPersistedCursorsUnderFreshAuthorization() =
        runBlocking {
            val file = databaseFile()
            val server = ManifestServer(PLAYERS, (1L..600L).map { redactedEvent(it) })
            try {
                reopen(file).use { f ->
                    f.bind()
                    f.serveManifestMatch(server)
                    assertEquals(CloudPullOutcome.LIMIT_REACHED, CloudPullRunner(f.backend, f.data, maxPagesPerRun = 2).pullNow().outcome)
                    val progress = f.data.replicas.redactionProgress(f.data.replicas.captureContext(), MATCH)!!
                    assertFalse(progress.completed)
                    assertEquals(500, progress.cursor.afterServerSeq)
                    assertEquals(PLAYERS[199], progress.cursor.afterPlayerId)
                    assertFalse(f.data.access()!!.ready)
                }
                reopen(file).use { f ->
                    f.bind()
                    f.serveManifestMatch(server)
                    val result = CloudPullRunner(f.backend, f.data, maxRpcsPerRun = 20, maxPagesPerRun = 20).pullNow()
                    assertEquals(CloudPullOutcome.COMPLETE, result.outcome)
                    val resumed = server.requests[1]
                    assertEquals(600, resumed.long("p_high_watermark"))
                    assertEquals(500, resumed.long("p_after_server_seq"))
                    assertEquals(PLAYERS[199], resumed.text("p_after_player_id"))
                    assertEquals(2, server.requests.size)
                    assertTrue(f.data.access()!!.ready)
                }
            } finally {
                file.delete()
            }
        }

    @Test
    fun changedRedactionEpochBetweenPagesStartsNewTransferAfterRestart() =
        runBlocking {
            PullFixture().use { f ->
                f.bind()
                val server = ManifestServer(PLAYERS, (1L..600L).map { redactedEvent(it) })
                var version = 1L
                f.serveManifestMatch(server, version = { version })
                assertEquals(CloudPullOutcome.LIMIT_REACHED, CloudPullRunner(f.backend, f.data, maxPagesPerRun = 2).pullNow().outcome)
                server.epoch = 2
                version = 2
                val result = CloudPullRunner(f.backend, f.data, maxRpcsPerRun = 20, maxPagesPerRun = 20).pullNow()
                assertEquals(CloudPullOutcome.COMPLETE, result.outcome)
                assertEquals(
                    RESTART_REASON_PAGES,
                    server.requests.map {
                        it.long("p_redaction_epoch") to
                            (it.text("p_high_watermark") != null)
                    },
                )
                val progress = f.data.replicas.redactionProgress(f.data.replicas.captureContext(), MATCH)!!
                assertEquals(2, progress.transfer.epoch)
                assertTrue(progress.completed)
                assertEquals(2, f.data.access()!!.redactionEpoch)
                assertTrue(f.data.access()!!.ready)
            }
        }

    @Test
    fun changedVisibilityGenerationBetweenPagesClearsTransferAndRestartsAtNewGeneration() =
        runBlocking {
            PullFixture().use { f ->
                f.bind()
                val server = ManifestServer(PLAYERS, (1L..600L).map { redactedEvent(it) })
                f.serveManifestMatch(server)
                assertEquals(CloudPullOutcome.LIMIT_REACHED, CloudPullRunner(f.backend, f.data, maxPagesPerRun = 2).pullNow().outcome)
                server.generation = 2
                val result = CloudPullRunner(f.backend, f.data, maxRpcsPerRun = 20, maxPagesPerRun = 20).pullNow()
                assertEquals(CloudPullOutcome.COMPLETE, result.outcome)
                assertEquals(listOf(1L, 1L, 2L, 2L), server.requests.map { it.long("p_generation") })
                assertNull(server.requests[2].text("p_high_watermark"))
                val progress = f.data.replicas.redactionProgress(f.data.replicas.captureContext(), MATCH)!!
                assertEquals(2, progress.transfer.generation)
                assertTrue(progress.completed)
                val access = f.data.access()!!
                assertEquals(2, access.generation)
                assertTrue(access.ready)
            }
        }

    @Test
    fun invalidRedactionPageStagesNothing() =
        runBlocking {
            PullFixture().use { f ->
                f.bind()
                val server = ManifestServer(listOf(uuid("p-1")), listOf(redactedEvent(1)))
                f.serveManifestMatch(server)
                // Nicht als redigiert gekennzeichnete Events sind kein Manifest; die ganze Seite wird verworfen.
                f.redaction = { server.respond(it).replace("\"redacted\":true", "\"redacted\":false") }
                val result = CloudPullRunner(f.backend, f.data).pullNow()
                assertEquals(CloudPullOutcome.FAILED, result.outcome)
                assertNull(f.data.replicas.redactionProgress(f.data.replicas.captureContext(), MATCH))
                assertFalse(f.data.access()!!.ready)
                assertEquals(0, f.data.events.count(MATCH))
            }
        }

    @Test
    fun lateNonCooperativeRedactionAndReadinessResponsesAfterContextChangesWriteNothing() =
        runBlocking {
            for (mode in listOf("account", "consent", "logout", "identity-a-b-a")) {
                for (rpc in listOf("redaction", "readiness")) {
                    PullFixture().use { f ->
                        f.bind()
                        val server = ManifestServer(listOf(uuid("p-1")), listOf(redactedEvent(1)), epoch = if (rpc == "redaction") 1 else 0)
                        f.serveManifestMatch(server)
                        if (rpc == "readiness") {
                            f.events = { _, after, limit -> listOf(pullEvent(1)).filter { it.serverSeq > after }.take(limit) }
                            f.serverLog = { listOf(pullEvent(1)) }
                        }

                        fun change() {
                            when (mode) {
                                "account" -> {
                                    f.credentials = CloudCredentials("bob", "session-bob", "token-bob")
                                }

                                "consent" -> {
                                    f.publish(ConsentStatus.REQUIRED)
                                }

                                "logout" -> {
                                    f.gate.stop()
                                    f.credentials = null
                                }

                                else -> {
                                    f.data.selectPullAccount("bob").selectPullAccount("alice")
                                }
                            }
                        }
                        f.redaction = { body -> server.respond(body).also { withContext(NonCancellable) { change() } } }
                        f.readiness = { body -> f.servedReadiness(body).also { withContext(NonCancellable) { change() } } }
                        val runner = CloudPullRunner(f.backend, f.data)
                        val result =
                            try {
                                runner.pullNow()
                            } catch (_: CancellationException) {
                                runner.state.value
                            }
                        val label = "$mode/$rpc"
                        assertFalse(result.outcome == CloudPullOutcome.COMPLETE, label)
                        val ctx = f.data.replicas.captureContext()
                        assertNull(f.data.replicas.redactionProgress(ctx, MATCH), label)
                        assertFalse(
                            f.data.replicas
                                .access(ctx, MATCH)!!
                                .ready,
                            label,
                        )
                        if (mode == "identity-a-b-a") {
                            // Neue Auswahl verlangt einen frischen Lauf; danach setzt dieser wiederaufnehmbar fort.
                            f.redaction = { server.respond(it) }
                            f.readiness = { f.servedReadiness(it) }
                            assertEquals(CloudPullOutcome.COMPLETE, CloudPullRunner(f.backend, f.data).pullNow().outcome, label)
                            assertTrue(f.data.access()!!.ready, label)
                        }
                    }
                }
            }
        }

    private companion object {
        val PLAYERS = (1..250).map { uuid("p-$it") }.sorted()

        // Seite 1 bei E = 1 ohne H, Fortsetzung bei E = 1 mit H (restart_required), dann neuer Transfer bei E = 2.
        val RESTART_REASON_PAGES = listOf(1L to false, 1L to true, 2L to false, 2L to true)
    }
}
