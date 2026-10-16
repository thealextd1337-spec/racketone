package eu.advantly.cloud.sync

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.EventId
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.PointWon
import eu.advantly.core.scoring.ScoreEvent
import eu.advantly.core.scoring.ScorerChanged
import eu.advantly.data.StoredEvent
import eu.advantly.data.toScoreEvent
import eu.advantly.data.toStoredEvent
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class EventRowsTest {
    private val match = MatchId("0199f0a0-0000-7000-8000-000000000001")
    private val watch = DeviceId("0199f0a0-0000-7000-8000-0000000000a2")

    @Test
    fun roundTripKeepsTheEngineEventIdentical() {
        val original =
            ScoreEvent(
                id = EventId("0199f0a0-0000-7000-8000-000000000101"),
                matchId = match,
                deviceId = watch,
                deviceSeq = 7,
                lamport = 12,
                wallClockMs = 1_760_608_800_000,
                parentId = EventId("0199f0a0-0000-7000-8000-000000000100"),
                payload = PointWon(Side.A),
            )
        val stored = original.toStoredEvent()
        val row = EventRows.toRow(stored)

        assertEquals("PointWon", row.type)
        assertEquals(JsonPrimitive("PointWon"), row.payload["type"])
        assertEquals(stored.parentId, row.parentId)

        val back = EventRows.toStored(row)
        assertFalse(back.confirmed, "Cloud-Empfang bestätigt keine Zustellung an ein gekoppeltes Gerät")
        assertEquals(original, back.toScoreEvent(), "Engine-Event strukturell gleich (02 §7.3)")
    }

    @Test
    fun scorerChangedTargetSurvivesTheRoundTrip() {
        val event =
            ScoreEvent(EventId("e2"), match, watch, 2, 2, 0, null, ScorerChanged(DeviceId("0199f0a0-0000-7000-8000-0000000000b1")))
        val row = EventRows.toRow(event.toStoredEvent())
        assertEquals(JsonPrimitive("0199f0a0-0000-7000-8000-0000000000b1"), row.payload["scorerDeviceId"])
        assertEquals(event, EventRows.toStored(row).toScoreEvent())
    }

    @Test
    fun legacyRowsWithoutTypeTakeItFromThePayload() {
        val legacy = StoredEvent("e3", match.value, watch.value, 3, 3, """{"type":"Paused"}""")
        assertEquals("Paused", EventRows.toRow(legacy).type)
    }

    @Test
    fun mismatchingTypeIsRejectedBeforeUpload() {
        val broken = StoredEvent("e4", match.value, watch.value, 4, 4, """{"type":"Undo","target":"e1"}""", type = "PointWon")
        assertFailsWith<IllegalArgumentException> { EventRows.toRow(broken) }
    }

    @Test
    fun cloudNamesAreLowercaseEngineNames() {
        assertEquals("table_tennis", CloudNames.of("TABLE_TENNIS"))
        assertEquals("AWAITING_DECISION", CloudNames.toEngine("awaiting_decision"))
    }
}
