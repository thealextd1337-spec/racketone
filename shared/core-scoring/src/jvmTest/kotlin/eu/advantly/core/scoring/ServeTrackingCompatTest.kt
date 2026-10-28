package eu.advantly.core.scoring

import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.ServeTracking
import eu.advantly.core.scoring.vectors.vectorJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * „Aufschläge“ (Entscheidung 2026-10-28): `MatchMeta.serveTracking` bleibt mit dem Ja/Nein `serveStats` von App 0.2.0
 * kompatibel – in Events, im Uhr↔Handy-Protokoll und in der Cloud (`matches.meta`), in beide Richtungen.
 */
class ServeTrackingCompatTest {
    /** `MatchMeta`, wie App 0.2.0 sie kennt (nur `serveStats`). */
    @Serializable
    private data class MetaV020(
        val venue: String? = null,
        val watchWorkout: Boolean = true,
        val serveStats: Boolean = true,
    )

    /** Events, Datenbank und Uhr↔Handy (`DataJson`, `SyncCodec`). */
    private val device =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
            explicitNulls = true
        }

    /** Cloud-Wire (`CloudJson`). */
    private val cloud =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }

    private fun Json.decodeMeta(text: String) = decodeFromString(MatchMeta.serializer(), text)

    @Test
    fun oldDataMapsToTheNewModes() {
        for (json in listOf(device, cloud, vectorJson)) {
            assertEquals(ServeTracking.FULL, json.decodeMeta("{}").serveTracking, "Feld fehlt → Ja")
            assertEquals(ServeTracking.FULL, json.decodeMeta("""{"serveStats":true}""").serveTracking)
            assertEquals(ServeTracking.OFF, json.decodeMeta("""{"serveStats":false}""").serveTracking)
            assertEquals(ServeTracking.DOUBLE_FAULTS_ONLY, json.decodeMeta("""{"serveTracking":"DOUBLE_FAULTS_ONLY"}""").serveTracking)
        }
    }

    @Test
    fun newDataStaysReadableForOldDevices() {
        val oldView = mapOf(ServeTracking.FULL to true, ServeTracking.DOUBLE_FAULTS_ONLY to true, ServeTracking.OFF to false)
        for (json in listOf(device, cloud)) {
            for ((tracking, serveStats) in oldView) {
                val meta = MatchMeta(venue = "Halle", serveTracking = tracking)
                val wire = json.encodeToString(MatchMeta.serializer(), meta)
                assertEquals(serveStats, json.decodeFromString(MetaV020.serializer(), wire).serveStats, "0.2.0 liest $wire")
                assertEquals(meta, json.decodeMeta(wire), "Rundreise $wire")
            }
        }
    }

    @Test
    fun fullAndOffKeepTheOldWireForm() {
        fun wire(tracking: ServeTracking) = device.encodeToString(MatchMeta.serializer(), MatchMeta(serveTracking = tracking))
        assertEquals("{}", wire(ServeTracking.FULL))
        assertEquals("""{"serveStats":false}""", wire(ServeTracking.OFF))
        assertEquals("""{"serveTracking":"DOUBLE_FAULTS_ONLY"}""", wire(ServeTracking.DOUBLE_FAULTS_ONLY))
        // im Genesis-Event: strenger Vektor-Codec (keine unbekannten Felder) liest die neue Form
        val created =
            MatchCreated(
                Presets.TENNIS_SINGLES_BO3.format(),
                Lineup(listOf(PlayerId("a")), listOf(PlayerId("b")), PlayerId("a")),
                MatchMeta(serveTracking = ServeTracking.DOUBLE_FAULTS_ONLY),
            )
        val text = vectorJson.encodeToString(EventPayload.serializer(), created)
        assertEquals(created, vectorJson.decodeFromString(EventPayload.serializer(), text))
    }
}
