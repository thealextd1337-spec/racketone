package eu.advantly.sync.protocol

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.Discipline
import eu.advantly.core.model.EventId
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.model.Surface
import eu.advantly.core.scoring.Correction
import eu.advantly.core.scoring.EndReason
import eu.advantly.core.scoring.MatchEnded
import eu.advantly.core.scoring.ScoreEvent
import eu.advantly.core.scoring.ServerChosen
import eu.advantly.core.scoring.Undo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SyncCodecTest {
    private fun roundTrip(message: SyncMessage) {
        assertEquals(DecodeResult.Ok(message), SyncCodec.decode(SyncCodec.encode(message)))
    }

    /** Eingefrorene v3-Payloads: ServerChosen hatte schon vor F30 nur Satz, Team und Spieler. */
    @Test
    fun legacyProtocolThreeChoicePayloadIsUnchangedInBothDirections() {
        for (device in listOf("phone", "watch")) {
            val legacy =
                """{"type":"Events","matchId":"m1","events":[{"id":"e1","matchId":"m1","deviceId":"$device",""" +
                    """"deviceSeq":2,"lamport":2,"wallClockMs":20000,"parentId":"g",""" +
                    """"payload":{"type":"ServerChosen","set":0,"side":"B","player":"B2"}}],"v":3}"""
            val message = Events(MATCH, listOf(ev("e1", device, 2, 2, "g", ServerChosen(0, Side.B, PlayerId("B2")))))
            assertEquals(DecodeResult.Ok(message), SyncCodec.decode(legacy))
            assertEquals(legacy, SyncCodec.encodeToString(message))
            assertEquals(3, ProtocolVersion.CURRENT)
        }
    }

    @Test
    fun archivedIdsAreAnOptionalProtocolThreeExtension() {
        val message = MatchMetaMessage(archivedPlayerIds = listOf(PlayerId("removed")))
        roundTrip(message)
        assertEquals(3, message.v)
        val old = """{"type":"MatchMeta","v":3}"""
        val decoded = (SyncCodec.decode(old.encodeToByteArray()) as DecodeResult.Ok).message as MatchMetaMessage
        assertTrue(decoded.archivedPlayerIds.isEmpty())
        assertTrue(SyncCodec.encodeToString(message).contains("archivedPlayerIds"))
    }

    @Test
    fun allMessageTypesRoundTrip() {
        roundTrip(Hello(DeviceId("watch-1"), PeerKind.WATCH, listOf(MATCH)))
        roundTrip(Deleted(listOf(DeletedMatch(MATCH, 1_760_000_000_000))))
        roundTrip(Summary(mapOf("m1" to VersionVector.of(mapOf(DeviceId("watch") to 3L, DeviceId("phone") to 1L))), reply = true))
        roundTrip(
            Events(
                MATCH,
                listOf(
                    genesis(),
                    point("e1", "phone", 2, 2, "g", Side.A),
                    ev("e2", "watch", 1, 3, "e1", Undo(EventId("e1"))),
                    ev("e3", "watch", 2, 4, "e2", Correction.server(PlayerId("B1"))),
                ),
                more = true,
            ),
        )
        roundTrip(
            MatchMetaMessage(
                MatchHeader(MATCH, FORMAT, LINEUP, MatchMeta(surface = Surface.CLAY), createdAtMs = 1_760_000_000_000, MatchId("m0")),
                players = listOf(Player(PlayerId("A1"), "Alex"), Player(PlayerId("B1"), "Bea ✓")),
                favorites = listOf(FavoriteLineup("fav", Sport.TENNIS, Discipline.SINGLES, listOf(PlayerId("A1")), listOf(PlayerId("B1")))),
            ),
        )
        // W1-I: Identität des Besitzers und Workout-Wunsch
        roundTrip(
            MatchMetaMessage(
                MatchHeader(MATCH, FORMAT, LINEUP, MatchMeta(watchWorkout = false), createdAtMs = 1),
                self = Player(PlayerId("A1"), "Alex"),
                selfAliases = listOf(PlayerId("watch-self")),
            ),
        )
    }

    /** Uhr-Zuweisung des Handys (F22) und Endgrund STOPPED (02 §9.27) überstehen den Draht. */
    @Test
    fun watchAssignmentAndStoppedRoundTrip() {
        roundTrip(MatchMetaMessage(watchMatch = WatchAssignment(MATCH, assignedAtMs = 42)))
        roundTrip(MatchMetaMessage(watchMatch = WatchAssignment(null, assignedAtMs = 43)))
        val stop =
            ScoreEvent(EventId("s"), MATCH, DeviceId("phone"), 2, 2, 2_000, null, MatchEnded(EndReason.STOPPED))
        roundTrip(Events(MATCH, listOf(stop)))
        assertTrue(""""reason":"STOPPED"""" in SyncCodec.encodeToString(Events(MATCH, listOf(stop))))
        val json = SyncCodec.encodeToString(MatchMetaMessage())
        assertTrue("watchMatch" !in json, json)
    }

    /** Ältere Absender kennen `self`/`selfAliases`/`watchWorkout` nicht: Defaults greifen, Standard wird nicht gesendet. */
    @Test
    fun ownerIdentityAndWorkoutAreOptionalOnTheWire() {
        val old = """{"type":"MatchMeta","match":null,"players":[],"v":3}"""
        val decoded = (SyncCodec.decode(old.encodeToByteArray()) as DecodeResult.Ok).message as MatchMetaMessage
        assertEquals(null, decoded.self)
        assertEquals(emptyList(), decoded.selfAliases)
        val json = SyncCodec.encodeToString(MatchMetaMessage(MatchHeader(MATCH, FORMAT, LINEUP, MatchMeta(), createdAtMs = 1)))
        assertTrue("watchWorkout" !in json && "selfAliases" !in json, json)
    }

    @Test
    fun wireFormatHasTypeVersionAndPlainVectors() {
        val json = SyncCodec.encodeToString(Summary(mapOf("m1" to VersionVector.of(mapOf(DeviceId("watch") to 3L)))))
        assertEquals("""{"type":"Summary","matches":{"m1":{"watch":3}},"v":3}""", json)
        val events = SyncCodec.encodeToString(Events(MATCH, listOf(point("e1", "phone", 1, 1, null, Side.B))))
        assertTrue(""""payload":{"type":"PointWon","side":"B"}""" in events, events)
        val hello = SyncCodec.encodeToString(Hello(DeviceId("d"), PeerKind.PHONE))
        assertTrue(""""v":3""" in hello && """"minV":3""" in hello, hello)
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val decoded = SyncCodec.decode("""{"type":"Hello","v":3,"deviceId":"d","kind":"PHONE","future":{"x":1}}""")
        assertEquals(DecodeResult.Ok(Hello(DeviceId("d"), PeerKind.PHONE)), decoded)
    }

    @Test
    fun unsupportedVersionIsReportedButHelloIsAlwaysRead() {
        assertEquals(DecodeResult.Unsupported(4, "Summary"), SyncCodec.decode("""{"type":"Summary","v":4,"matches":{}}"""))
        val hello = SyncCodec.decode("""{"type":"Hello","v":4,"minV":4,"deviceId":"d","kind":"WATCH","newField":true}""")
        assertEquals(DecodeResult.Ok(Hello(DeviceId("d"), PeerKind.WATCH, minV = 4, v = 4)), hello)
        // W2-Gerät (Protokoll 2): nur noch sein Hello wird gelesen
        assertEquals(DecodeResult.Unsupported(2, "Events"), SyncCodec.decode("""{"type":"Events","v":2,"matchId":"m1","events":[]}"""))
    }

    /** E1: ein anderer lesbarer Bereich (Session-Konfiguration) macht Protokoll 4 lesbar, ohne die Konstanten zu ändern. */
    @Test
    fun acceptedRangeIsAParameterAndConstantsStayTheDefault() {
        val summary = """{"type":"Summary","v":4,"matches":{}}"""
        assertEquals(DecodeResult.Ok(Summary(emptyMap(), v = 4)), SyncCodec.decode(summary, 3..4))
        assertEquals(DecodeResult.Unsupported(4, "Summary"), SyncCodec.decode(summary))
        assertEquals(DecodeResult.Unsupported(3, "Summary"), SyncCodec.decode("""{"type":"Summary","v":3,"matches":{}}""", 4..4))
    }

    /** Gerät mit Protokoll 1 (W1): sein Hello wird gelesen, alle anderen Nachrichten nicht mehr (kein Halb-Sync). */
    @Test
    fun version1PeerIsReadOnlyForTheHello() {
        val oldHello = SyncCodec.decode("""{"type":"Hello","v":1,"minV":1,"deviceId":"old","kind":"WATCH","activeMatches":[]}""")
        val hello = assertIs<DecodeResult.Ok>(oldHello).message as Hello
        assertEquals(CompatibilityStatus.PEER_TOO_OLD, ProtocolVersion.negotiate(hello.v, hello.minV).status)
        assertEquals(DecodeResult.Unsupported(1, "Summary"), SyncCodec.decode("""{"type":"Summary","v":1,"matches":{}}"""))
        assertEquals(
            DecodeResult.Unsupported(1, "Events"),
            SyncCodec.decode("""{"type":"Events","v":1,"matchId":"m1","events":[]}"""),
        )
    }

    @Test
    fun garbageIsMalformedAndNeverThrows() {
        listOf(
            "",
            "nicht json",
            "[]",
            """{"type":"Summary"}""",
            """{"type":"Unbekannt","v":3}""",
            """{"type":"Events","v":3,"matchId":"m1","events":[{"id":"x"}]}""",
            """{"type":"Events","v":"eins"}""",
        ).forEach { assertIs<DecodeResult.Malformed>(SyncCodec.decode(it), it) }
        assertIs<DecodeResult.Malformed>(SyncCodec.decode(byteArrayOf(0xAD.toByte(), 1, 2, 0, 0)))
        assertIs<DecodeResult.Ok>(SyncCodec.decode("""{"type":"Summary","v":3,"matches":{"m1":{}}}"""))
    }

    @Test
    fun summaryVectorLookup() {
        val summary = Summary(mapOf("m1" to VersionVector.of(mapOf(DeviceId("w") to 2L))))
        assertEquals(2, summary.vector(MATCH)[DeviceId("w")])
        assertEquals(VersionVector.EMPTY, summary.vector(MatchId("other")))
    }
}
