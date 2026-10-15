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
    }

    @Test
    fun wireFormatHasTypeVersionAndPlainVectors() {
        val json = SyncCodec.encodeToString(Summary(mapOf("m1" to VersionVector.of(mapOf(DeviceId("watch") to 3L)))))
        assertEquals("""{"type":"Summary","matches":{"m1":{"watch":3}},"v":1}""", json)
        val events = SyncCodec.encodeToString(Events(MATCH, listOf(point("e1", "phone", 1, 1, null, Side.B))))
        assertTrue(""""payload":{"type":"PointWon","side":"B"}""" in events, events)
        assertTrue(""""v":1""" in SyncCodec.encodeToString(Hello(DeviceId("d"), PeerKind.PHONE)))
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val decoded = SyncCodec.decode("""{"type":"Hello","v":1,"deviceId":"d","kind":"PHONE","future":{"x":1}}""")
        assertEquals(DecodeResult.Ok(Hello(DeviceId("d"), PeerKind.PHONE)), decoded)
    }

    @Test
    fun unsupportedVersionIsReportedButHelloIsAlwaysRead() {
        assertEquals(DecodeResult.Unsupported(2, "Summary"), SyncCodec.decode("""{"type":"Summary","v":2,"matches":{}}"""))
        val hello = SyncCodec.decode("""{"type":"Hello","v":2,"minV":2,"deviceId":"d","kind":"WATCH","newField":true}""")
        assertEquals(DecodeResult.Ok(Hello(DeviceId("d"), PeerKind.WATCH, minV = 2, v = 2)), hello)
    }

    @Test
    fun garbageIsMalformedAndNeverThrows() {
        listOf(
            "",
            "nicht json",
            "[]",
            """{"type":"Summary"}""",
            """{"type":"Unbekannt","v":1}""",
            """{"type":"Events","v":1,"matchId":"m1","events":[{"id":"x"}]}""",
            """{"type":"Events","v":"eins"}""",
        ).forEach { assertIs<DecodeResult.Malformed>(SyncCodec.decode(it), it) }
        assertIs<DecodeResult.Malformed>(SyncCodec.decode(byteArrayOf(0xAD.toByte(), 1, 2, 0, 0)))
        assertIs<DecodeResult.Ok>(SyncCodec.decode("""{"type":"Summary","v":1,"matches":{"m1":{}}}"""))
    }

    @Test
    fun summaryVectorLookup() {
        val summary = Summary(mapOf("m1" to VersionVector.of(mapOf(DeviceId("w") to 2L))))
        assertEquals(2, summary.vector(MATCH)[DeviceId("w")])
        assertEquals(VersionVector.EMPTY, summary.vector(MatchId("other")))
    }
}
