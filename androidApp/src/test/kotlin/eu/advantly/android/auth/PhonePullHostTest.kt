package eu.advantly.android.auth

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.advantly.cloud.sync.CloudInstallation
import eu.advantly.cloud.sync.CloudJson
import eu.advantly.cloud.sync.ConsentStatus
import eu.advantly.cloud.sync.ItemResult
import eu.advantly.cloud.sync.PushBatch
import eu.advantly.cloud.sync.PushResponse
import eu.advantly.cloud.sync.SupabasePhoneAuth
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.PlayerId
import eu.advantly.core.scoring.Presets
import eu.advantly.data.CloudUploadSession
import eu.advantly.data.LocalAccountOwnership
import eu.advantly.data.LocalData
import eu.advantly.data.db.AdvantlyDatabase
import eu.advantly.device.link.testing.ManualScheduler
import eu.advantly.match.session.MatchSession
import eu.advantly.match.session.MatchSessions
import eu.advantly.match.session.SessionConfig
import eu.advantly.presentation.auth.AuthViewModel
import eu.advantly.presentation.auth.PhonePullBlock
import eu.advantly.presentation.auth.PhonePullResult
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
import kotlinx.serialization.json.jsonPrimitive
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

class PhonePullHostTest {
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

    /** Pull- und Uploadhost eines Kontos auf einer gemeinsamen Sperre, wie in [createPhoneAccount]. */
    private class Fixture(
        scope: CoroutineScope,
        val driver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), AdvantlyDatabase.Schema),
    ) {
        val thread = Thread.currentThread()
        val data = LocalData(driver)
        val phone = MatchSession(data, null, ManualScheduler(), SessionConfig(MatchSessions.deviceId(data), PeerKind.PHONE))
        val lifecycle = SupervisorJob(scope.coroutineContext[Job])
        val hostScope = CoroutineScope(scope.coroutineContext + lifecycle)

        /** Start/Ende jedes Bind-, Pull- und Push-Requests in Eingangsreihenfolge. */
        val trace = CopyOnWriteArrayList<String>()
        val bindings = CopyOnWriteArrayList<String>()
        val activations = CopyOnWriteArrayList<String>()
        var owner = "alice"
        var epoch = 1
        var consent = ConsentStatus.CURRENT
        var deletion = false
        var offline = false
        var pullStatus = HttpStatusCode.OK
        var pullBody = """{"cursor":0,"hasMore":false,"visibilityEpoch":1}"""
        var pullOffline = false
        var hostAccess = true

        /** Aus: Die Auth übergeht SDK-Wechsel, Sitzungsflow und Konto-Identitäten bleiben auf dem alten Stand. */
        var authSync = true
        var afterBind: suspend () -> Unit = {}
        var holdPull: Hold? = null
        var holdPush: Hold? = null
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
                                    if (offline) throw IOException("offline")
                                    """{"profileId":"$owner","onboarded":true,"deletionRequested":$deletion,"consentStatus":"$consent"}"""
                                }

                                "bind_cloud_session_v1" -> {
                                    val body = CloudJson.parseToJsonElement((request.body as TextContent).text).jsonObject
                                    val device = body.getValue("p_device_id").jsonPrimitive.content
                                    val token = request.headers["Authorization"]!!.removePrefix("Bearer ")
                                    val claims =
                                        CloudJson
                                            .parseToJsonElement(
                                                Base64.UrlSafe
                                                    .withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
                                                    .decode(token.split('.')[1])
                                                    .decodeToString(),
                                            ).jsonObject
                                    bindings += device
                                    """{"contractVersion":1,"profileId":"${claims.getValue("sub").jsonPrimitive.content}",""" +
                                        """"sessionId":"${claims.getValue("session_id").jsonPrimitive.content}",""" +
                                        """"installationId":"installation","deviceId":"$device"}"""
                                }

                                "cloud_sync_pull_v1" -> {
                                    holdPull?.waitForRelease()
                                    if (pullOffline) throw IOException("offline")
                                    status = pullStatus
                                    pullBody
                                }

                                "cloud_sync_push_v1" -> {
                                    val batch =
                                        CloudJson.decodeFromJsonElement(
                                            PushBatch.serializer(),
                                            CloudJson
                                                .parseToJsonElement((request.body as TextContent).text)
                                                .jsonObject
                                                .getValue("p_batch"),
                                        )
                                    holdPush?.waitForRelease()
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
                                            events = batch.events.map { ItemResult(it.id, "accepted") },
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
        private val ownership =
            object : LocalAccountOwnership by phone {
                override fun activateAccount(
                    profileId: String,
                    session: CloudUploadSession,
                ) {
                    activations += profileId
                    phone.activateAccount(profileId, session)
                }
            }
        val auth =
            SupabasePhoneAuth(
                sdk,
                "secret",
                data.cloudUploads,
                hostScope,
                threadGuard = { Thread.currentThread() === thread },
                ownership = ownership,
                hostAccessAllowed = { authSync },
            )
        val backend = auth.cloudBackend(CloudInstallation("installation", "a".repeat(64)))
        private val lane = PhoneCloudLane()
        val upload =
            PhoneUploadHost(
                auth,
                phone,
                backend,
                backend::bindDevice,
                hostScope,
                { 1000L },
                { Thread.currentThread() === thread },
                lane = lane,
            )
        val pull =
            PhonePullHost(
                auth,
                phone,
                backend,
                {
                    backend.bindDevice(it)
                    afterBind()
                },
                lane,
                hostScope,
                { Thread.currentThread() === thread },
                hostAccessAllowed = { hostAccess },
            )

        init {
            phone.start()
        }

        /** Entspricht einer beim App-Start aus dem Speicher geladenen SDK-Sitzung, ohne Profilabruf. */
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

        suspend fun idle() {
            withTimeout(15_000) { while (upload.state.value.busy || pull.state.value.busy) yield() }
        }

        suspend fun close(closeDriver: Boolean = true) {
            holdPull?.release?.complete(Unit)
            holdPush?.release?.complete(Unit)
            var drained = 0
            upload.close { drained++ }
            pull.close { drained++ }
            withTimeout(15_000) { while (drained < 2) yield() }
            auth.close()
            lifecycle.cancel()
            phone.close()
            if (closeDriver) driver.close()
        }
    }

    /** Kein Bind-, Pull- oder Push-Request beginnt, solange ein anderer desselben Kontos offen ist. */
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

    @Test fun concurrentPullAndUploadOfOneAccountNeverOverlapAndShareOneBinding() =
        runBlocking {
            for (pullFirst in listOf(true, false)) {
                val f = Fixture(this)
                try {
                    f.signIn()
                    f.match()
                    val hold = Hold()
                    if (pullFirst) f.holdPull = hold else f.holdPush = hold
                    if (pullFirst) f.pull.pullNow() else f.upload.uploadNow()
                    withTimeout(15_000) { hold.entered.await() }
                    if (pullFirst) f.upload.uploadNow() else f.pull.pullNow()
                    delay(200)
                    val second = if (pullFirst) "start:cloud_sync_push_v1" else "start:cloud_sync_pull_v1"
                    assertFalse(f.trace.toString(), second in f.trace)
                    assertTrue(f.upload.state.value.busy && f.pull.state.value.busy)
                    hold.release.complete(Unit)
                    f.idle()
                    assertTrue(f.trace.toString(), second in f.trace)
                    assertSerial(f.trace)
                    assertEquals(listOf(f.phone.deviceId.value), f.bindings.toList())
                    assertEquals(PhonePullResult.COMPLETE, f.pull.state.value.result)
                } finally {
                    f.close()
                }
            }
        }

    @Test fun logoutAccountChangeConsentAndDeletionStopRunningPullAndDiscardItsPage() =
        runBlocking {
            for (transition in listOf("none", "consent", "logout", "bob", "alice-again", "deletion")) {
                val f = Fixture(this)
                try {
                    f.signIn()
                    val hold = Hold()
                    f.holdPull = hold
                    f.pull.pullNow()
                    withTimeout(15_000) { hold.entered.await() }
                    when (transition) {
                        "none" -> {}

                        "consent" -> {
                            f.consent = ConsentStatus.REQUIRED
                            f.auth.refreshProfile()
                        }

                        "deletion" -> {
                            f.deletion = true
                            f.auth.refreshProfile()
                        }

                        "logout" -> {
                            f.auth.signOut()
                        }

                        else -> {
                            f.owner = if (transition == "bob") "bob" else "alice"
                            f.epoch++
                            f.signIn()
                        }
                    }
                    if (transition != "none") {
                        withTimeout(15_000) { hold.cancelled.await() }
                        assertNull(f.pull.state.value.result)
                    }
                    hold.release.complete(Unit)
                    f.idle()
                    val applied =
                        f.data.cloudPulls
                            .progress("alice")
                            .metadataComplete
                    if (transition == "none") {
                        assertTrue(applied)
                        assertEquals(PhonePullResult.COMPLETE, f.pull.state.value.result)
                    } else {
                        assertFalse(transition, applied)
                        assertFalse(
                            transition,
                            f.data.cloudPulls
                                .progress("bob")
                                .metadataComplete,
                        )
                        assertNull(transition, f.pull.state.value.result)
                    }
                } finally {
                    f.close()
                }
            }
        }

    @Test fun blockedAndFailedPullsAreVisibleWithReasonAndOnlyRequestFailuresCountAsConnection() =
        runBlocking {
            for ((case, expected) in listOf(
                "forbidden" to PhonePullResult.BLOCKED,
                "server" to PhonePullResult.CONNECTION_FAILED,
                "offline" to PhonePullResult.CONNECTION_FAILED,
                "invalid" to PhonePullResult.FAILED,
            )) {
                val f = Fixture(this)
                try {
                    f.signIn()
                    when (case) {
                        "forbidden" -> f.pullStatus = HttpStatusCode.Forbidden
                        "server" -> f.pullStatus = HttpStatusCode.InternalServerError
                        "offline" -> f.pullOffline = true
                        "invalid" -> f.pullBody = """{"cursor":-1,"hasMore":false,"visibilityEpoch":1}"""
                    }
                    f.pull.pullNow()
                    f.idle()
                    assertEquals(case, expected, f.pull.state.value.result)
                    assertEquals(if (expected == PhonePullResult.BLOCKED) PhonePullBlock.PERMISSION else null, f.pull.state.value.blocked)
                } finally {
                    f.close()
                }
            }
        }

    @Test fun pullHostExistsOnlyBehindTheReplicaActivationSwitch() {
        assertFalse(PHONE_REPLICA_ACTIVATION)
        // Ausgeschaltet: kein Host, also weder Pull-Abschnitt noch Request oder Bind.
        assertNull(phonePullHost { error("Pull-Host ohne aktiviertes Replikat") })
        runBlocking {
            val f = Fixture(this)
            try {
                assertSame(f.pull, phonePullHost(replicaActivation = true) { f.pull })
            } finally {
                f.close()
            }
        }
    }

    @Test fun pullActivatesTheReplicaOnlyBehindTheAccountGate() =
        runBlocking {
            val f = Fixture(this)
            try {
                f.pull.pullNow()
                f.idle()
                // Ohne gültige Kontositzung weder Aktivierung noch Netzwerk.
                assertNull(f.data.settings.get("replica.activated"))
                assertTrue(f.trace.isEmpty())
                f.signIn()
                f.pull.pullNow()
                f.idle()
                assertEquals("true", f.data.settings.get("replica.activated"))
                assertEquals(PhonePullResult.COMPLETE, f.pull.state.value.result)
            } finally {
                f.close()
            }
        }

    /** Der Sitzungsflow hinkt dem SDK nach; erst die Auth-Lease im Fence erkennt den Wechsel zu Bob. */
    @Test fun accountSwitchDuringActivationNeverActivatesUnderTheOldAccount() =
        runBlocking {
            val f = Fixture(this)
            try {
                f.signIn()
                f.afterBind = {
                    f.authSync = false
                    f.owner = "bob"
                    f.epoch++
                    f.restoreSession()
                    delay(50)
                    f.authSync = true
                }
                f.pull.pullNow()
                f.idle()
                assertNull(f.data.settings.get("replica.activated"))
                assertFalse(f.trace.toString(), "start:cloud_sync_pull_v1" in f.trace)
                assertFalse(
                    f.data.cloudPulls
                        .progress("alice")
                        .metadataComplete,
                )
            } finally {
                f.close()
            }
        }

    @Test fun closedRestoreGatePreventsActivationPullAndBinding() =
        runBlocking {
            for (closeDuringBind in listOf(false, true)) {
                val f = Fixture(this)
                try {
                    f.signIn()
                    if (closeDuringBind) f.afterBind = { f.hostAccess = false } else f.hostAccess = false
                    f.pull.pullNow()
                    f.idle()
                    assertNull(f.data.settings.get("replica.activated"))
                    assertFalse(f.trace.toString(), "start:cloud_sync_pull_v1" in f.trace)
                    assertEquals(if (closeDuringBind) 1 else 0, f.bindings.size)
                    assertFalse(f.pull.state.value.available)
                } finally {
                    f.close()
                }
            }
        }

    /** Ohne Host-Abbruch wartete der alte Pull nach dem Kontextwechsel weiter auf die Kontosperre. */
    @Test fun contextChangeCancelsTheHostActionWhileItWaitsForTheAccountLock() =
        runBlocking {
            for (transition in listOf("logout", "bob")) {
                val f = Fixture(this)
                val lock = PhoneCloudLane().mutex("alice")
                try {
                    f.signIn()
                    lock.lock()
                    f.pull.pullNow()
                    yield()
                    assertTrue(f.pull.state.value.busy)
                    if (transition == "logout") {
                        f.auth.signOut()
                    } else {
                        f.owner = "bob"
                        f.epoch++
                        f.signIn()
                    }
                    withTimeout(5_000) { while (f.pull.state.value.busy) yield() }
                    lock.unlock()
                    delay(100)
                    assertTrue(f.trace.toString(), f.trace.isEmpty())
                } finally {
                    if (lock.isLocked) lock.unlock()
                    f.close()
                }
            }
        }

    /** Zwei Konto-ViewModels desselben Kontos (Multi-Window, Drain): je eigene Bindung, aber eine Sperre im Prozess. */
    @Test fun hostsOfTwoViewModelsOfOneAccountShareTheProcessWideLock() =
        runBlocking {
            val f = Fixture(this)
            val other =
                PhoneUploadHost(
                    f.auth,
                    f.phone,
                    f.backend,
                    f.backend::bindDevice,
                    f.hostScope,
                    { 1000L },
                    { Thread.currentThread() === f.thread },
                    lane = PhoneCloudLane(),
                )
            try {
                f.signIn()
                f.match()
                val hold = Hold()
                f.holdPull = hold
                f.pull.pullNow()
                withTimeout(15_000) { hold.entered.await() }
                other.uploadNow()
                delay(200)
                assertEquals(
                    f.trace.toString(),
                    listOf("start:bind_cloud_session_v1", "end:bind_cloud_session_v1", "start:cloud_sync_pull_v1"),
                    f.trace.toList(),
                )
                hold.release.complete(Unit)
                f.idle()
                withTimeout(15_000) { while (other.state.value.busy) yield() }
                assertTrue(f.trace.toString(), "start:cloud_sync_push_v1" in f.trace)
                assertSerial(f.trace)
            } finally {
                var drained = false
                other.close { drained = true }
                withTimeout(15_000) { while (!drained) yield() }
                f.close()
            }
        }

    @Test fun everyAppStartWithStoredSessionActivatesTheAccountThroughTheAuthModel() =
        runBlocking {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), AdvantlyDatabase.Schema)
            try {
                val first = Fixture(this, driver)
                first.signIn()
                assertEquals(listOf("alice"), first.activations.toList())
                first.close(closeDriver = false)
                for (offline in listOf(false, true)) {
                    // Neuer Prozess: gleiche Datenbank, gespeicherte SDK-Sitzung, frische Gates.
                    val restarted = Fixture(this, driver)
                    try {
                        restarted.offline = offline
                        restarted.restoreSession()
                        assertTrue(restarted.activations.isEmpty())
                        val model = AuthViewModel(restarted.auth, restarted.hostScope, upload = restarted.upload, pull = restarted.pull)
                        if (offline) {
                            delay(200)
                            // Ohne bestätigtes Profil keine Aktivierung und kein Cloud-Lauf.
                            assertTrue(restarted.activations.isEmpty())
                            assertFalse(restarted.pull.state.value.available)
                            restarted.offline = false
                            model.refresh()
                        }
                        withTimeout(15_000) { while (restarted.activations.isEmpty()) yield() }
                        assertEquals(listOf("alice"), restarted.activations.toList())
                        withTimeout(15_000) { restarted.pull.state.first { it.available } }
                    } finally {
                        restarted.close(closeDriver = false)
                    }
                }
            } finally {
                driver.close()
            }
        }
}
