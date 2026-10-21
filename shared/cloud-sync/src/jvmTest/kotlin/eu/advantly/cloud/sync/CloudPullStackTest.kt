package eu.advantly.cloud.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.advantly.data.LocalData
import eu.advantly.data.db.AdvantlyDatabase
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import java.io.File
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Externe D1-Abnahme, ausdrücklich kein Mock-/SQL-Ersatz.
 * ADVANTLY_D1_STACK_FIXTURE zeigt auf eine außerhalb des Repos erzeugte JSON-Datei mit:
 * confirmDisposable=true, apiUrl (HTTP-Loopback), apiKey (anon), email/password,
 * noConsentEmail/noConsentPassword, matchId, expectedMatches>=201, expectedEvents>=501.
 * Der wegwerfbare echte Supabase-Stack muss alle bestehenden Migrationen, aktuelle freigegebene
 * TEST-Zustimmungen und zwei echte Auth-Konten enthalten. Das erste Konto sieht die angegebene
 * Anzahl vollständig enginefähiger Matches und das Eventmatch; das zweite hat keine Zustimmung.
 * Der Test meldet ohne Fixture SKIPPED. Ein grüner lokaler Check ist keine Stackabnahme.
 */
class CloudPullStackTest {
    @Test
    fun realAuthPostgrestRunnerPagingReopenGapRepairAndDeniedRequests() {
        val path = System.getenv("ADVANTLY_D1_STACK_FIXTURE")
        assumeTrue("D1-Stackabnahme offen: kein wegwerfbarer Supabase-Stack konfiguriert", !path.isNullOrBlank())
        val fixture = CloudJson.parseToJsonElement(File(requireNotNull(path)).readText()).jsonObject

        fun field(key: String): String = fixture.getValue(key).jsonPrimitive.content
        require(field("confirmDisposable") == "true")
        val url = URI(field("apiUrl"))
        require(url.scheme == "http" && url.host in setOf("127.0.0.1", "localhost"))
        require(url.rawUserInfo == null && url.rawQuery == null && url.rawFragment == null && url.path.orEmpty() in setOf("", "/"))
        val expectedMatches = field("expectedMatches").toLong().also { require(it >= 201) }
        val expectedEvents = field("expectedEvents").toLong().also { require(it >= 501) }
        val match = field("matchId")
        val key = field("apiKey")

        fun login(
            email: String,
            password: String,
        ): CloudCredentials {
            java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NEVER).build().use { client ->
                val response =
                    client.send(
                        HttpRequest
                            .newBuilder(URI("${url.toString().trimEnd('/')}/auth/v1/token?grant_type=password"))
                            .header("apikey", key)
                            .header("Content-Type", "application/json")
                            .POST(
                                HttpRequest.BodyPublishers.ofString(
                                    buildJsonObject {
                                        put("email", email)
                                        put("password", password)
                                    }.toString(),
                                ),
                            ).build(),
                        HttpResponse.BodyHandlers.ofString(),
                    )
                check(response.statusCode() == 200) { "Echte lokale Auth-Anmeldung fehlgeschlagen" }
                val body = CloudJson.parseToJsonElement(response.body()).jsonObject
                val token = body.getValue("access_token").jsonPrimitive.content
                val claims = CloudJson.parseToJsonElement(String(Base64.getUrlDecoder().decode(token.split('.')[1]))).jsonObject
                return CloudCredentials(
                    body
                        .getValue("user")
                        .jsonObject
                        .getValue("id")
                        .jsonPrimitive.content,
                    claims.getValue("session_id").jsonPrimitive.content,
                    token,
                )
            }
        }
        val credentials = login(field("email"), field("password"))
        val noConsent = login(field("noConsentEmail"), field("noConsentPassword"))
        val installation =
            CloudInstallation(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""),
            )
        val device = UUID.randomUUID().toString()
        val file = File.createTempFile("d1-real-stack", ".db")
        try {
            HttpClient { followRedirects = false }.use { client ->
                val http = CloudHttp(url.toString(), key, client)
                runBlocking {
                    val profile =
                        CloudJson
                            .parseToJsonElement(
                                http.rpc("my_auth_profile", JsonObject(emptyMap()), credentials.accessToken),
                            ).jsonObject
                    check(profile.getValue("profileId").jsonPrimitive.content == credentials.profileId)
                    check(profile.getValue("onboarded").jsonPrimitive.content == "true")
                    check(profile.getValue("deletionRequested").jsonPrimitive.content == "false")
                    check(profile.getValue("consentStatus").jsonPrimitive.content == "CURRENT")

                    fun backend(data: LocalData): SupabaseCloudBackend {
                        val gate = CloudSessionGate({ credentials }, data.cloudUploads)
                        gate.publish(gate.capture(false), CloudSession(credentials.profileId, true, consentStatus = ConsentStatus.CURRENT))
                        return SupabaseCloudBackend(gate, http, installation)
                    }

                    fun drain(
                        backend: SupabaseCloudBackend,
                        data: LocalData,
                    ) = CloudPullRunner(
                        backend,
                        data,
                        maxRpcsPerRun = 100,
                        maxPagesPerRun = 100,
                        maxEventJobsPerRun = 100,
                        deadlineMillis = 120_000,
                    )

                    suspend fun complete(
                        backend: SupabaseCloudBackend,
                        data: LocalData,
                    ) {
                        val runner = drain(backend, data)
                        repeat(20) {
                            val result = runner.pullNow()
                            if (result.outcome == CloudPullOutcome.COMPLETE) return
                            check(result.outcome == CloudPullOutcome.LIMIT_REACHED) { "Echter D1-Runner unvollständig: ${result.outcome}" }
                        }
                        error("Echter D1-Runner überschritt das begrenzte Testbudget")
                    }
                    JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { driver ->
                        AdvantlyDatabase.Schema.create(driver).value
                        val data = LocalData(driver).selectPullAccount(credentials.profileId)
                        val backend = backend(data)
                        backend.bindDevice(device)
                        assertEquals(CloudPullOutcome.LIMIT_REACHED, CloudPullRunner(backend, data, maxPagesPerRun = 1).pullNow().outcome)
                        assertTrue(data.cloudPulls.progress(credentials.profileId).cursor > 0)
                    }
                    JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { driver ->
                        val data = LocalData(driver).selectPullAccount(credentials.profileId)
                        val backend = backend(data)
                        backend.bindDevice(device)
                        complete(backend, data)
                        val count =
                            driver
                                .executeQuery(null, "SELECT count(*) FROM cloud_pull_snapshot WHERE kind='MATCH'", {
                                    check(it.next().value)
                                    app.cash.sqldelight.db.QueryResult
                                        .Value(requireNotNull(it.getLong(0)))
                                }, 0)
                                .value
                        assertTrue(count >= expectedMatches)
                        assertEquals(expectedEvents, data.events.count(match))
                        val work = requireNotNull(data.cloudPulls.eventWork(credentials.profileId, match))
                        val received = data.cloudPulls.receivedWatermarks(work).sumOf { it.eventCount }
                        assertEquals(expectedEvents, received)
                        // Nur einen Empfangsnachweis unterhalb des gespeicherten Cursors entfernen, keine Payload.
                        driver.execute(
                            null,
                            """DELETE FROM cloud_pull_receipt WHERE rowid=(SELECT rowid FROM cloud_pull_receipt
                                WHERE match_id=? ORDER BY server_seq LIMIT 1)""",
                            1,
                        ) {
                            bindString(0, match)
                        }
                        driver.execute(null, "UPDATE cloud_pull_event_work SET last_checked=0 WHERE match_id=?", 1) { bindString(0, match) }
                        assertEquals(CloudPullOutcome.LIMIT_REACHED, CloudPullRunner(backend, data, maxPagesPerRun = 2).pullNow().outcome)
                        assertTrue(requireNotNull(data.cloudPulls.eventWork(credentials.profileId, match)).repairing)
                        assertEquals(0, data.cloudPulls.eventWork(credentials.profileId, match)?.cursor)
                    }
                    JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { driver ->
                        val data = LocalData(driver).selectPullAccount(credentials.profileId)
                        val backend = backend(data)
                        backend.bindDevice(device)
                        complete(backend, data)
                        val work = requireNotNull(data.cloudPulls.eventWork(credentials.profileId, match))
                        assertEquals(expectedEvents, data.cloudPulls.receivedWatermarks(work).sumOf { it.eventCount })
                        val requests =
                            listOf(
                                "cloud_sync_pull_v1" to
                                    buildJsonObject {
                                        put("p_cursor", 0)
                                        put("p_limit", 200)
                                    },
                                "cloud_pull_match_events_v1" to
                                    buildJsonObject {
                                        put("p_match_id", match)
                                        put("p_after_server_seq", 0)
                                        put("p_limit", 500)
                                    },
                                "cloud_match_event_watermarks_v1" to buildJsonObject { put("p_match_id", match) },
                            )
                        for ((name, body) in requests) {
                            assertFailsWith<CloudException> { http.rpc(name, body, null, installation) }
                            assertEquals(
                                "consent_required",
                                assertFailsWith<CloudException> { http.rpc(name, body, noConsent.accessToken, installation) }.reason,
                            )
                            assertFailsWith<CloudException> { http.rpc(name, body, credentials.accessToken, null) }
                        }
                        http.rpc(
                            "cloud_revoke_installation_v1",
                            buildJsonObject { put("p_installation_id", installation.id) },
                            credentials.accessToken,
                        )
                        for ((name, body) in requests) {
                            assertFailsWith<CloudException> {
                                http.rpc(
                                    name,
                                    body,
                                    credentials.accessToken,
                                    installation,
                                )
                            }
                        }
                        assertEquals(CloudPullOutcome.BLOCKED, drain(backend, data).pullNow().outcome)
                    }
                }
            }
        } finally {
            file.delete()
        }
    }
}
