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
import eu.advantly.core.scoring.Undo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SyncCodecTest {
    private fun roundTrip(message: SyncMessage) {
        assertEquals(DecodeResult.Ok(message), SyncCodec.decode(SyncCodec.encode(message)))
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

    /** Ältere Absender kennen `self`/`selfAliases`/`watchWorkout` nicht: Defaults greifen, Standard wird nicht gesendet. */
    @Test
    fun ownerIdentityAndWorkoutAreOptionalOnTheWire() {
        val old = """{"type":"MatchMeta","match":null,"players":[],"v":2}"""
        val decoded = (SyncCodec.decode(old.encodeToByteArray()) as DecodeResult.Ok).message as MatchMetaMessage
        assertEquals(null, decoded.self)
        assertEquals(emptyList(), decoded.selfAliases)
        val json = SyncCodec.encodeToString(MatchMetaMessage(MatchHeader(MATCH, FORMAT, LINEUP, MatchMeta(), createdAtMs = 1)))
        assertTrue("watchWorkout" !in json && "selfAliases" !in json, json)
    }

    @Test
    fun wireFormatHasTypeVersionAndPlainVectors() {
        val json = SyncCodec.encodeToString(Summary(mapOf("m1" to VersionVector.of(mapOf(DeviceId("watch") to 3L)))))
        assertEquals("""{"type":"Summary","matches":{"m1":{"watch":3}},"v":2}""", json)
        val events = SyncCodec.encodeToString(Events(MATCH, listOf(point("e1", "phone", 1, 1, null, Side.B))))
        assertTrue(""""payload":{"type":"PointWon","side":"B"}""" in events, events)
        val hello = SyncCodec.encodeToString(Hello(DeviceId("d"), PeerKind.PHONE))
        assertTrue(""""v":2""" in hello && """"minV":2""" in hello, hello)
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val decoded = SyncCodec.decode("""{"type":"Hello","v":2,"deviceId":"d","kind":"PHONE","future":{"x":1}}""")
        assertEquals(DecodeResult.Ok(Hello(DeviceId("d"), PeerKind.PHONE)), decoded)
    }

    @Test
    fun unsupportedVersionIsReportedButHelloIsAlwaysRead() {
        assertEquals(DecodeResult.Unsupported(3, "Summary"), SyncCodec.decode("""{"type":"Summary","v":3,"matches":{}}"""))
        val hello = SyncCodec.decode("""{"type":"Hello","v":3,"minV":3,"deviceId":"d","kind":"WATCH","newField":true}""")
        assertEquals(DecodeResult.Ok(Hello(DeviceId("d"), PeerKind.WATCH, minV = 3, v = 3)), hello)
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
            """{"type":"Unbekannt","v":2}""",
            """{"type":"Events","v":2,"matchId":"m1","events":[{"id":"x"}]}""",
            """{"type":"Events","v":"eins"}""",
        ).forEach { assertIs<DecodeResult.Malformed>(SyncCodec.decode(it), it) }
        assertIs<DecodeResult.Malformed>(SyncCodec.decode(byteArrayOf(0xAD.toByte(), 1, 2, 0, 0)))
        assertIs<DecodeResult.Ok>(SyncCodec.decode("""{"type":"Summary","v":2,"matches":{"m1":{}}}"""))
    }

    @Test
    fun summaryVectorLookup() {
        val summary = Summary(mapOf("m1" to VersionVector.of(mapOf(DeviceId("w") to 2L))))
        assertEquals(2, summary.vector(MATCH)[DeviceId("w")])
        assertEquals(VersionVector.EMPTY, summary.vector(MatchId("other")))
    }
}
