package eu.advantly.core.scoring.vectors

import eu.advantly.core.model.Discipline
import eu.advantly.core.model.End
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchFormat
import eu.advantly.core.model.MatchMode
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.model.StartEnds
import eu.advantly.core.scoring.ContinueDecision
import eu.advantly.core.scoring.Effect
import eu.advantly.core.scoring.EngineState
import eu.advantly.core.scoring.EventPayload
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.core.scoring.NextSetKind
import eu.advantly.core.scoring.PointWon
import eu.advantly.core.scoring.Preset
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.ScoringEngine
import eu.advantly.core.scoring.Undo
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.random.Random
import kotlin.system.exitProcess

/**
 * Generator der Golden-Testvektoren (01 §8.1): je Preset aus 02 §4 ein kanonischer Beispiel-Vektor unter
 * `testvectors/<sport>/<preset>.json`. Die Eventfolge ist pseudozufällig mit festem Seed je Preset-ID
 * (deterministisch), enthält einzelne UI-Undos und – im OPEN-Modus – Satzende-Entscheidungen. Checkpoints enthalten
 * den **vollständigen** kanonischen Zustand an markanten Stellen (Satzende, Seitenwechsel, Undo …).
 *
 * Aufruf über Gradle: `generateTestVectors` (schreibt) bzw. `checkTestVectorDrift` (vergleicht, schlägt bei Drift fehl).
 */
object GoldenVectors {
    private const val MAX_POINTS = 600
    private const val UNDO_EVERY = 37
    private const val REGULAR_CHECKPOINT_EVERY = 25

    private val markerEffects =
        setOf(
            Effect.SECOND_SERVER,
            Effect.SET_WON,
            Effect.MATCH_WON,
            Effect.MATCH_DRAWN,
            Effect.MATCH_ENDED,
            Effect.CHANGE_ENDS,
            Effect.RECEIVE_ORDER_SWAPPED,
            Effect.TIEBREAK_STARTED,
            Effect.ASK_ANOTHER_SET,
            Effect.UNDONE,
            Effect.IGNORED,
        )

    /** Relativer Pfad (mit `/`) → Dateiinhalt, sortiert. */
    fun generateAll(): Map<String, String> =
        Presets.all.associate { preset -> "${sportDir(preset.sport)}/${preset.id.lowercase()}.json" to render(preset) }.toSortedMap()

    private fun sportDir(sport: Sport): String = sport.name.lowercase().replace('_', '-')

    private fun lineupFor(discipline: Discipline): Lineup =
        if (discipline == Discipline.SINGLES) {
            Lineup(listOf(PlayerId("A1")), listOf(PlayerId("B1")), PlayerId("A1"), startEnds = StartEnds(End.END_1))
        } else {
            Lineup(
                listOf(PlayerId("A1"), PlayerId("A2")),
                listOf(PlayerId("B1"), PlayerId("B2")),
                PlayerId("A1"),
                startEnds = StartEnds(End.END_1),
            )
        }

    /** Erzeugt Eventfolge und Checkpoints für [preset] (Default-Disziplin). */
    fun script(preset: Preset): Triple<MatchFormat, Lineup, List<Pair<String, EventPayload>>> {
        val format = preset.format()
        val lineup = lineupFor(format.discipline)
        val random = Random(preset.id.hashCode().toLong())
        val payloads = mutableListOf<Pair<String, EventPayload>>()
        var engine: EngineState = ScoringEngine.replay(format, lineup, emptyList())
        var decisions = 0
        var points = 0

        fun push(payload: EventPayload) {
            val id = "e${payloads.size + 1}"
            val event = VectorIo.envelope(payloads.size, id, payloads.lastOrNull()?.first, payload)
            payloads += id to payload
            engine = ScoringEngine.apply(engine, event)
        }
        while (engine.state.status != MatchStatus.FINISHED && points < MAX_POINTS) {
            when (engine.state.status) {
                MatchStatus.AWAITING_DECISION -> {
                    decisions += 1
                    when {
                        decisions >= 3 -> push(ContinueDecision(anotherSet = false))
                        decisions == 2 && format.matchTiebreak != null -> push(ContinueDecision(true, NextSetKind.MATCH_TIEBREAK))
                        else -> push(ContinueDecision(true))
                    }
                }

                else -> {
                    points += 1
                    if (points % UNDO_EVERY == 0 && engine.undoTarget != null) {
                        push(Undo(checkNotNull(engine.undoTarget)))
                    } else {
                        push(PointWon(if (random.nextInt(100) < 58) Side.A else Side.B))
                    }
                }
            }
        }
        check(engine.state.status == MatchStatus.FINISHED) { "${preset.id}: Match nach $MAX_POINTS Punkten nicht beendet" }
        check(format.match.mode == MatchMode.OPEN || engine.state.result?.winner != null)
        return Triple(format, lineup, payloads)
    }

    fun render(preset: Preset): String {
        val (format, lineup, payloads) = script(preset)
        val events = payloads.mapIndexed { i, (id, p) -> VectorIo.envelope(i, id, payloads.getOrNull(i - 1)?.first, p) }
        val checkpointIndices = sortedSetOf(0, events.size)
        var engine = ScoringEngine.replay(format, lineup, emptyList())
        events.forEachIndexed { i, event ->
            engine = ScoringEngine.apply(engine, event)
            if (engine.state.effects.any { it in markerEffects } || (i + 1) % REGULAR_CHECKPOINT_EVERY == 0) checkpointIndices += i + 1
        }
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"schema\": 1,\n")
        sb.append("  \"id\": ${str("GV-${preset.id}")},\n")
        sb.append("  \"title\": ${str("Golden-Vektor ${preset.id} (generiert)")},\n")
        sb.append(
            "  \"description\": ${str(
                "Aus der Kotlin-Engine generierter kanonischer Beispiel-Vektor für das Preset ${preset.id} " +
                    "(Pseudozufall mit festem Seed, einzelne UI-Undos). Checkpoints enthalten den vollständigen Zustand. " +
                    "Nicht von Hand ändern: gradlew :shared:core-scoring:generateTestVectors.",
            )},\n",
        )
        sb.append("  \"sources\": [${str("Advantly 02-scoring-regeln.md §4 (Preset ${preset.id}), §6–§8")}],\n")
        sb.append("  \"config\": ${compact(vectorJson.encodeToJsonElement(MatchFormat.serializer(), format))},\n")
        sb.append("  \"lineup\": ${compact(vectorJson.encodeToJsonElement(Lineup.serializer(), lineup))},\n")
        sb.append("  \"events\": [\n")
        sb.append(
            payloads.joinToString(",\n") { (id, p) ->
                val body = vectorJson.encodeToJsonElement(EventPayload.serializer(), p) as JsonObject
                "    " + compact(JsonObject(mapOf("id" to JsonPrimitive(id)) + body))
            },
        )
        sb.append("\n  ],\n")
        sb.append("  \"expect\": [\n")
        sb.append(
            checkpointIndices.joinToString(",\n") { n ->
                val state = ScoringEngine.fold(format, lineup, events.subList(0, n))
                val cp =
                    buildJsonObject {
                        put("afterEvent", n)
                        put("label", if (n == 0) "Start" else "nach ${payloads[n - 1].first}")
                        put("state", VectorIo.canonical(state))
                    }
                "    " + compact(cp)
            },
        )
        sb.append("\n  ]\n")
        sb.append("}\n")
        return sb.toString()
    }

    private fun compact(element: JsonElement): String = vectorJson.encodeToString(JsonElement.serializer(), element)

    private fun str(s: String): String = compact(JsonPrimitive(s))
}

/** Einstieg für Gradle: `generate <dir>` schreibt, `check <dir>` vergleicht. */
fun main(args: Array<String>) {
    require(args.size == 2) { "Aufruf: generate|check <testvectors-Ordner>" }
    val (mode, dirArg) = args
    val dir = File(dirArg).canonicalFile
    val generated = GoldenVectors.generateAll()
    val existing =
        dir
            .walkTopDown()
            .filter { it.isFile && it.extension == "json" }
            .associate { it.relativeTo(dir).invariantSeparatorsPath to it.readText().replace("\r\n", "\n") }
    when (mode) {
        "generate" -> {
            (existing.keys - generated.keys).forEach { File(dir, it).delete() }
            generated.forEach { (path, content) ->
                val file = File(dir, path)
                file.parentFile.mkdirs()
                file.writeText(content)
            }
            println("generateTestVectors: ${generated.size} Golden-Vektoren nach $dir geschrieben")
        }

        "check" -> {
            val missing = generated.keys - existing.keys
            val stale = existing.keys - generated.keys
            val changed = generated.filter { (path, content) -> existing[path] != null && existing[path] != content }.keys
            if (missing.isEmpty() && stale.isEmpty() && changed.isEmpty()) {
                println("checkTestVectorDrift: ${generated.size} Golden-Vektoren aktuell")
            } else {
                System.err.println("checkTestVectorDrift: Golden-Vektoren weichen vom Engine-Stand ab.")
                missing.forEach { System.err.println("  fehlt:    $it") }
                stale.forEach { System.err.println("  veraltet: $it") }
                changed.forEach { System.err.println("  geändert: $it") }
                System.err.println("Absicht? Dann: gradlew :shared:core-scoring:generateTestVectors und Änderung prüfen/einchecken.")
                exitProcess(1)
            }
        }

        else -> {
            error("Unbekannter Modus '$mode'")
        }
    }
}
