package eu.advantly.sync.protocol

import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.TelemetryPreference
import eu.advantly.core.model.TelemetryVisibility
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P2-J: Telemetrie-Sichtbarkeit als additives, optionales Feld von `MatchMeta` in Protokoll 3. */
class TelemetrySettingCodecTest {
    private fun decodeMeta(json: String): MatchMetaMessage = assertIs<DecodeResult.Ok>(SyncCodec.decode(json)).message as MatchMetaMessage

    @Test
    fun settingRoundTripsAndDefaultIsNotSent() {
        val message = MatchMetaMessage(telemetry = TelemetrySetting("off", 1_760_000_000_000))
        val json = SyncCodec.encodeToString(message)
        assertTrue(""""telemetry":{"visibility":"off","changedAtMs":1760000000000}""" in json, json)
        assertEquals(message, decodeMeta(json))
        assertTrue("telemetry" !in SyncCodec.encodeToString(MatchMetaMessage()))
        assertNull(TelemetrySetting.of(TelemetryPreference()))
    }

    @Test
    fun olderSenderWithoutFieldKeepsReceiverValue() {
        val old = decodeMeta("""{"type":"MatchMeta","match":null,"players":[],"v":3}""")
        assertNull(old.telemetry)
    }

    @Test
    fun unknownOrInvalidValuesOnlyDropTheSettingNotTheMessage() {
        val future =
            decodeMeta(
                """{"type":"MatchMeta","players":[{"id":"p","displayName":"Sam"}],"telemetry":{"visibility":"friends","changedAtMs":5},"v":3}""",
            )
        assertEquals(listOf(Player(PlayerId("p"), "Sam")), future.players)
        assertNull(future.telemetry?.toPreference())
        assertNull(TelemetrySetting("off", 0).toPreference())
        assertNull(TelemetrySetting("off", -3).toPreference())
        assertEquals(TelemetryPreference(TelemetryVisibility.SHARE, 7), TelemetrySetting("share", 7).toPreference())
        assertEquals(TelemetrySetting("private", 9), TelemetrySetting.of(TelemetryPreference(TelemetryVisibility.PRIVATE, 9)))
    }

    /** Ein Gerät mit dem bisherigen Protokoll 3 kennt das Feld nicht: dieselbe Codec-Konfiguration ignoriert es. */
    @Test
    fun olderReceiverIgnoresTheField() {
        val json =
            SyncCodec.encodeToString(
                MatchMetaMessage(players = listOf(Player(PlayerId("p"), "Sam")), telemetry = TelemetrySetting("off", 3)),
            )
        val old = SyncCodec.json.decodeFromString(PreviousMatchMeta.serializer(), json)
        assertEquals(PreviousMatchMeta(players = listOf(Player(PlayerId("p"), "Sam")), v = 3), old)
    }

    /** Ausschnitt der `MatchMeta`-Form vor P2-J (ohne `telemetry`). */
    @Serializable
    private data class PreviousMatchMeta(
        val players: List<Player> = emptyList(),
        val v: Int,
    )
}
