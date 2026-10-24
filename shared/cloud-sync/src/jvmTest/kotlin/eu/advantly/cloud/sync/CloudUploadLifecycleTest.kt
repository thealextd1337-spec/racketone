package eu.advantly.cloud.sync

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.EventId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.PlayerId
import eu.advantly.core.scoring.MatchCreated
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.ScoreEvent
import eu.advantly.data.CloudMetadataKind
import eu.advantly.data.CloudPullMatch
import eu.advantly.data.CloudPullMetadataPage
import eu.advantly.data.LocalData
import eu.advantly.data.RedactionCursor
import eu.advantly.data.RedactionPage
import eu.advantly.data.RedactionTransfer
import eu.advantly.data.ReplicaAccessLevel
import eu.advantly.data.ScoreSummary
import eu.advantly.data.StoredEvent
import eu.advantly.data.createInMemoryJvmDriver
import eu.advantly.data.metadataState
import eu.advantly.data.toStoredEvent
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** U4: Upload-Beleg G/E, Neustart nach 40001 und Fairness blockierter Matches (Vertrag Welle 2). */
class CloudUploadLifecycleTest {
    private class Fixture(
        sources: Set<String> = setOf("phone-source"),
    ) : AutoCloseable {
        val driver = createInMemoryJvmDriver()
        val data = LocalData(driver)
        val hostThread: Thread = Thread.currentThread()
        var credentials = CloudCredentials("alice", "session", "token")
        val gate = CloudSessionGate({ credentials }, data.cloudUploads, { Thread.currentThread() === hostThread })
        val bodies = mutableListOf<JsonObject>()
        val batches get() = bodies.map { CloudJson.decodeFromJsonElement(PushBatch.serializer(), it) }
        val committed = mutableSetOf<String>()

        // Nachbildung von 00700: G = profile_settings.visibility_epoch, E je Match, dem Server bekannte Matches.
        var serverG = 0L
        val serverE = mutableMapOf<String, Long>()
        val serverMatches = mutableSetOf<String>()
        var restartWhen: (PushBatch) -> Boolean = { false }

        // Widerrufene Geräte (K4-U): Bindung des Aufrufers → device_revoked, Quellgerät eines Events → device_not_owned.
        val revoked = mutableSetOf<String>()
        var rejectEvent: (EventRow) -> String? = { null }

        fun violates00700(batch: PushBatch): Boolean =
            restartRequired00700(batch, serverG, { id -> serverE.getOrDefault(id, 0).takeIf { id in serverMatches } }, { it in committed })

        var loseResponse = false
        var hold: CompletableDeferred<Unit>? = null
        val sent = CompletableDeferred<Unit>()
        val http =
            CloudHttp(
                "https://cloud.test",
                "public",
                HttpClient(
                    MockEngine { request ->
                        val body = CloudJson.parseToJsonElement((request.body as TextContent).text).jsonObject
                        val json = headersOf("Content-Type", "application/json")
                        if (request.url.encodedPath.endsWith("bind_cloud_session_v1")) {
                            if ("caller-phone" in revoked) {
                                return@MockEngine respond("""{"code":"42501","message":"device_revoked"}""", HttpStatusCode.Forbidden, json)
                            }
                            val profile = credentials.profileId
                            return@MockEngine respond(
                                """{"contractVersion":1,"profileId":"$profile","sessionId":"session",
                                    "installationId":"installation","deviceId":"caller-phone"}""",
                                headers = json,
                            )
                        }
                        val raw = body.getValue("p_batch").jsonObject
                        bodies += raw
                        val batch = CloudJson.decodeFromJsonElement(PushBatch.serializer(), raw)
                        // Der Server prüft G/E vor jedem Write; eine spätere lokale Änderung trifft den Commit nicht mehr.
                        val restart = restartWhen(batch) || violates00700(batch)
                        if (!restart) serverMatches += batch.matches.map { it.id } + batch.events.map { it.matchId }
                        hold?.let {
                            sent.complete(Unit)
                            it.await()
                        }
                        if (restart) {
                            return@MockEngine respond(
                                """{"code":"40001","message":"restart_required"}""",
                                HttpStatusCode.Conflict,
                                json,
                            )
                        }
                        val response =
                            PushResponse(
                                devices = batch.devices.map { ItemResult(it.id, "ok") },
                                players = batch.players.map { ItemResult(it.id, "ok") },
                                matches = batch.matches.map { ItemResult(it.id, "updated", projection = it.projection?.let { "applied" }) },
                                events =
                                    batch.events.map {
                                        val reason = if (it.deviceId in revoked) "device_not_owned" else rejectEvent(it)
                                        if (reason != null) {
                                            ItemResult(it.id, "rejected", reason)
                                        } else {
                                            ItemResult(it.id, if (committed.add(it.id)) "accepted" else "duplicate")
                                        }
                                    },
                            )
                        if (loseResponse) throw IOException("response lost after commit")
                        respond(CloudJson.encodeToString(PushResponse.serializer(), response), headers = json)
                    },
                ) { followRedirects = false },
            )
        val backend = SupabaseCloudBackend(gate, http, CloudInstallation("installation", "a".repeat(64)))

        init {
            data.players.ensureSelf("Alex", 0) { PlayerId("me") }
            data.accountIdentities.initialize("phone-source", "me", "PHONE")
            account("alice")
            val session = data.cloudUploads.activate("alice")
            data.cloudUploads.bindOwnedDevices(session, sources)
            data.cloudUploads.deactivate()
            gate.publish(gate.capture(false), CloudSession("alice", true, consentStatus = ConsentStatus.CURRENT))
        }

        suspend fun bind() = backend.bindDevice("caller-phone")

        /** Replikatkonto des Geräts (`identity.profile`); G und E müssen aus demselben Konto stammen. */
        fun account(profile: String) = data.accountIdentities.select("legacy:phone-source", profile, 0, "PHONE") { error("keine neue ID") }

        suspend fun switchTo(profile: String) {
            credentials = CloudCredentials(profile, "session", "token")
            gate.completeTransition(gate.stop())
            gate.publish(gate.capture(false), CloudSession(profile, true, consentStatus = ConsentStatus.CURRENT))
            bind()
        }

        val restartKey = "cloud.upload.restart.alice"

        fun runner(maxEvents: Int = 500) = CloudUploadRunner(backend, { 1L }, 1, maxEvents, { Thread.currentThread() === hostThread })

        fun event(
            match: String,
            seq: Long,
        ) = data.events.append(
            StoredEvent("$match-e$seq", match, "phone-source", seq, seq, """{"type":"PointWon","side":"A"}""", type = "PointWon"),
        )

        /** Vollständige Metadatenrunde mit Sichtbarkeitsepoche [g]; optional ohne Rundenabschluss. */
        fun pull(
            g: Long,
            finish: Boolean = true,
            matches: List<CloudPullMatch> = emptyList(),
        ) {
            val run = data.cloudPulls.beginRun("alice") {}.runSerial
            var cursor = data.cloudPulls.progress("alice").cursor
            val page = { input: Long -> CloudPullMetadataPage(matches, emptyList(), emptyList(), input + 1, false, g) }
            if (!data.cloudPulls.commitMetadata("alice", run, cursor, page(cursor)) {}) {
                cursor = data.cloudPulls.progress("alice").cursor
                assertTrue(data.cloudPulls.commitMetadata("alice", run, cursor, page(cursor)) {})
            }
            if (finish) data.cloudPulls.finishRound("alice", run) {}
            serverG = g
        }

        /** Neue Runde sieht G' ([g]), ist aber noch nicht abgeschlossen: targetEpoch gesetzt. */
        fun observe(g: Long) {
            val run = data.cloudPulls.beginRun("alice") {}.runSerial
            val cursor = data.cloudPulls.progress("alice").cursor
            data.cloudPulls.commitMetadata(
                "alice",
                run,
                cursor,
                CloudPullMetadataPage(emptyList(), emptyList(), emptyList(), cursor + 1, true, g),
            ) {}
            assertEquals(g, data.cloudPulls.progress("alice").targetEpoch)
        }

        fun member(
            match: String,
            g: Long,
            access: ReplicaAccessLevel = ReplicaAccessLevel.MEMBER,
        ) = data.replicas.applyAccess(data.replicas.captureContext(), match, g, access) {}

        fun redact(
            match: String,
            g: Long,
            epoch: Long,
            players: List<String> = emptyList(),
            complete: Boolean = true,
        ) {
            val ctx = data.replicas.captureContext()
            serverE[match] = epoch
            val transfer = RedactionTransfer("t-$match-$epoch", match, g, epoch, 0)
            val from = data.replicas.beginRedaction(ctx, transfer) {}
            if (!complete) return
            val ids = players.sorted()
            val page = RedactionPage(0, from, RedactionCursor(ids.lastOrNull() ?: from.afterPlayerId, 0), ids, emptyList(), true, true)
            data.replicas.stageRedaction(ctx, transfer.id, page) {}
            data.replicas.completeRedaction(ctx, transfer.id) {}
        }

        fun createMatch(id: String) {
            val format = Presets.TENNIS_SINGLES_BO3.format()
            val lineup = Lineup(listOf(PlayerId("me")), listOf(PlayerId("guest")), PlayerId("me"))
            if (data.players.get(PlayerId("guest")) == null) data.players.addGuest(PlayerId("guest"), "Bea", 0)
            data.events.append(
                ScoreEvent(EventId("$id-genesis"), MatchId(id), DeviceId("phone-source"), 1, 1, 1, null, MatchCreated(format, lineup))
                    .toStoredEvent(),
            )
            data.matches.insertIfAbsent(MatchId(id), format, lineup, MatchMeta(), 1)
            data.matches.updateProgress(MatchId(id), MatchStatus.IN_PROGRESS, ScoreSummary(eventCount = 1), 1)
        }

        override fun close() {
            hold?.complete(Unit)
            gate.stop()
            http.close()
            driver.close()
        }
    }

    @Test
    fun watchEventsUploadOnceTheWatchIsRestoredAfterThePhone(): Unit =
        runBlocking {
            // K4-U r2 (Review P2-3): Handy und Uhr widerrufen, erst das Handy frei → Uhr-Events device_not_owned (endgültig).
            // Die Uhr-Freigabe erhöht G; danach gehen genau diese Ablehnungen wieder auf ausstehend, andere bleiben.
            Fixture(setOf("phone-source", WATCH)).use { f ->
                f.revoked += setOf("caller-phone", WATCH)
                f.pull(1)
                f.event(LOCAL, 1)
                f.event(LOCAL, 2)
                f.data.events.append(StoredEvent("$LOCAL-w1", LOCAL, WATCH, 1, 3, """{"type":"PointWon","side":"B"}""", type = "PointWon"))
                assertEquals("device_revoked", assertFailsWith<CloudException> { f.bind() }.reason)
                // Handy frei (G 2): neu anmelden und synchronisieren.
                f.revoked -= "caller-phone"
                f.rejectEvent = { if (it.id == "$LOCAL-e2") "forbidden" else null }
                f.switchTo("alice")
                f.pull(2)
                assertEquals(CloudUploadOutcome.BLOCKED, f.runner().uploadNow().outcome)
                assertEquals(mapOf("$LOCAL-e2" to "forbidden", "$LOCAL-w1" to "device_not_owned"), f.data.cloud.rejectedEvents())
                assertEquals(CloudUploadOutcome.BLOCKED, f.runner().uploadNow().outcome)
                assertEquals(1, f.bodies.size, "ohne Auslöser kein erneuter Versand")
                // Uhr frei (G 3): der nächste Pull sieht die neue G, die Uhr-Events gehen hoch.
                f.revoked -= WATCH
                f.pull(3)
                assertEquals(mapOf("$LOCAL-e2" to "forbidden"), f.data.cloud.rejectedEvents())
                assertEquals(CloudUploadOutcome.BLOCKED, f.runner().uploadNow().outcome)
                assertEquals(
                    listOf("$LOCAL-w1"),
                    f.batches
                        .last()
                        .events
                        .map { it.id },
                )
                assertEquals(setOf("$LOCAL-e1", "$LOCAL-w1"), f.committed)
                assertEquals(0, f.data.cloud.pendingCount())
            }
        }

    @Test
    fun aNewSignInRetriesDeviceRejections(): Unit =
        runBlocking {
            Fixture(setOf("phone-source", WATCH)).use { f ->
                f.revoked += WATCH
                f.pull(1)
                f.data.events.append(StoredEvent("$LOCAL-w1", LOCAL, WATCH, 1, 1, """{"type":"PointWon","side":"B"}""", type = "PointWon"))
                f.bind()
                assertEquals(CloudUploadOutcome.BLOCKED, f.runner().uploadNow().outcome)
                f.revoked -= WATCH
                f.switchTo("alice")
                assertEquals(CloudUploadOutcome.DRAINED, f.runner().uploadNow().outcome)
                assertEquals(setOf("$LOCAL-w1"), f.committed)
            }
        }

    @Test
    fun lifecycleJsonCarriesAnEpochForEveryReferencedMatch(): Unit =
        runBlocking {
            Fixture().use { f ->
                f.pull(7)
                f.member(CLOUD, 7)
                f.event(CLOUD, 1)
                f.event(LOCAL, 1)
                f.bind()
                assertEquals(CloudUploadOutcome.DRAINED, f.runner().uploadNow().outcome)
                assertEquals(
                    """{"generation":7,"redactionEpochs":{"$CLOUD":0,"$LOCAL":0}}""",
                    f.bodies
                        .single()
                        .getValue("lifecycle")
                        .toString(),
                )
            }
        }

    @Test
    fun knownMatchesWithoutAccessRowKeepUploadingAfterTheFirstPull(): Unit =
        runBlocking {
            Fixture().use { f ->
                // Altbestand: vor dem ersten Pull ohne Beleg hochgeladen, der Server kennt das Match danach.
                f.event(OLD, 1)
                f.bind()
                assertEquals(CloudUploadOutcome.DRAINED, f.runner().uploadNow().outcome)
                f.pull(7)
                f.event(OLD, 2)
                // Neues Match über zwei Batches: Batch 1 legt es an, Batch 2 braucht seinen Eintrag E = 0.
                f.event(LOCAL, 1)
                f.event(LOCAL, 2)
                val runner = f.runner(maxEvents = 1)
                assertEquals(CloudUploadOutcome.LIMIT_REACHED, runner.uploadNow().outcome)
                assertEquals(CloudUploadOutcome.LIMIT_REACHED, runner.uploadNow().outcome)
                assertEquals(CloudUploadOutcome.DRAINED, runner.uploadNow().outcome)
                assertEquals(0, f.data.cloud.pendingCount())
                f.batches.drop(1).forEach { batch ->
                    assertEquals(CloudPushLifecycle(7, batch.events.associate { it.matchId to 0 }), batch.lifecycle)
                }
            }
        }

    @Test
    fun noLifecycleBeforeTheFirstRoundThenLastCompletedGenerationDuringANewRound(): Unit =
        runBlocking {
            Fixture().use { f ->
                f.event(LOCAL, 1)
                f.bind()
                assertEquals(CloudUploadOutcome.DRAINED, f.runner().uploadNow().outcome)
                assertFalse("lifecycle" in f.bodies.single())
                assertEquals(0, f.data.cloud.pendingCount())
                // Eine neue Runde ohne targetEpoch macht das zuletzt abgeschlossene G nicht ungültig.
                f.pull(3)
                f.data.cloudPulls.beginRun("alice") {}
                f.event(LOCAL, 2)
                assertEquals(CloudUploadOutcome.DRAINED, f.runner().uploadNow().outcome)
                assertEquals(CloudPushLifecycle(3, mapOf(LOCAL to 0)), f.batches.last().lifecycle)
            }
        }

    @Test
    fun targetEpochBlocksLocallyWithoutSendingUntilTheRoundCompletes(): Unit =
        runBlocking {
            Fixture().use { f ->
                f.pull(3)
                f.event(LOCAL, 1)
                f.data.players.addGuest(PlayerId("guest"), "Bea", 0)
                f.observe(4)
                f.bind()
                val blocked = f.runner().uploadNow()
                assertEquals(CloudUploadOutcome.BLOCKED, blocked.outcome)
                assertTrue(f.bodies.isEmpty())
                f.pull(4)
                assertEquals(CloudUploadOutcome.DRAINED, f.runner().uploadNow().outcome)
                assertEquals(CloudPushLifecycle(4, mapOf(LOCAL to 0)), f.batches.single().lifecycle)
            }
        }

    @Test
    fun uploadInFlightWhileAPullRoundStartsIsStillAcknowledged(): Unit =
        runBlocking {
            Fixture().use { f ->
                f.pull(5)
                f.event(LOCAL, 1)
                f.bind()
                val runner = f.runner()
                f.hold = CompletableDeferred()
                val upload = async { runner.uploadNow() }
                withTimeout(5_000) { f.sent.await() }
                f.data.cloudPulls.beginRun("alice") {}
                f.hold!!.complete(Unit)
                assertEquals(CloudUploadOutcome.DRAINED, upload.await().outcome)
                assertEquals(0, f.data.cloud.pendingCount())
                assertEquals(1, f.bodies.size)
            }
        }

    @Test
    fun knownPositiveEpochWithoutGenerationIsSkippedInsteadOfProvoking40001(): Unit =
        runBlocking {
            Fixture().use { f ->
                f.member(CLOUD, 1)
                f.redact(CLOUD, 1, 1)
                f.serverMatches += CLOUD
                f.event(CLOUD, 1)
                f.event(LOCAL, 1)
                f.bind()
                assertEquals(CloudUploadOutcome.BLOCKED, f.runner().uploadNow().outcome)
                assertEquals(
                    listOf(LOCAL),
                    f.batches
                        .single()
                        .events
                        .map { it.matchId },
                )
                assertNull(f.batches.single().lifecycle)
                assertEquals(1, f.data.cloud.pendingCount())
            }
        }

    @Test
    fun accountMismatchBetweenGenerationAndReplicaContextBlocks(): Unit =
        runBlocking {
            Fixture().use { f ->
                f.pull(2)
                f.event(LOCAL, 1)
                f.bind()
                // Kontextwechsel zwischen Auswahl und Versand verwirft den Snapshot ohne Request.
                val selected = f.backend.captureUpload()
                f.account("mallory")
                assertTrue(runCatching { f.backend.pushSelected(selected, selected.batch) }.isFailure)
                assertTrue(f.bodies.isEmpty())
                assertEquals(CloudUploadOutcome.BLOCKED, f.runner().uploadNow().outcome)
                assertTrue(f.bodies.isEmpty())
                f.account("alice")
                assertEquals(CloudUploadOutcome.DRAINED, f.runner().uploadNow().outcome)
                assertEquals(CloudPushLifecycle(2, mapOf(LOCAL to 0)), f.batches.single().lifecycle)
            }
        }

    @Test
    fun playerOnlyBatchSendsGenerationWithEmptyEpochs(): Unit =
        runBlocking {
            Fixture().use { f ->
                f.pull(4)
                f.data.players.addGuest(PlayerId("guest"), "Bea", 0)
                f.bind()
                assertEquals(CloudUploadOutcome.DRAINED, f.runner().uploadNow().outcome)
                val batch = f.batches.single()
                assertTrue(batch.events.isEmpty() && batch.matches.isEmpty() && batch.players.isNotEmpty())
                assertEquals(
                    """{"generation":4,"redactionEpochs":{}}""",
                    f.bodies
                        .single()
                        .getValue("lifecycle")
                        .toString(),
                )
            }
        }

    @Test
    fun generationOrEpochChangeBetweenSelectionAndSendDiscardsSnapshot(): Unit =
        runBlocking {
            Fixture().use { f ->
                f.pull(2)
                f.member(CLOUD, 2)
                f.event(CLOUD, 1)
                f.bind()
                val selected = f.backend.captureUpload()
                assertEquals(CloudPushLifecycle(2, mapOf(CLOUD to 0)), selected.batch.lifecycle)
                f.redact(CLOUD, 2, 1)
                val stale = runCatching { f.backend.pushSelected(selected, selected.batch) }.exceptionOrNull()
                assertTrue(stale != null)
                assertTrue(f.bodies.isEmpty())
                val fresh = f.backend.captureUpload()
                assertEquals(CloudPushLifecycle(2, mapOf(CLOUD to 1)), fresh.batch.lifecycle)
                // Neue Sichtbarkeitsepoche ohne neuen Zugriff blockiert das Match, statt ein altes G zu senden.
                f.pull(3)
                val blocked = runCatching { f.backend.pushSelected(fresh, fresh.batch) }.exceptionOrNull()
                assertTrue(blocked != null)
                assertTrue(f.bodies.isEmpty())
                assertTrue(
                    f.backend
                        .captureUpload()
                        .events
                        .isEmpty(),
                )
                assertEquals(1, f.data.cloud.pendingCount())
            }
        }

    @Test
    fun epochChangeBetweenSendAndAckLeavesEverythingPendingWithoutRevisionAcks(): Unit =
        runBlocking {
            Fixture().use { f ->
                f.pull(5)
                f.member(CLOUD, 5)
                f.event(CLOUD, 1)
                f.data.players.addGuest(PlayerId("guest"), "Bea", 0)
                f.bind()
                val runner = f.runner()
                f.hold = CompletableDeferred()
                val upload = async { runner.uploadNow() }
                withTimeout(5_000) { f.sent.await() }
                f.redact(CLOUD, 5, 1)
                f.hold!!.complete(Unit)
                val state = upload.await()
                assertEquals(CloudUploadOutcome.FAILED, state.outcome)
                assertEquals("cloud_lifecycle_changed", state.failure?.reason)
                // Der Status derselben Sitzung bleibt sichtbar.
                assertEquals("alice", state.profileId)
                assertEquals(1L, state.inventory?.pendingEvents)
                assertEquals(1, f.data.cloud.pendingCount())
                assertTrue(
                    f.data.cloud
                        .metadataState(CloudMetadataKind.PLAYER, "guest")!!
                        .let { it.uploadedRevision < it.selected.revision },
                )
                // Retry des Server-Commits mit frischem Beleg ist idempotent.
                f.hold = null
                assertEquals(CloudUploadOutcome.DRAINED, runner.uploadNow().outcome)
                assertEquals(CloudPushLifecycle(5, mapOf(CLOUD to 1)), f.batches.last().lifecycle)
                assertEquals(0, f.data.cloud.pendingCount())
            }
        }

    @Test
    fun lostResponseAfterServerCommitRetriesIdenticalBatchIdempotently(): Unit =
        runBlocking {
            Fixture().use { f ->
                f.pull(9)
                f.member(CLOUD, 9)
                f.event(CLOUD, 1)
                f.bind()
                val runner = f.runner()
                f.loseResponse = true
                assertEquals(CloudUploadFailureKind.TRANSIENT, runner.uploadNow().failure?.kind)
                f.loseResponse = false
                assertEquals(CloudUploadOutcome.DRAINED, runner.uploadNow().outcome)
                assertEquals(f.bodies[0], f.bodies[1])
                assertEquals(0, f.data.cloud.pendingCount())
            }
        }

    @Test
    fun accountSwitchAndRevocationDuringHttpNeverAcknowledge(): Unit =
        runBlocking {
            for (revoke in listOf(false, true)) {
                Fixture().use { f ->
                    f.pull(1)
                    f.member(CLOUD, 1)
                    f.event(CLOUD, 1)
                    f.bind()
                    val runner = f.runner()
                    f.hold = CompletableDeferred()
                    val upload = async { runner.uploadNow() }
                    withTimeout(5_000) { f.sent.await() }
                    if (revoke) {
                        f.pull(2)
                        f.member(CLOUD, 2, ReplicaAccessLevel.FEED)
                    } else {
                        f.credentials = CloudCredentials("bob", "session", "token")
                        f.gate.completeTransition(f.gate.stop())
                        f.gate.publish(f.gate.capture(false), CloudSession("bob", true, consentStatus = ConsentStatus.CURRENT))
                    }
                    f.hold!!.complete(Unit)
                    // Der Kontowechsel bricht den laufenden Request ab; der Entzug verwirft den Snapshot vor dem Ack.
                    val state = runCatching { upload.await() }
                    if (revoke) {
                        assertEquals(CloudUploadOutcome.FAILED, state.getOrThrow().outcome)
                        assertEquals("cloud_lifecycle_changed", state.getOrThrow().failure?.reason)
                        f.hold = null
                        runner.uploadNow()
                        assertTrue(f.batches.drop(1).none { batch -> batch.events.any { it.matchId == CLOUD } })
                    } else {
                        assertTrue(state.exceptionOrNull() is CancellationException)
                        assertEquals(1, f.data.cloud.pendingCount())
                    }
                }
            }
        }

    @Test
    fun restartRequiredBlocksTheWholeProfileUntilALaterRoundCompletesItsCatalog(): Unit =
        runBlocking {
            Fixture().use { f ->
                f.pull(1)
                f.member(CLOUD, 1)
                f.event(CLOUD, 1)
                f.event(LOCAL, 1)
                f.bind()
                f.restartWhen = { batch -> batch.events.any { it.matchId == CLOUD } }
                val runner = f.runner(maxEvents = 1)
                val restart = runner.uploadNow()
                assertEquals(CloudUploadOutcome.FAILED, restart.outcome)
                assertEquals(CloudUploadFailure(CloudUploadFailureKind.TRANSIENT, "restart_required"), restart.failure)
                assertEquals(2, f.data.cloud.pendingCount())
                // Profilweit: auch unbeteiligte Matches warten auf den Pull, statt N Fehlversuche zu kaskadieren.
                assertEquals(CloudUploadOutcome.BLOCKED, runner.uploadNow().outcome)
                assertEquals(1, f.bodies.size)
                // Eine nur begonnene Runde ohne abgeschlossenen Katalog genügt nicht.
                f.data.cloudPulls.beginRun("alice") {}
                assertEquals(CloudUploadOutcome.BLOCKED, runner.uploadNow().outcome)
                assertEquals(1, f.bodies.size)
                // Integratorentscheid: der abgeschlossene Katalog dieser Runde gibt frei, nicht erst das Rundenende.
                f.pull(1, finish = false)
                assertTrue(
                    f.data.cloudPulls
                        .progress("alice")
                        .roundActive,
                )
                f.restartWhen = { false }
                assertEquals(CloudUploadOutcome.LIMIT_REACHED, runner.uploadNow().outcome)
                assertEquals(CloudUploadOutcome.DRAINED, runner.uploadNow().outcome)
                assertEquals(listOf(CLOUD, LOCAL), f.batches.drop(1).flatMap { batch -> batch.events.map { it.matchId } })
                assertEquals(0, f.data.cloud.pendingCount())
            }
        }

    @Test
    fun releasedLockStaysReleasedWhenD1RestartsTheCatalogOfTheReleasingRound(): Unit =
        runBlocking {
            Fixture().use { f ->
                f.pull(1)
                f.event(LOCAL, 1)
                f.bind()
                f.restartWhen = { true }
                val runner = f.runner()
                assertEquals("restart_required", runner.uploadNow().failure?.reason)
                f.restartWhen = { false }
                f.pull(1, finish = false)
                f.hold = CompletableDeferred()
                val upload = async { runner.uploadNow() }
                withTimeout(5_000) { f.sent.await() }
                // D1 erhält in derselben Runde selbst 40001 und holt den Katalog neu: die erteilte Freigabe bleibt.
                val progress = f.data.cloudPulls.progress("alice")
                f.data.cloudPulls.restartCatalog("alice", progress.runSerial) {}
                assertFalse(
                    f.data.cloudPulls
                        .progress("alice")
                        .roundCatalogReady,
                )
                f.hold!!.complete(Unit)
                assertEquals(CloudUploadOutcome.DRAINED, upload.await().outcome)
                f.hold = null
                f.event(LOCAL, 2)
                assertEquals(CloudUploadOutcome.DRAINED, runner.uploadNow().outcome)
                assertEquals(3, f.bodies.size)
                assertEquals(0, f.data.cloud.pendingCount())
            }
        }

    @Test
    fun catalogEpochAboveAppliedEpochBlocksOnlyThatMatchWithout40001(): Unit =
        runBlocking {
            Fixture().use { f ->
                f.pull(1)
                f.event(CLOUD, 1)
                f.event(LOCAL, 1)
                f.bind()
                // Der Katalog meldet E = 1; Access-Zeile entsteht im selben Commit, angewandt ist E = 0, kein Transfer offen.
                f.serverMatches += CLOUD
                f.serverE[CLOUD] = 1
                f.pull(1, finish = false, matches = listOf(CloudPullMatch(CLOUD, 2, "{}", "member", 1, 1, false, null)))
                assertEquals(
                    0,
                    f.data.replicas
                        .access(f.data.replicas.captureContext(), CLOUD)
                        ?.redactionEpoch,
                )
                val runner = f.runner()
                assertEquals(CloudUploadOutcome.BLOCKED, runner.uploadNow().outcome)
                assertEquals(
                    listOf(LOCAL),
                    f.batches
                        .single()
                        .events
                        .map { it.matchId },
                )
                assertEquals(CloudUploadOutcome.BLOCKED, runner.uploadNow().outcome)
                assertEquals(1, f.bodies.size)
                // Transfer abgeschlossen: E lokal angewandt, das Match lädt mit E = 1 hoch.
                f.redact(CLOUD, 1, 1)
                assertEquals(CloudUploadOutcome.DRAINED, runner.uploadNow().outcome)
                assertEquals(CloudPushLifecycle(1, mapOf(CLOUD to 1)), f.batches.last().lifecycle)
                assertEquals(0, f.data.cloud.pendingCount())
            }
        }

    /** 40001, danach eine beendete Freigaberunde mit bestätigtem Upload; liefert den Runner. */
    private suspend fun Fixture.releasedRestartLock(): CloudUploadRunner {
        pull(1)
        event(LOCAL, 1)
        bind()
        restartWhen = { true }
        val runner = runner()
        assertEquals("restart_required", runner.uploadNow().failure?.reason)
        restartWhen = { false }
        pull(1)
        assertEquals(CloudUploadOutcome.DRAINED, runner.uploadNow().outcome)
        assertEquals(2, bodies.size)
        return runner
    }

    @Test
    fun laterPullRoundAfterAReleasedRestartLockDoesNotBlockAgain(): Unit =
        runBlocking {
            Fixture().use { f ->
                val runner = f.releasedRestartLock()
                f.event(LOCAL, 2)
                // Neue Runde ohne targetEpoch: das letzte abgeschlossene G bleibt gültig, die alte Sperre ist erledigt.
                f.pull(1, finish = false)
                assertEquals(CloudUploadOutcome.DRAINED, runner.uploadNow().outcome)
                assertEquals(CloudPushLifecycle(1, mapOf(LOCAL to 0)), f.batches.last().lifecycle)
                assertEquals(3, f.bodies.size)
                assertEquals(0, f.data.cloud.pendingCount())
                // Auch eine neue Instanz liest die erledigte Sperre nicht als offen.
                f.event(LOCAL, 3)
                val restarted = LocalData(f.driver)
                assertEquals(
                    listOf("$LOCAL-e3"),
                    restarted.cloudUploads
                        .capture(restarted.cloudUploads.activate("alice"), 500)
                        .events
                        .map { it.id },
                )
            }
        }

    @Test
    fun uploadInFlightWhileARoundStartsAfterAReleasedRestartLockIsNotResent(): Unit =
        runBlocking {
            Fixture().use { f ->
                val runner = f.releasedRestartLock()
                f.event(LOCAL, 2)
                f.hold = CompletableDeferred()
                val upload = async { runner.uploadNow() }
                withTimeout(5_000) { f.sent.await() }
                f.pull(1, finish = false)
                f.hold!!.complete(Unit)
                assertEquals(CloudUploadOutcome.DRAINED, upload.await().outcome)
                f.hold = null
                assertEquals(CloudUploadOutcome.DRAINED, runner.uploadNow().outcome)
                assertEquals(3, f.bodies.size)
                assertEquals(0, f.data.cloud.pendingCount())
            }
        }

    @Test
    fun roundStartedBeforeTheRestartDoesNotReleaseTheLock(): Unit =
        runBlocking {
            Fixture().use { f ->
                f.pull(1)
                f.event(LOCAL, 1)
                f.bind()
                // Die Runde läuft schon, bevor der 40001 eintrifft; ihr Ende beweist keinen Pull danach.
                f.pull(1, finish = false)
                f.restartWhen = { true }
                val runner = f.runner()
                assertEquals("restart_required", runner.uploadNow().failure?.reason)
                f.restartWhen = { false }
                f.data.cloudPulls.finishRound(
                    "alice",
                    f.data.cloudPulls
                        .progress("alice")
                        .runSerial,
                ) {}
                assertEquals(CloudUploadOutcome.BLOCKED, runner.uploadNow().outcome)
                assertEquals(1, f.bodies.size)
                val restarted = LocalData(f.driver)
                val session = restarted.cloudUploads.activate("alice")
                val locked = restarted.cloudUploads.capture(session, 500)
                assertTrue(locked.events.isEmpty() && locked.players.isEmpty())
                f.pull(1)
                assertEquals(
                    listOf("$LOCAL-e1"),
                    restarted.cloudUploads
                        .capture(session, 500)
                        .events
                        .map { it.id },
                )
            }
        }

    @Test
    fun playerOnlyRestartIsNotResentInALoop(): Unit =
        runBlocking {
            Fixture().use { f ->
                f.pull(4)
                f.data.players.addGuest(PlayerId("guest"), "Bea", 0)
                f.bind()
                f.restartWhen = { true }
                val runner = f.runner()
                assertEquals("restart_required", runner.uploadNow().failure?.reason)
                assertEquals(CloudUploadOutcome.BLOCKED, runner.uploadNow().outcome)
                assertEquals(1, f.bodies.size)
                f.restartWhen = { false }
                f.pull(4)
                assertEquals(CloudUploadOutcome.DRAINED, runner.uploadNow().outcome)
                assertEquals(2, f.bodies.size)
            }
        }

    @Test
    fun restartLockSurvivesANewLocalDataInstance(): Unit =
        runBlocking {
            Fixture().use { f ->
                f.pull(1)
                f.event(LOCAL, 1)
                f.bind()
                f.restartWhen = { true }
                assertEquals(
                    "restart_required",
                    f
                        .runner()
                        .uploadNow()
                        .failure
                        ?.reason,
                )
                val restarted = LocalData(f.driver)
                val session = restarted.cloudUploads.activate("alice")
                val locked = restarted.cloudUploads.capture(session, 500)
                assertTrue(locked.events.isEmpty() && locked.players.isEmpty())
                assertEquals(1L, locked.inventory.pendingEvents)
                f.pull(1)
                assertEquals(
                    listOf("$LOCAL-e1"),
                    restarted.cloudUploads
                        .capture(session, 500)
                        .events
                        .map { it.id },
                )
            }
        }

    @Test
    fun corruptRestartLockFailsClosedUntilTheNextRound(): Unit =
        runBlocking {
            Fixture().use { f ->
                f.pull(1)
                f.event(LOCAL, 1)
                f.bind()
                f.data.settings.put(f.restartKey, "{broken")
                assertEquals(CloudUploadOutcome.BLOCKED, f.runner().uploadNow().outcome)
                assertTrue(f.bodies.isEmpty())
                f.pull(1)
                assertEquals(CloudUploadOutcome.DRAINED, f.runner().uploadNow().outcome)
                assertEquals(0, f.data.cloud.pendingCount())
            }
        }

    @Test
    fun failureWhileLockingNeverEscapesUploadNow(): Unit =
        runBlocking {
            for (deactivate in listOf(false, true)) {
                Fixture().use { f ->
                    f.pull(1)
                    f.event(LOCAL, 1)
                    f.bind()
                    f.restartWhen = { true }
                    val runner = f.runner()
                    f.hold = CompletableDeferred()
                    val upload = async { runner.uploadNow() }
                    withTimeout(5_000) { f.sent.await() }
                    // Beschädigter Wert oder beendete Sitzung genau zwischen 40001 und Sperre.
                    if (deactivate) f.data.cloudUploads.deactivate() else f.data.settings.put(f.restartKey, "{broken")
                    f.hold!!.complete(Unit)
                    val state = upload.await()
                    assertEquals(CloudUploadOutcome.FAILED, state.outcome)
                    assertEquals("restart_required", state.failure?.reason)
                }
            }
        }

    @Test
    fun restartLockStaysWithItsProfileAcrossAccountSwitches(): Unit =
        runBlocking {
            Fixture().use { f ->
                val bob = f.data.cloudUploads.activate("bob")
                f.data.cloudUploads.bindOwnedDevices(bob, setOf("bob-watch"))
                f.data.cloudUploads.deactivate()
                f.switchTo("alice")
                f.data.events.append(
                    StoredEvent("bob-e1", BOB, "bob-watch", 1, 1, """{"type":"PointWon","side":"A"}""", type = "PointWon"),
                )
                f.pull(1)
                f.event(LOCAL, 1)
                f.restartWhen = { true }
                assertEquals(
                    "restart_required",
                    f
                        .runner()
                        .uploadNow()
                        .failure
                        ?.reason,
                )
                f.restartWhen = { false }
                f.switchTo("bob")
                assertEquals(CloudUploadOutcome.DRAINED, f.runner().uploadNow().outcome)
                assertEquals(
                    listOf(BOB),
                    f.batches
                        .last()
                        .events
                        .map { it.matchId },
                )
                f.switchTo("alice")
                assertEquals(CloudUploadOutcome.BLOCKED, f.runner().uploadNow().outcome)
                assertEquals(2, f.bodies.size)
                f.pull(1)
                assertEquals(CloudUploadOutcome.DRAINED, f.runner().uploadNow().outcome)
                assertEquals(
                    listOf(LOCAL),
                    f.batches
                        .last()
                        .events
                        .map { it.matchId },
                )
            }
        }

    @Test
    fun blockedMatchMetadataBeyondTheLimitIsSkippedFairly(): Unit =
        runBlocking {
            Fixture().use { f ->
                f.pull(6)
                // Mehr blockierte Matches (Kopf, Teilnehmer, Genesis) vor dem freien Match, als das Limit erlaubt.
                listOf(CLOUD, BLOCKED_2, BLOCKED_3).forEach {
                    f.createMatch(it)
                    f.member(it, 6, ReplicaAccessLevel.FEED)
                }
                f.createMatch(LOCAL)
                f.bind()
                val runner = f.runner(maxEvents = 1)
                val outcomes = mutableListOf<CloudUploadOutcome>()
                while (outcomes.size < 30 && outcomes.lastOrNull() != CloudUploadOutcome.BLOCKED) outcomes += runner.uploadNow().outcome
                assertEquals(CloudUploadOutcome.BLOCKED, outcomes.last())
                assertTrue(outcomes.dropLast(1).all { it == CloudUploadOutcome.LIMIT_REACHED })
                val blocked = setOf(CLOUD, BLOCKED_2, BLOCKED_3)
                f.batches.forEach { batch ->
                    assertTrue(batch.events.size <= 1 && batch.matches.size + batch.players.size <= 1)
                    assertTrue(batch.matches.none { it.id in blocked } && batch.events.none { it.matchId in blocked })
                }
                assertTrue(f.batches.any { batch -> batch.matches.any { it.id == LOCAL } })
                assertNotNull(f.batches.firstOrNull { batch -> batch.events.any { it.matchId == LOCAL } })
                assertEquals(3, f.data.cloud.pendingCount())
            }
        }

    @Test
    fun blockedMatchDoesNotStarveLaterMatchesBehindTheLimit(): Unit =
        runBlocking {
            Fixture().use { f ->
                f.pull(6)
                f.member(CLOUD, 6)
                f.redact(CLOUD, 6, 1, complete = false)
                (1L..3L).forEach { f.event(CLOUD, it) }
                f.event(LOCAL, 1)
                f.bind()
                val runner = f.runner(maxEvents = 2)
                // Bleiben nach dem Batch nur blockierte Reste, meldet schon dieser Lauf BLOCKED statt LIMIT_REACHED.
                assertEquals(CloudUploadOutcome.BLOCKED, runner.uploadNow().outcome)
                assertEquals(
                    listOf(LOCAL),
                    f.batches
                        .single()
                        .events
                        .map { it.matchId },
                )
                val blocked = runner.uploadNow()
                assertEquals(CloudUploadOutcome.BLOCKED, blocked.outcome)
                assertEquals(3L, blocked.inventory?.pendingEvents)
                assertEquals(1, f.bodies.size)
            }
        }

    @Test
    fun globallyRedactedIdentitiesAreNeverUploadedAndDoNotConsumeTheLimit(): Unit =
        runBlocking {
            Fixture().use { f ->
                f.pull(2)
                f.member(CLOUD, 2)
                f.data.players.addGuest(PlayerId(REDACTED), "Old", 0)
                f.data.players.addGuest(PlayerId("zz-guest"), "Bea", 0)
                f.redact(CLOUD, 2, 1, players = listOf(REDACTED))
                f.bind()
                val runner = f.runner(maxEvents = 1)
                repeat(3) { runner.uploadNow() }
                val players = f.batches.flatMap { batch -> batch.players.map { it.id } }
                assertFalse(REDACTED in players)
                assertTrue("zz-guest" in players)
                assertNull(f.batches.firstOrNull { batch -> batch.players.any { it.id == REDACTED } })
                assertEquals(CloudUploadOutcome.DRAINED, runner.uploadNow().outcome)
            }
        }

    private companion object {
        const val CLOUD = "00000000-0000-4000-8000-000000000001"
        const val BLOCKED_2 = "00000000-0000-4000-8000-000000000002"
        const val BLOCKED_3 = "00000000-0000-4000-8000-000000000003"
        const val LOCAL = "00000000-0000-4000-8000-0000000000f0"
        const val WATCH = "watch-source"
        const val OLD = "00000000-0000-4000-8000-0000000000f1"
        const val BOB = "00000000-0000-4000-8000-0000000000b0"
        const val REDACTED = "00000000-0000-4000-8000-0000000000aa"
    }
}
