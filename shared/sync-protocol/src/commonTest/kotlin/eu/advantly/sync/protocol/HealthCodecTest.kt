package eu.advantly.sync.protocol

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.HealthConsent
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.TelemetryVisibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Puls H5/H6: Gesundheitsnachrichten (Protokoll 6), Einwilligung und Stufe im Match-Kopf – alles additiv. */
class HealthCodecTest {
    private val series =
        HealthSeriesMessage(
            MatchId("m1"),
            DeviceId("watch"),
            revision = 3,
            startMs = 1_000,
            bpm = listOf(120, null, 130),
            steps = listOf(0, null, 12),
            clockOffsetMs = -800,
            quality = "OK",
        )

    private fun decode(json: String) = SyncCodec.decode(json)

    @Test
    fun seriesAndAckRoundTripWithoutValuesInToString() {
        for (message in listOf(
            series,
            series.copy(bpm = emptyList(), steps = emptyList(), deleted = true),
            HealthAck(MatchId("m1"), DeviceId("w"), 3),
        )) {
            assertEquals(message, assertIs<DecodeResult.Ok>(decode(SyncCodec.encodeToString(message))).message)
        }
        assertTrue("120" !in series.toString())
        // mit Beleg (Konto, Protokoll ≥ 4)
        val stamped =
            series.copy(
                v = 6,
                stamp =
                    LocalMatchStamp(
                        DeviceId("watch"),
                        eu.advantly.core.model
                            .EventId("g"),
                        "s",
                    ),
            )
        assertEquals(stamped, assertIs<DecodeResult.Ok>(decode(SyncCodec.encodeToString(stamped))).message)
    }

    @Test
    fun invalidSeriesAreRejectedAtTheBoundary() {
        assertFailsWith<IllegalArgumentException> { series.copy(steps = listOf(1)) }
        assertFailsWith<IllegalArgumentException> { series.copy(intervalMs = 0) }
        assertFailsWith<IllegalArgumentException> { series.copy(deleted = true) }
        val tooMany = HealthSeriesMessage.MAX_BUCKETS + 1
        assertFailsWith<IllegalArgumentException> { series.copy(bpm = List(tooMany) { 100 }, steps = List(tooMany) { null }) }
        val json = SyncCodec.encodeToString(series).replace(""""steps":[0,null,12]""", """"steps":[0]""")
        assertIs<DecodeResult.Malformed>(decode(json))
    }

    /** Die längste erlaubte Serie passt im ungünstigsten Fall mit Beleg in ein Wirepaket (64 KiB, Rest für die Identität). */
    @Test
    fun theLongestSeriesFitsIntoOneWirePacket() {
        val n = HealthSeriesMessage.MAX_BUCKETS
        val worst =
            series.copy(
                revision = Long.MAX_VALUE,
                startMs = Long.MIN_VALUE,
                bpm = List(n) { null },
                steps = List(n) { 999_999L },
                clockOffsetMs = Long.MIN_VALUE,
                quality = "INSUFFICIENT",
                v = 6,
                stamp =
                    LocalMatchStamp(
                        DeviceId("watch"),
                        eu.advantly.core.model
                            .EventId("g"),
                        "s",
                    ),
            )
        val size = SyncCodec.encode(worst).size
        assertTrue(size <= 60 * 1024, "$size Byte")
    }

    /** Ein Gerät vor Protokoll 6 kennt den Typ nicht: Es verwirft die Nachricht still (kein Absturz, kein Teilzustand). */
    @Test
    fun olderReceiverDropsUnknownHealthMessages() {
        val json = SyncCodec.encodeToString(series).replace("\"HealthSeries\"", "\"FutureHealth\"")
        assertIs<DecodeResult.Malformed>(decode(json))
    }

    @Test
    fun helloAnnouncementAndConsentAreOptional() {
        val old = assertIs<DecodeResult.Ok>(decode("""{"type":"Hello","deviceId":"w","kind":"WATCH","v":5,"minV":5}""")).message
        assertNull((old as Hello).healthProtocol)
        val hello = Hello(DeviceId("w"), PeerKind.WATCH, healthProtocol = 1)
        assertEquals(hello, assertIs<DecodeResult.Ok>(decode(SyncCodec.encodeToString(hello))).message)
        assertTrue("healthConsent" !in SyncCodec.encodeToString(MatchMetaMessage()))
        assertNull(HealthConsentSetting.of(HealthConsent()))
        val consent = HealthConsent(true, 2, "phone")
        val meta = MatchMetaMessage(healthConsent = HealthConsentSetting.of(consent))
        val decoded = assertIs<DecodeResult.Ok>(decode(SyncCodec.encodeToString(meta))).message as MatchMetaMessage
        assertEquals(consent, decoded.healthConsent?.toConsent())
        assertNull(HealthConsentSetting(true).toConsent(), "ohne Zähler")
        assertNull(HealthConsentSetting(true, 1, " ").toConsent())
    }

    /** `MatchMeta.pulseVisibility` steht im Kopf und in der Genesis nur bei ≠ „Gar nicht“, als Text. */
    @Test
    fun pulseVisibilityInTheHeaderIsAdditive() {
        fun wire(meta: MatchMeta) = SyncCodec.json.encodeToString(MatchMeta.serializer(), meta)
        assertEquals("{}", wire(MatchMeta()))
        assertEquals("""{"pulseVisibility":"private"}""", wire(MatchMeta(pulseVisibility = TelemetryVisibility.PRIVATE)))
        for (visibility in TelemetryVisibility.entries) {
            val meta = MatchMeta(venue = "Halle", pulseVisibility = visibility)
            assertEquals(meta, SyncCodec.json.decodeFromString(MatchMeta.serializer(), wire(meta)))
        }
        val future = SyncCodec.json.decodeFromString(MatchMeta.serializer(), """{"venue":"x","pulseVisibility":"friends"}""")
        assertEquals(MatchMeta(venue = "x"), future, "unbekannte Stufe → „Gar nicht“, Kopf bleibt lesbar")
    }
}
