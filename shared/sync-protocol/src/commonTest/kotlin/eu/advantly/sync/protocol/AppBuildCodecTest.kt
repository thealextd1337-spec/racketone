package eu.advantly.sync.protocol

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.MatchId
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppBuildCodecTest {
    @Test
    fun oldHelloWithoutBuildStillDecodesAndDefaultFieldIsOmitted() {
        val old = """{"type":"Hello","deviceId":"watch","kind":"WATCH","minV":3,"v":3}"""
        val hello = assertIs<Hello>(assertIs<DecodeResult.Ok>(SyncCodec.decode(old)).message)
        assertNull(hello.appBuild)
        assertTrue("appBuild" !in SyncCodec.encodeToString(hello))
        assertTrue("\"minV\":3" in SyncCodec.encodeToString(hello))
        assertTrue("\"v\":3" in SyncCodec.encodeToString(hello))
    }

    @Test
    fun newHelloRoundTripsAndAnOldDecoderIgnoresBuild() {
        val hello =
            Hello(
                DeviceId("phone"),
                PeerKind.PHONE,
                listOf(MatchId("match")),
                appBuild = AppBuild("0.1.0", "223dff1-dirty", "2026-10-16T14:54:00Z"),
            )
        val json = SyncCodec.encodeToString(hello)
        assertEquals(hello, assertIs<DecodeResult.Ok>(SyncCodec.decode(json)).message)
        val old = SyncCodec.json.decodeFromString(PreviousHello.serializer(), json)
        assertEquals(PreviousHello(DeviceId("phone"), PeerKind.PHONE, listOf(MatchId("match")), 3, 3), old)
    }

    @Serializable
    private data class PreviousHello(
        val deviceId: DeviceId,
        val kind: PeerKind,
        val activeMatches: List<MatchId> = emptyList(),
        val minV: Int,
        val v: Int,
    )
}
