package eu.advantly.core.scoring.vectors

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.EventId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchFormat
import eu.advantly.core.model.MatchId
import eu.advantly.core.scoring.EventPayload
import eu.advantly.core.scoring.MatchState
import eu.advantly.core.scoring.ScoreEvent
import eu.advantly.core.scoring.ScoringEngine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** JSON-Einstellungen für Testvektoren (Schema 1): Diskriminator `type`, explizite `null`, keine unbekannten Felder. */
val vectorJson: Json =
    Json {
        ignoreUnknownKeys = false
        explicitNulls = true
        encodeDefaults = false
    }

/** Ein eingelesener Testvektor (docs/testvectors/README.md §1). */
data class TestVector(
    val file: File,
    val id: String,
    val format: MatchFormat,
    val lineup: Lineup,
    val events: List<ScoreEvent>,
    val checkpoints: List<Checkpoint>,
)

/** Checkpoint: Teilzustand nach den ersten [afterEvent] Events. */
data class Checkpoint(
    val afterEvent: Int,
    val label: String,
    val state: JsonObject,
)

/** Abweichung eines Checkpoints. */
data class CheckpointFailure(
    val vectorId: String,
    val afterEvent: Int,
    val label: String,
    val diffs: List<String>,
) {
    override fun toString(): String = "$vectorId @$afterEvent „$label“:\n    " + diffs.joinToString("\n    ")
}

object RepoPaths {
    /**
     * Repo-Root robust auflösen: System-Property `advantly.repoRoot` (setzt Gradle), sonst vom Arbeitsverzeichnis
     * aufwärts bis zu einem Ordner mit `docs/testvectors`.
     */
    fun repoRoot(): File {
        System.getProperty("advantly.repoRoot")?.let { return File(it) }
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "docs/testvectors").isDirectory) return dir
            dir = dir.parentFile
        }
        error("Repo-Root mit docs/testvectors nicht gefunden (user.dir=${System.getProperty("user.dir")})")
    }

    fun specVectorsDir(): File = File(repoRoot(), "docs/testvectors")

    fun goldenVectorsDir(): File = File(repoRoot(), "testvectors")
}

object VectorIo {
    private val matchId = MatchId("tv-match")
    private val device = DeviceId("tv")

    /** Synthetische Hülle laut README §1.1: `lamport = Index + 1`, `deviceId = "tv"`, `wallClockMs = Index × 10 000`. */
    fun envelope(
        index: Int,
        id: String,
        previousId: String?,
        payload: EventPayload,
    ): ScoreEvent =
        ScoreEvent(
            id = EventId(id),
            matchId = matchId,
            deviceId = device,
            deviceSeq = index + 1L,
            lamport = index + 1L,
            wallClockMs = index * 10_000L,
            parentId = previousId?.let { EventId(it) },
            payload = payload,
        )

    fun read(file: File): TestVector {
        val root = vectorJson.parseToJsonElement(file.readText()).jsonObject
        require(root["schema"]?.jsonPrimitive?.int == 1) { "${file.name}: schema muss 1 sein" }
        val format = vectorJson.decodeFromJsonElement(MatchFormat.serializer(), root.getValue("config"))
        val lineup = vectorJson.decodeFromJsonElement(Lineup.serializer(), root.getValue("lineup"))
        val rawEvents = root.getValue("events").jsonArray.map { it.jsonObject }
        val events =
            rawEvents.mapIndexed { index, obj ->
                val id = obj.getValue("id").jsonPrimitive.content
                val payload = vectorJson.decodeFromJsonElement(EventPayload.serializer(), JsonObject(obj - "id"))
                envelope(
                    index,
                    id,
                    rawEvents
                        .getOrNull(index - 1)
                        ?.getValue("id")
                        ?.jsonPrimitive
                        ?.content,
                    payload,
                )
            }
        val checkpoints =
            root.getValue("expect").jsonArray.map {
                val cp = it.jsonObject
                Checkpoint(
                    afterEvent = cp.getValue("afterEvent").jsonPrimitive.int,
                    label = cp["label"]?.jsonPrimitive?.content ?: "",
                    state = cp.getValue("state").jsonObject,
                )
            }
        return TestVector(file, root.getValue("id").jsonPrimitive.content, format, lineup, events, checkpoints)
    }

    /** Kanonische JSON-Form des Zustands (02 §8). */
    fun canonical(state: MatchState): JsonObject = vectorJson.encodeToJsonElement(MatchState.serializer(), state).jsonObject

    /** Prüft alle Checkpoints eines Vektors; liefert die Abweichungen. */
    fun verify(vector: TestVector): List<CheckpointFailure> =
        vector.checkpoints.mapNotNull { cp ->
            val diffs =
                runCatching {
                    require(cp.afterEvent in 0..vector.events.size) { "afterEvent ${cp.afterEvent} außerhalb des Logs" }
                    val state = ScoringEngine.fold(vector.format, vector.lineup, vector.events.subList(0, cp.afterEvent))
                    PartialMatch.diff(cp.state, canonical(state))
                }.getOrElse { listOf("Ausnahme: $it") }
            if (diffs.isEmpty()) null else CheckpointFailure(vector.id, cp.afterEvent, cp.label, diffs)
        }
}

/** Vergleichsregeln aus docs/testvectors/README.md §1.2. */
object PartialMatch {
    fun diff(
        expected: JsonElement,
        actual: JsonElement?,
        path: String = "state",
    ): List<String> =
        when (expected) {
            is JsonObject -> {
                if (actual !is JsonObject) {
                    listOf("$path: erwartet Objekt, ist ${actual ?: "fehlend"}")
                } else {
                    expected.flatMap { (key, value) ->
                        if (key !in actual) listOf("$path.$key: Schlüssel fehlt") else diff(value, actual[key], "$path.$key")
                    }
                }
            }

            is JsonArray -> {
                when {
                    actual !is JsonArray -> listOf("$path: erwartet Array $expected, ist $actual")
                    actual.size != expected.size -> listOf("$path: erwartet $expected, ist $actual")
                    else -> expected.indices.flatMap { diff(expected[it], actual[it], "$path[$it]") }
                }
            }

            JsonNull -> {
                if (actual == JsonNull) emptyList() else listOf("$path: erwartet null, ist $actual")
            }

            is JsonPrimitive -> {
                if (actual == expected) emptyList() else listOf("$path: erwartet $expected, ist $actual")
            }
        }
}
