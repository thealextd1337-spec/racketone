package eu.advantly.cloud.sync

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.EventId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.PlayerId
import eu.advantly.core.scoring.MatchCreated
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.ScoreEvent
import eu.advantly.data.LocalData
import eu.advantly.data.createInMemoryJvmDriver
import eu.advantly.data.toStoredEvent
import kotlinx.serialization.json.JsonElement
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Vertrag App → Server (Staging-Befund 03.11.2026): Das echte Upload-Mapping erzeugt für ein Match mit namenlosem Self und
 * Platzhalter-Gegner genau `supabase/tests/fixtures/cloud_sync_push_placeholder_match.json`. pgTAP
 * (`450_leere_teilnehmernamen.test.sql`) schickt denselben Batch durch `cloud_sync_push_v1` und erwartet `created`.
 * Neu schreiben: `ADVANTLY_WRITE_FIXTURES=1 gradlew :shared:cloud-sync:jvmTest --tests '*PushContractFixtureTest*'`,
 * danach den JSON-Block in der pgTAP-Datei ersetzen.
 */
class PushContractFixtureTest {
    private val root = File(System.getProperty("user.dir"), "../..")
    private val fixture = File(root, "supabase/tests/fixtures/cloud_sync_push_placeholder_match.json")
    private val pgTap = File(root, "supabase/tests/database/450_leere_teilnehmernamen.test.sql")

    private fun batch(): JsonElement {
        val driver = createInMemoryJvmDriver()
        try {
            val data = LocalData(driver)
            val me = PlayerId("45000000-0000-4000-8000-000000000001")
            val device = "45200000-0000-4000-8000-000000000001"
            val opponent = PlayerId("placeholder:45100000-0000-7000-8000-000000000001")
            // Wie die App: Self ohne Namen, Gegner ein unbesetzter Platz (Uhr-Schnellmatch).
            data.players.ensureSelf("", 1_793_700_000_000) { me }
            data.accountIdentities.initialize(device, me.value, "PHONE")
            val session = data.cloudUploads.activate("alice")
            data.cloudUploads.bindOwnedDevices(session, setOf(device))
            val format = Presets.TENNIS_SINGLES_BO3.format()
            val lineup = Lineup(listOf(me), listOf(opponent), me)
            val match = MatchId("45300000-0000-4000-8000-000000000001")
            data.events.append(
                ScoreEvent(
                    EventId("45400000-0000-4000-8000-000000000001"),
                    match,
                    DeviceId(device),
                    1,
                    1,
                    1_793_700_000_000,
                    null,
                    MatchCreated(format, lineup, createdBy = me),
                ).toStoredEvent(),
            )
            data.matches.insertIfAbsent(match, format, lineup, MatchMeta(), 1_793_700_000_000)
            val batch = data.cloudUploads.capture(session, PushBatch.MAX_EVENTS_PER_PUSH).toPushBatch()
            return CloudJson.encodeToJsonElement(PushBatch.serializer(), batch)
        } finally {
            driver.close()
        }
    }

    @Test
    fun mappingProducesTheCheckedInFixture() {
        val actual = batch()
        val rewrite = System.getenv("ADVANTLY_WRITE_FIXTURES") == "1"
        if (rewrite) fixture.writeText(PRETTY.encodeToString(JsonElement.serializer(), actual) + "\n")
        assertEquals(CloudJson.parseToJsonElement(fixture.readText()), actual, "Upload-Mapping weicht von der Server-Fixture ab")
    }

    @Test
    fun pgTapSendsExactlyTheFixture() {
        val sql = pgTap.readText()
        val embedded = sql.substringAfter("\$fixture\$").substringBefore("\$fixture\$")
        assertEquals(CloudJson.parseToJsonElement(fixture.readText()), CloudJson.parseToJsonElement(embedded))
    }

    private companion object {
        val PRETTY = kotlinx.serialization.json.Json { prettyPrint = true }
    }
}
