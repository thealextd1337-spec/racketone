package eu.advantly.cloud.sync

import eu.advantly.core.scoring.CurrentSet
import eu.advantly.core.scoring.GameKind
import eu.advantly.core.scoring.SetKind
import eu.advantly.core.scoring.SidePair
import eu.advantly.data.ScoreSummary
import eu.advantly.data.SetScore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveStateMappingTest {
    private fun scoreJson(
        current: CurrentSet?,
        lastEventAtMs: Long? = 1_000L,
    ) = CloudJson.encodeToString(
        ScoreSummary.serializer(),
        ScoreSummary(sets = listOf(SetScore(6, 4, 7, 5)), current = SetScore(2, 1), currentState = current, lastEventAtMs = lastEventAtMs),
    )

    private val standard =
        CurrentSet(1, SetKind.REGULAR, GameKind.STANDARD, SidePair(2, 1), SidePair(2, 1), SidePair("30", "15"), null, null, null)

    @Test fun runningMatchUploadsOnlyWhitelistedScoreFields() {
        val live = liveStateOf("in_progress", scoreJson(standard))!!
        assertEquals(
            CloudJson.parseToJsonElement(
                """{"sets":[[6,4]],"games":[2,1],"points":["30","15"],"tiebreak":false,"status":"in_progress","updatedAtMs":1000}""",
            ),
            live,
        )
        // Server-Whitelist (private.sanitize_projection): alles andere würde verworfen bzw. darf nie gesendet werden.
        val whitelist =
            setOf("sets", "games", "points", "serving", "flags", "status", "winner", "tiebreak", "scorerConnected", "updatedAtMs")
        assertTrue(whitelist.containsAll(live.keys))
    }

    @Test fun telemetryOrForeignKeysInTheProjectionNeverReachLiveState() {
        val tainted =
            JsonObject(
                CloudJson.parseToJsonElement(scoreJson(standard)).jsonObject +
                    mapOf("heartRate" to JsonPrimitive(150), "deviceId" to JsonPrimitive("d1"), "calories" to JsonPrimitive(12)),
            )
        val live = liveStateOf("paused", tainted.toString())!!
        assertTrue(live.toString().let { "heartRate" !in it && "deviceId" !in it && "calories" !in it })
        assertEquals(JsonPrimitive("paused"), live["status"])
    }

    @Test fun tiebreakPointsAndUnsafeDisplayValues() {
        val tiebreak = standard.copy(gameKind = GameKind.TIEBREAK, games = SidePair(6, 6), display = SidePair("5", "4"))
        assertEquals(JsonPrimitive(true), liveStateOf("in_progress", scoreJson(tiebreak))!!["tiebreak"])
        // Anzeige außerhalb des Server-Schemas (z. B. Sonderzeichen) wird gar nicht erst gesendet.
        assertNull(liveStateOf("in_progress", scoreJson(standard.copy(display = SidePair("–", "AD"))))!!["points"])
    }

    @Test fun finishedOrUnreadableProjectionHasNoLiveState() {
        assertNull(liveStateOf("finished", scoreJson(null)))
        assertNull(liveStateOf("in_progress", "not json"))
    }
}
