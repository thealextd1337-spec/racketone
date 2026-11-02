package eu.advantly.cloud.sync

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.EventId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.MatchCreated
import eu.advantly.core.scoring.PointWon
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.ScoreEvent
import eu.advantly.core.scoring.ScoringEngine
import eu.advantly.data.ScoreSummary
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * M2 (Review): durchgehender Vertrag Live-Stand. Die echte [liveStateOf]-Ausgabe eines Engine-Zustands steht wörtlich als
 * `liveState` im Push von `generate.psql`; der Server bereinigt sie (`sanitize_projection` über `visitor_view`) und
 * `cloud_my_live_matches.json` ist seine echte Antwort, die der Client hier dekodiert. Ändert sich eine Seite, bricht der Test.
 */
class LiveStateContractTest {
    private val dir = File(System.getProperty("user.dir"), "../../supabase/tests/fixtures")

    /** Stand des Fixture-Matches: Alice (A) hat den ersten Punkt gewonnen, das Handy zählt und ist erreichbar. */
    private fun uploadedLiveState(): String {
        val me = PlayerId("alice")
        val format = Presets.TENNIS_SINGLES_BO3.format()
        val lineup = Lineup(listOf(me), listOf(PlayerId("max")), me)
        val match = MatchId("m")
        val phone = DeviceId("phone")
        val log =
            listOf(
                ScoreEvent(
                    EventId("e1"),
                    match,
                    phone,
                    1,
                    1,
                    1_000,
                    null,
                    MatchCreated(format, lineup, scorerDeviceId = phone, createdBy = me),
                ),
                ScoreEvent(EventId("e2"), match, phone, 2, 2, 2_000, null, PointWon(Side.A)),
            )
        val state = ScoringEngine.replay(log).state
        val summary = ScoreSummary.of(state).copy(lastEventAtMs = 2_000, eventCount = 2, scorerConnected = true)
        return assertNotNull(liveStateOf("in_progress", CloudJson.encodeToString(ScoreSummary.serializer(), summary))).toString()
    }

    @Test
    fun uploadedLiveStateIsTheFixtureInputAndSurvivesTheServer() {
        val uploaded = CloudJson.parseToJsonElement(uploadedLiveState())
        val psql = File(dir, "generate.psql").readText()
        val literal = assertNotNull(Regex("'liveState', '([^']*)'::jsonb").find(psql), "liveState im Fixture-Push").groupValues[1]
        assertEquals(uploaded, CloudJson.parseToJsonElement(literal), "generate.psql muss die echte Mapping-Ausgabe senden")

        val live = CloudJson.decodeFromString(ListSerializer(LiveMatch.serializer()), File(dir, "cloud_my_live_matches.json").readText())
        val entry = live.single()
        assertEquals(true, entry.scorerConnected, "Erreichbarkeit des Zählers kommt beim Mitspieler an")
        assertEquals(JsonPrimitive("A"), entry.projection?.get("serving"), "Aufschlag kommt beim Mitspieler an")
        assertEquals(JsonPrimitive(true), entry.projection?.get("scorerConnected"))
        assertEquals(JsonPrimitive("in_progress"), entry.projection?.get("status"))
    }
}
