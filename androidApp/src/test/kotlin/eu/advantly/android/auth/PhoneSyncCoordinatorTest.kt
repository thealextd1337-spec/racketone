package eu.advantly.android.auth

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.advantly.android.sync.onMatchFinished
import eu.advantly.cloud.sync.CloudInstallation
import eu.advantly.cloud.sync.CloudJson
import eu.advantly.cloud.sync.CloudPullAttempt
import eu.advantly.cloud.sync.CloudPullPort
import eu.advantly.cloud.sync.CloudUploadPort
import eu.advantly.cloud.sync.ConsentStatus
import eu.advantly.cloud.sync.ItemResult
import eu.advantly.cloud.sync.PullResponse
import eu.advantly.cloud.sync.PushBatch
import eu.advantly.cloud.sync.PushResponse
import eu.advantly.cloud.sync.SupabasePhoneAuth
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.PlayerId
import eu.advantly.core.scoring.Presets
import eu.advantly.data.LocalData
import eu.advantly.data.db.AdvantlyDatabase
import eu.advantly.device.link.testing.ManualScheduler
import eu.advantly.match.session.MatchSession
import eu.advantly.match.session.MatchSessions
import eu.advantly.match.session.SessionConfig
import eu.advantly.presentation.auth.PhoneSyncBlock
import eu.advantly.presentation.auth.PhoneSyncState
import eu.advantly.presentation.auth.PhoneSyncStatus
import eu.advantly.sync.protocol.PeerKind
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.minimalConfig
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.encoding.Base64

/** B4: koordinierter Abgleich je Konto über die echten Hosts, SDK-Auth und einen HTTP-Mock des Cloud-Vertrags. */
class PhoneSyncCoordinatorTest {
    private class Hold {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        suspend fun waitForRelease() {
            entered.complete(Unit)
            try {
                release.await()
            } finally {
                if (!release.isCompleted) cancelled.complete(Unit)
                withContext(NonCancellable) { release.await() }
            }
        }
    }

    private class Scheduler : PhoneSyncScheduler {
        val scheduled = CopyOnWriteArrayList<Pair<String, Long>>()
        val periodic = CopyOnWriteArrayList<String>()
        var cancels = 0

        override fun schedule(
            profile: String,
            delayMs: Long,
        ) {
            scheduled += profile to delayMs
        }

        override fun ensurePeriodic(profile: String) {
            periodic += profile
        }

        override fun cancel() {
            cancels++
        }
    }

    /** Überlebt den simulierten Prozesswechsel wie die SharedPreferences der App. */
    private class Record : PhoneSyncRecord {
        val last = HashMap<String, Long>()
        val failures = HashMap<String, Int>()

        override fun lastBackupAtMs(profile: String) = last[profile]

        override fun failures(profile: String) = failures[profile] ?: 0

        override fun save(
            profile: String,
            lastBackupAtMs: Long?,
            failures: Int,
        ) {
            if (lastBackupAtMs == null) last.remove(profile) else last[profile] = lastBackupAtMs
            this.failures[profile] = failures
        }
    }

    private class Fixture(
        scope: CoroutineScope,
        val driver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), AdvantlyDatabase.Schema),
        val record: Record = Record(),
        pulls: (CloudPullPort) -> CloudPullPort = { it },
        uploads: (CloudUploadPort) -> CloudUploadPort = { it },
    ) {
        val thread = Thread.currentThread()
        val data = LocalData(driver)
        val phone = MatchSession(data, null, ManualScheduler(), SessionConfig(MatchSessions.deviceId(data), PeerKind.PHONE))
        val lifecycle = SupervisorJob(scope.coroutineContext[Job])
        val hostScope = CoroutineScope(scope.coroutineContext + lifecycle)
        val trace = CopyOnWriteArrayList<String>()
        var owner = "alice"
        var epoch = 1
        var consent = ConsentStatus.CURRENT
        var deletion = false
        var pullStatus = HttpStatusCode.OK
        var pullOffline = false
        var pushOffline = false
        var reject = false
        var hostAccess = true

        /** Nur das Gate des Koordinators: Seine Prüfung kann der eines Hosts vorauslaufen. */
        var coordinatorAccess = true
        var holdPull: Hold? = null
        var holdPush: Hold? = null
        var nowMs = 1_000_000L
        val scheduler = Scheduler()
        private val guard = { Thread.currentThread() === thread }
        val sdk =
            createSupabaseClient("https://cloud.test", "public") {
                defaultLogLevel = LogLevel.NONE
                install(Auth) { minimalConfig() }
                httpEngine =
                    MockEngine { request ->
                        val path = request.url.encodedPath.substringAfterLast('/')
                        val tracked = path in setOf("bind_cloud_session_v1", "cloud_sync_pull_v1", "cloud_sync_push_v1")
                        if (tracked) trace += "start:$path"
                        var status = HttpStatusCode.OK
                        val response =
                            when (path) {
                                "my_auth_profile" -> {
                                    """{"profileId":"$owner","onboarded":true,"deletionRequested":$deletion,"consentStatus":"$consent"}"""
                                }

                                "bind_cloud_session_v1" -> {
                                    val device =
                                        CloudJson
                                            .parseToJsonElement((request.body as TextContent).text)
                                            .jsonObject
                                            .getValue("p_device_id")
                                            .toString()
                                            .trim('"')
                                    """{"contractVersion":1,"profileId":"$owner","sessionId":"session-$owner-$epoch",""" +
                                        """"installationId":"installation","deviceId":"$device"}"""
                                }

                                "cloud_sync_pull_v1" -> {
                                    holdPull?.waitForRelease()
                                    if (pullOffline) throw IOException("offline")
                                    status = pullStatus
                                    """{"cursor":0,"hasMore":false,"visibilityEpoch":1}"""
                                }

                                "cloud_sync_push_v1" -> {
                                    holdPush?.waitForRelease()
                                    if (pushOffline) throw IOException("offline")
                                    val batch =
                                        CloudJson.decodeFromJsonElement(
                                            PushBatch.serializer(),
                                            CloudJson
                                                .parseToJsonElement((request.body as TextContent).text)
                                                .jsonObject
                                                .getValue("p_batch"),
                                        )
                                    CloudJson.encodeToString(
                                        PushResponse.serializer(),
                                        PushResponse(
                                            devices = batch.devices.map { ItemResult(it.id, "ok") },
                                            players = batch.players.map { ItemResult(it.id, "ok") },
                                            matches =
                                                batch.matches.map {
                                                    ItemResult(
                                                        it.id,
                                                        "updated",
                                                        projection = it.projection?.let { "applied" },
                                                    )
                                                },
                                            events =
                                                batch.events.map {
                                                    if (reject) {
                                                        ItemResult(
                                                            it.id,
                                                            "rejected",
                                                            reason = "device_not_owned",
                                                        )
                                                    } else {
                                                        ItemResult(it.id, "accepted")
                                                    }
                                                },
                                        ),
                                    )
                                }

                                "logout" -> {
                                    "{}"
                                }

                                else -> {
                                    error("Unexpected request: $path")
                                }
                            }
                        if (tracked) trace += "end:$path"
                        respond(response, status, headersOf("Content-Type", "application/json"))
                    }
            }
        val auth = SupabasePhoneAuth(sdk, "secret", data.cloudUploads, hostScope, threadGuard = guard, ownership = phone)
        val backend = auth.cloudBackend(CloudInstallation("installation", "a".repeat(64)))
        private val lane = PhoneCloudLane()
        val upload =
            PhoneUploadHost(auth, phone, uploads(backend), backend::bindDevice, hostScope, { nowMs }, guard, { hostAccess }, lane = lane)
        val pull = PhonePullHost(auth, phone, pulls(backend), backend::bindDevice, lane, hostScope, guard, { hostAccess })
        val sync =
            PhoneSyncCoordinator(auth, phone, pull, upload, scheduler, record, hostScope, { nowMs }, guard, {
                hostAccess &&
                    coordinatorAccess
            })

        init {
            phone.start()
        }

        /** Gespeicherte SDK-Sitzung wie nach einem Prozessstart, noch ohne Profilprüfung. */
        suspend fun restoreSession() {
            sdk.auth.awaitInitialization()
            val claims = """{"sub":"$owner","session_id":"session-$owner-$epoch"}"""
            sdk.auth.importAuthToken(
                "h." + Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(claims.encodeToByteArray()) + ".s",
                autoRefresh = false,
            )
            yield()
        }

        suspend fun signIn() {
            restoreSession()
            auth.refreshProfile()
            yield()
        }

        fun match() =
            phone.create(
                Presets.TENNIS_SINGLES_BO3.format(),
                Lineup(listOf(PlayerId("a")), listOf(PlayerId("b")), PlayerId("a")),
            )

        /** Wartet auf das Ende des automatisch gestarteten ersten Laufs eines neuen Kontexts. */
        suspend fun firstCycle(): PhoneSyncState = withTimeout(15_000) { sync.state.first { it.status != null && !it.running } }

        /** Ein weiterer, ausdrücklich angestoßener Lauf. */
        suspend fun cycle(profile: String? = "alice"): PhoneSyncState {
            withTimeout(15_000) { checkNotNull(sync.request(profile)).join() }
            return sync.state.value
        }

        fun count(entry: String) = trace.count { it == entry }

        suspend fun close() {
            holdPull?.release?.complete(Unit)
            holdPush?.release?.complete(Unit)
            sync.close()
            var drained = 0
            upload.close { drained++ }
            pull.close { drained++ }
            withTimeout(15_000) { while (drained < 2) yield() }
            auth.close()
            lifecycle.cancel()
            phone.close()
        }
    }

    private fun assertSerial(trace: List<String>) {
        var open: String? = null
        for (entry in trace) {
            val path = entry.substringAfter(':')
            if (entry.startsWith("start:")) {
                assertNull("$entry überlappt $open in $trace", open)
                open = path
            } else {
                assertEquals(open, path)
                open = null
            }
        }
    }

    @Test fun everyCycleDownloadsBeforeItUploadsWithoutOverlapAndRecordsTheBackup() =
        runBlocking {
            val f = Fixture(this)
            try {
                f.signIn()
                assertEquals(PhoneSyncStatus.BACKED_UP, f.firstCycle().status)
                assertEquals(listOf("alice"), f.scheduler.periodic.toList())
                f.match()
                f.nowMs += 5_000
                val before = f.trace.size
                val state = f.cycle()
                val cycle = f.trace.drop(before)
                assertEquals(cycle.toString(), listOf("start:cloud_sync_pull_v1", "end:cloud_sync_pull_v1"), cycle.take(2))
                assertTrue(cycle.toString(), "start:cloud_sync_push_v1" in cycle)
                assertSerial(f.trace)
                assertEquals(PhoneSyncStatus.BACKED_UP, state.status)
                assertEquals(0L, f.upload.state.value.pendingEvents)
                assertEquals(f.nowMs, state.lastBackupAtMs)
                assertEquals(f.nowMs, f.record.last["alice"])
                assertNull(state.nextAttemptAtMs)
                assertTrue(f.scheduler.scheduled.isEmpty())
            } finally {
                f.close()
            }
        }

    /** Logout, Kontowechsel, Löschantrag und Einwilligungsentzug: laufender Lauf und geplante Arbeit enden sofort. */
    @Test fun accountBoundariesStopTheRunningCycleAndScheduledWorkOfTheOldAccount() =
        runBlocking {
            for (transition in listOf("logout", "bob", "deletion", "consent")) {
                val f = Fixture(this)
                try {
                    val hold = Hold()
                    f.holdPull = hold
                    f.match()
                    f.signIn()
                    withTimeout(15_000) { hold.entered.await() }
                    assertTrue(f.sync.state.value.running)
                    when (transition) {
                        "logout" -> {
                            f.auth.signOut()
                        }

                        "deletion" -> {
                            f.deletion = true
                            f.auth.refreshProfile()
                        }

                        "consent" -> {
                            f.consent = ConsentStatus.REQUIRED
                            f.auth.refreshProfile()
                        }

                        else -> {
                            f.owner = "bob"
                            f.epoch++
                            f.signIn()
                        }
                    }
                    withTimeout(15_000) { hold.cancelled.await() }
                    withTimeout(15_000) { while (f.scheduler.cancels == 0) yield() }
                    assertEquals(transition, 1, f.scheduler.cancels)
                    // Ein für Alice geplanter Auftrag läuft nie unter dem neuen Zustand.
                    assertNull(transition, f.sync.request("alice"))
                    hold.release.complete(Unit)
                    delay(300)
                    withTimeout(15_000) { while (f.sync.state.value.running || f.upload.state.value.busy) yield() }
                    assertFalse(
                        transition,
                        f.data.cloudPulls
                            .progress("alice")
                            .metadataComplete,
                    )
                    assertNull(transition, f.record.last["alice"])
                    if (transition != "bob") {
                        assertEquals(transition, PhoneSyncState(), f.sync.state.value)
                        assertEquals(transition, 0, f.count("start:cloud_sync_push_v1"))
                    }
                } finally {
                    f.close()
                }
            }
        }

    /** Schließt das Gate, während ein Upload noch antwortet, wird dessen Ergebnis weder gespeichert noch geplant. */
    @Test fun aResultArrivingAfterTheGateClosedIsNeverRecordedOrScheduled() =
        runBlocking {
            val f = Fixture(this)
            try {
                f.signIn()
                f.firstCycle()
                val backedUpAt = f.nowMs
                f.nowMs += 60_000
                f.match()
                val hold = Hold()
                f.holdPush = hold
                val job = checkNotNull(f.sync.request("alice"))
                withTimeout(15_000) { hold.entered.await() }
                f.coordinatorAccess = false
                hold.release.complete(Unit)
                withTimeout(15_000) { job.join() }
                assertEquals(backedUpAt, f.record.last["alice"])
                assertEquals(0, f.record.failures["alice"])
                assertTrue(f.scheduler.scheduled.isEmpty())
                assertEquals(PhoneSyncState(), f.sync.state.value)
            } finally {
                f.close()
            }
        }

    @Test fun failuresBackOffUpToTheCapAndSuccessResetsTheSeries() =
        runBlocking {
            assertEquals(30_000L, PhoneSyncCoordinator.backoffMs(1))
            assertEquals(60_000L, PhoneSyncCoordinator.backoffMs(2))
            assertEquals(1_920_000L, PhoneSyncCoordinator.backoffMs(7))
            assertEquals(3_600_000L, PhoneSyncCoordinator.backoffMs(8))
            assertEquals(3_600_000L, PhoneSyncCoordinator.backoffMs(Int.MAX_VALUE))
            val f = Fixture(this)
            try {
                f.pullOffline = true
                f.signIn()
                var state = f.firstCycle()
                assertEquals(PhoneSyncStatus.CONNECTION_FAILED, state.status)
                assertEquals(f.nowMs + 30_000, state.nextAttemptAtMs)
                for (failures in 2..10) state = f.cycle()
                assertEquals(10, f.record.failures["alice"])
                assertEquals(
                    (1..10).map { "alice" to PhoneSyncCoordinator.backoffMs(it) },
                    f.scheduler.scheduled.toList(),
                )
                assertEquals(
                    3_600_000L,
                    f.scheduler.scheduled
                        .last()
                        .second,
                )
                // Der Upload kam trotz gescheitertem Download durch: gesichert, aber der Lauf gilt als fehlgeschlagen.
                assertEquals(f.nowMs, state.lastBackupAtMs)
                // Netz wieder da: der Verbindungsfehler wird sofort wiederholt, nicht erst nach dem Backoff.
                f.pullOffline = false
                f.sync.onNetworkAvailable()
                assertTrue(f.sync.state.value.running)
                withTimeout(15_000) { f.sync.state.first { !it.running } }
                assertEquals(PhoneSyncStatus.BACKED_UP, f.sync.state.value.status)
                assertEquals(0, f.record.failures["alice"])
                assertEquals(10, f.scheduler.scheduled.size)
                assertNull(f.sync.state.value.nextAttemptAtMs)
                // Nach Erfolg kein Lauf je Netzwechsel.
                f.sync.onNetworkAvailable()
                assertFalse(f.sync.state.value.running)
            } finally {
                f.close()
            }
        }

    @Test fun blockedNamesItsReasonAndNeverLoopsOrSchedules() =
        runBlocking {
            for (case in listOf("permission", "rejected")) {
                val f = Fixture(this)
                try {
                    if (case == "permission") f.pullStatus = HttpStatusCode.Forbidden else f.reject = true
                    f.signIn()
                    f.firstCycle()
                    f.match()
                    val state = f.cycle()
                    assertEquals(case, PhoneSyncStatus.BLOCKED, state.status)
                    assertEquals(
                        case,
                        if (case == "permission") PhoneSyncBlock.PERMISSION else PhoneSyncBlock.UPLOAD_REJECTED,
                        state.blocked,
                    )
                    assertNull(case, state.nextAttemptAtMs)
                    val pulls = f.count("start:cloud_sync_pull_v1")
                    f.sync.onNetworkAvailable()
                    f.sync.onForeground()
                    delay(300)
                    assertFalse(case, f.sync.state.value.running)
                    assertEquals(case, pulls, f.count("start:cloud_sync_pull_v1"))
                    assertTrue(case, f.scheduler.scheduled.isEmpty())
                    assertEquals(case, 0, f.record.failures["alice"])
                } finally {
                    f.close()
                }
            }
        }

    /** Begrenzte Läufe: höchstens MAX_PASSES Runden je Auslöser, danach geplante Fortsetzung, auch nach Prozessende. */
    @Test fun limitReachedContinuesWithinTheBudgetThenResumesFromThePersistedCursorAfterProcessDeath() =
        runBlocking {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), AdvantlyDatabase.Schema)
            val record = Record()
            val cursors = CopyOnWriteArrayList<Long>()
            var runs = 0
            val endless: (CloudPullPort) -> CloudPullPort = { backend ->
                object : CloudPullPort by backend {
                    override fun capturePull(): CloudPullAttempt = backend.capturePull().also { runs++ }

                    override suspend fun pullPage(
                        attempt: CloudPullAttempt,
                        cursor: Long,
                        limit: Int,
                    ): PullResponse {
                        cursors += cursor
                        return PullResponse(cursor = cursor + 1, hasMore = true, visibilityEpoch = 1)
                    }
                }
            }
            try {
                val first = Fixture(this, driver, record, endless)
                try {
                    first.signIn()
                    val state = first.firstCycle()
                    assertEquals(PhoneSyncStatus.PENDING, state.status)
                    assertEquals(listOf("alice" to PhoneSyncCoordinator.CONTINUE_DELAY_MS), first.scheduler.scheduled.toList())
                    assertEquals(first.nowMs + PhoneSyncCoordinator.CONTINUE_DELAY_MS, state.nextAttemptAtMs)
                    assertEquals((0L until cursors.size).toList(), cursors.toList())
                    // Ein Auslöser: genau MAX_PASSES begrenzte Pull-Läufe hintereinander, dann Planung statt Dauerlauf.
                    assertEquals(PhoneSyncCoordinator.MAX_PASSES, runs)
                    assertEquals(0, record.failures["alice"])
                } finally {
                    first.close()
                }
                // Neuer Prozess: dieselbe Datenbank und derselbe gespeicherte Stand; der geplante Auftrag setzt fort.
                val resumed = cursors.size.toLong()
                val second = Fixture(this, driver, record, endless)
                try {
                    second.restoreSession()
                    assertNull(second.sync.request("alice"))
                    second.auth.refreshProfile()
                    second.firstCycle()
                    assertEquals(resumed, cursors[resumed.toInt()])
                } finally {
                    second.close()
                }
            } finally {
                driver.close()
            }
        }

    @Test fun theBackoffSeriesAndTheLastBackupSurviveProcessDeath() =
        runBlocking {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), AdvantlyDatabase.Schema)
            val record = Record()
            try {
                val backedUpAt: Long
                val first = Fixture(this, driver, record)
                try {
                    first.signIn()
                    assertEquals(PhoneSyncStatus.BACKED_UP, first.firstCycle().status)
                    backedUpAt = first.nowMs
                    first.nowMs += 60_000
                    first.match()
                    first.pullOffline = true
                    first.pushOffline = true
                    assertEquals(PhoneSyncStatus.CONNECTION_FAILED, first.cycle().status)
                    assertEquals(listOf("alice" to 30_000L), first.scheduler.scheduled.toList())
                } finally {
                    first.close()
                }
                // Neuer Prozess, weiter offline: Die Serie setzt fort statt neu bei 30 s, „zuletzt gesichert“ bleibt.
                val second = Fixture(this, driver, record)
                try {
                    second.nowMs += 120_000
                    second.pullOffline = true
                    second.pushOffline = true
                    second.signIn()
                    val state = second.firstCycle()
                    assertEquals(PhoneSyncStatus.CONNECTION_FAILED, state.status)
                    assertEquals(listOf("alice" to 60_000L), second.scheduler.scheduled.toList())
                    assertEquals(2, record.failures["alice"])
                    assertEquals(backedUpAt, state.lastBackupAtMs)
                    assertTrue(second.upload.state.value.pendingEvents!! > 0)
                } finally {
                    second.close()
                }
            } finally {
                driver.close()
            }
        }

    /** Wie im Hintergrund: gespeicherte Sitzung ohne geprüftes Profil und geschlossene Gates bleiben ohne Netzanfrage. */
    @Test fun scheduledWorkChecksTheSameGatesAndItsAccount() =
        runBlocking {
            for (gate in listOf("unverified", "restore", "consent", "deletion", "signed-out")) {
                val f = Fixture(this)
                try {
                    when (gate) {
                        "unverified" -> {
                            f.restoreSession()
                        }

                        "restore" -> {
                            f.hostAccess = false
                            f.signIn()
                        }

                        "consent" -> {
                            f.consent = ConsentStatus.REQUIRED
                            f.signIn()
                        }

                        "deletion" -> {
                            f.deletion = true
                            f.signIn()
                        }

                        else -> {
                            Unit
                        }
                    }
                    assertNull(gate, f.sync.request("alice"))
                    assertNull(gate, f.sync.request(null))
                    delay(100)
                    assertTrue(gate + f.trace, f.trace.isEmpty())
                    assertFalse(gate, f.sync.state.value.available)
                } finally {
                    f.close()
                }
            }
            val f = Fixture(this)
            try {
                f.restoreSession()
                f.auth.refreshProfile()
                // Der Profilwechsel startete den Lauf schon; der Auftrag schließt sich an, statt einen zweiten zu erzwingen.
                val job = checkNotNull(f.sync.request("alice"))
                assertSame(job, f.sync.request("alice"))
                withTimeout(15_000) { job.join() }
                withTimeout(15_000) { while (f.sync.state.value.running) yield() }
                assertEquals(1, f.count("start:cloud_sync_pull_v1"))
                assertNull(f.sync.request("bob"))
                assertEquals(1, f.count("start:cloud_sync_pull_v1"))
            } finally {
                f.close()
            }
        }

    /**
     * Ändern sich lokale Daten während des Uploads (z. B. Matchende), folgt genau ein Lauf, nie eine Kette. Weitere
     * Auslöser während eines Laufs schließen sich ihm an; der Vordergrund-Auslöser ist gedrosselt.
     */
    @Test fun changesDuringTheUploadGetOneFollowUpTriggersJoinTheRunningCycleAndForegroundIsThrottled() =
        runBlocking {
            var live = false
            lateinit var f: Fixture
            var match: MatchId? = null
            f =
                Fixture(this, uploads = { backend ->
                    object : CloudUploadPort by backend {
                        override fun captureUpload(limit: Int) =
                            backend.captureUpload(limit).also {
                                // Auch jeder Folgelauf sieht eine Änderung: ohne Grenze liefe der Abgleich endlos.
                                val id = match
                                if (live && id != null) {
                                    f.phone.closeMatch(id)
                                    f.phone.openInBackground(id)
                                }
                            }
                    }
                })
            try {
                f.signIn()
                f.firstCycle()
                match = f.match()
                live = true
                withTimeout(15_000) { checkNotNull(f.sync.request(null)).join() }
                withTimeout(15_000) { while (f.sync.state.value.running) yield() }
                delay(200)
                assertFalse(f.sync.state.value.running)
                assertEquals("erster Lauf, Lauf, genau ein Folgelauf", 3, f.count("start:cloud_sync_pull_v1"))
                live = false

                val hold = Hold()
                f.holdPull = hold
                val running = checkNotNull(f.sync.request(null))
                withTimeout(15_000) { hold.entered.await() }
                assertSame(running, f.sync.request("alice"))
                assertSame(running, f.sync.request(null))
                f.sync.syncNow()
                f.holdPull = null
                hold.release.complete(Unit)
                withTimeout(15_000) { running.join() }
                withTimeout(15_000) { while (f.sync.state.value.running) yield() }
                assertEquals(4, f.count("start:cloud_sync_pull_v1"))
                assertSerial(f.trace)

                f.sync.onForeground()
                assertFalse("gedrosselt", f.sync.state.value.running)
                f.nowMs += PhoneSyncCoordinator.FOREGROUND_INTERVAL_MS
                f.sync.onForeground()
                assertTrue(f.sync.state.value.running)
                withTimeout(15_000) { while (f.sync.state.value.running) yield() }
                assertEquals(5, f.count("start:cloud_sync_pull_v1"))
            } finally {
                f.close()
            }
        }

    @Test fun onlyAFinishedMatchTriggersTheMatchEndSync() =
        runBlocking {
            val f = Fixture(this)
            var finished = 0
            val remove = onMatchFinished(f.phone) { finished++ }
            try {
                val id = f.match()
                f.phone.recordPoint(eu.advantly.core.model.Side.A)
                f.phone.closeMatch(id)
                assertEquals("Punkte und Schließen der Anzeige sind kein Matchende", 0, finished)
                f.match()
                assertTrue(f.phone.endMatch())
                assertEquals(1, finished)
                f.phone.recordPoint(eu.advantly.core.model.Side.A)
                assertEquals(1, finished)
            } finally {
                remove()
                f.close()
            }
        }
}
