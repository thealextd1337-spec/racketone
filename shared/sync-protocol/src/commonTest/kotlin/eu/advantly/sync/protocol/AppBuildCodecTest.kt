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
        assertNull(hello.session)
        assertTrue("session" !in SyncCodec.encodeToString(hello))
        assertTrue("appBuild" !in SyncCodec.encodeToString(hello))
        assertTrue("\"minV\":3" in SyncCodec.encodeToString(hello))
        assertTrue("\"v\":3" in SyncCodec.encodeToString(hello))
    }

    @Test
    fun newHelloRoundTripsAndPreviousDecoderIgnoresSession() {
        val hello =
            Hello(
                DeviceId("phone"),
                PeerKind.PHONE,
                listOf(MatchId("match")),
                appBuild = AppBuild("0.1.0", "223dff1-dirty", "2026-10-16T14:54:00Z"),
                session = "phone-process-1",
            )
        val json = SyncCodec.encodeToString(hello)
        assertEquals(ProtocolVersion.CURRENT, hello.v)
        assertTrue("\"session\":\"phone-process-1\"" in json)
        assertEquals(hello, assertIs<DecodeResult.Ok>(SyncCodec.decode(json)).message)
        val old = SyncCodec.json.decodeFromString(PreviousHello.serializer(), json)
        val previous =
            PreviousHello(
                DeviceId("phone"),
                PeerKind.PHONE,
                listOf(MatchId("match")),
                ProtocolVersion.MIN_SUPPORTED,
                ProtocolVersion.CURRENT,
                hello.appBuild,
            )
        assertEquals(previous, old)
    }

    @Test
    fun previousHelloWithBuildButWithoutSessionDecodesInNewCodec() {
        val previous = PreviousHello(DeviceId("watch"), PeerKind.WATCH, minV = 3, v = 3, appBuild = AppBuild("0.1.0", "223dff1"))
        val json =
            """{"type":"Hello","deviceId":"watch","kind":"WATCH","minV":3,"v":3,"appBuild":{"version":"0.1.0","commit":"223dff1"}}"""
        val hello = assertIs<Hello>(assertIs<DecodeResult.Ok>(SyncCodec.decode(json)).message)
        assertNull(hello.session)
        assertEquals(previous.appBuild, hello.appBuild)
        assertEquals(previous.deviceId, hello.deviceId)
    }

    @Serializable
    private data class PreviousHello(
        val deviceId: DeviceId,
        val kind: PeerKind,
        val activeMatches: List<MatchId> = emptyList(),
        val minV: Int,
        val v: Int,
        val appBuild: AppBuild? = null,
    )
}
