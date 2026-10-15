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
import eu.advantly.core.scoring.EventLog
import eu.advantly.core.scoring.EventPayload
import eu.advantly.core.scoring.MatchDriver
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.core.scoring.NextSetKind
import eu.advantly.core.scoring.PointWon
import eu.advantly.core.scoring.Preset
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.RandomMatches
import eu.advantly.core.scoring.ScoreEvent
import eu.advantly.core.scoring.ScoringEngine
import eu.advantly.core.scoring.Undo
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.random.Random
import kotlin.system.exitProcess

/**
 * Generator der Golden-Testvektoren (01 §8.1) unter `testvectors/<sport>/`:
 *
 * - `presets/` – je Preset **und** Disziplin ein kanonischer Beispiel-Vektor (Pseudozufall mit festem Seed, UI-Undos,
 *   im OPEN-Modus Satzende-Antworten),
 * - `rules/` – Export der Regel-Tabellen-Szenarien ([RuleScenarios]),
 * - `random/` – [RANDOM_PER_SPORT] Zufallsmatches je Sportart mit festem Seed, inkl. verzweigter Historien mehrerer
 *   Geräte (explizite Hülle `lamport`/`deviceId`/`parentId`/`wallClockMs`), Doppel-Taps, Undo-Ketten und Korrekturen.
 *
 * Checkpoints enthalten den **vollständigen** kanonischen Zustand. Aufruf über Gradle: `generateTestVectors` (schreibt)
 * bzw. `checkTestVectorDrift` (vergleicht, schlägt bei Drift fehl; hängt an `check`).
 */
object GoldenVectors {
    /** Zufallsmatches je Sportart. 01 §8.1 nennt ~200; wegen der Repo-Größe vorerst 20 (Konstante, jederzeit erhöhbar). */
    const val RANDOM_PER_SPORT = 20

    private const val MAX_POINTS = 600
    private const val UNDO_EVERY = 37

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

    /** Wann ein Checkpoint geschrieben wird. */
    private class Policy(
        val every: Int,
        val markers: Boolean,
    )

    /** Relativer Pfad (mit `/`) → Dateiinhalt, sortiert. */
    fun generateAll(): Map<String, String> {
        val files = sortedMapOf<String, String>()
        for (preset in Presets.all) {
            for (d in preset.disciplines) {
                val name = "${preset.id.lowercase()}-${d.name.lowercase()}"
                files["${dir(preset.sport)}/presets/$name.json"] = presetVector(preset, d)
            }
        }
        for (scenario in RuleScenarios.all()) {
            files["${dir(scenario.sport)}/rules/${scenario.name}.json"] = scenarioVector(scenario)
        }
        for (sport in Sport.entries) {
            repeat(RANDOM_PER_SPORT) { i ->
                files["${dir(sport)}/random/random-${(i + 1).toString().padStart(3, '0')}.json"] = randomVector(sport, i)
            }
        }
        return files
    }

    private fun dir(sport: Sport): String = sport.name.lowercase().replace('_', '-')

    private fun tag(sport: Sport): String =
        when (sport) {
            Sport.TENNIS -> "TEN"
            Sport.PADEL -> "PAD"
            Sport.PICKLEBALL -> "PB"
            Sport.BADMINTON -> "BAD"
            Sport.TABLE_TENNIS -> "TT"
        }

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

    // --- presets/ -------------------------------------------------------------------------------------------------

    /** Eventfolge für [preset] in Disziplin [discipline]: Punkte (58 % A), alle [UNDO_EVERY] Punkte ein UI-Undo. */
    private fun presetScript(
        preset: Preset,
        discipline: Discipline,
    ): Triple<MatchFormat, Lineup, List<EventPayload>> {
        val format = preset.format(discipline)
        val lineup = lineupFor(discipline)
        val random = Random("${preset.id}/$discipline".hashCode().toLong())
        val payloads = mutableListOf<EventPayload>()
        var engine: EngineState = ScoringEngine.replay(format, lineup, emptyList())
        var decisions = 0
        var points = 0

        fun push(payload: EventPayload) {
            val i = payloads.size
            payloads += payload
            engine = ScoringEngine.apply(engine, VectorIo.envelope(i, "e${i + 1}", if (i == 0) null else "e$i", payload))
        }
        while (engine.state.status != MatchStatus.FINISHED && points < MAX_POINTS) {
            if (engine.state.status == MatchStatus.AWAITING_DECISION) {
                decisions += 1
                when {
                    decisions >= 3 -> push(ContinueDecision(anotherSet = false))
                    decisions == 2 && format.matchTiebreak != null -> push(ContinueDecision(true, NextSetKind.MATCH_TIEBREAK))
                    else -> push(ContinueDecision(true))
                }
            } else {
                points += 1
                val target = engine.undoTarget
                if (points % UNDO_EVERY == 0 && target != null) {
                    push(Undo(target))
                } else {
                    push(PointWon(if (random.nextInt(100) < 58) Side.A else Side.B))
                }
            }
        }
        check(engine.state.status == MatchStatus.FINISHED) { "${preset.id}: Match nach $MAX_POINTS Punkten nicht beendet" }
        check(format.match.mode == MatchMode.OPEN || engine.state.result?.winner != null)
        return Triple(format, lineup, payloads)
    }

    private fun presetVector(
        preset: Preset,
        discipline: Discipline,
    ): String {
        val (format, lineup, payloads) = presetScript(preset, discipline)
        return render(
            id = "GV-${preset.id}-${discipline.name}",
            title = "Golden-Vektor ${preset.id} (${discipline.name}, generiert)",
            description =
                "Aus der Kotlin-Engine generierter kanonischer Beispiel-Vektor für das Preset ${preset.id} in ${discipline.name} " +
                    "(Pseudozufall mit festem Seed, einzelne UI-Undos).",
            source = "Advantly 02-scoring-regeln.md §4 (Preset ${preset.id}), §6–§8",
            format = format,
            lineup = lineup,
            events = synthetic(payloads),
            explicitEnvelope = false,
            policy = Policy(every = 25, markers = true),
        )
    }

    // --- rules/ ---------------------------------------------------------------------------------------------------

    private fun scenarioVector(scenario: RuleScenario): String {
        val explicit = scenario.events != null
        val events =
            scenario.events?.let { EventLog.distinctOrdered(it) }
                ?: MatchDriver(scenario.format, scenario.lineup).apply { checkNotNull(scenario.script)(this) }.events.let { evs ->
                    synthetic(evs.map { it.payload })
                }
        return render(
            id = "GV-${tag(scenario.sport)}-RULE-${scenario.name.uppercase()}",
            title = "Regel-Szenario ${scenario.name} (generiert)",
            description = scenario.description,
            source = "Advantly 02-scoring-regeln.md §6–§9; Regel-Tabellen-Tests in core-scoring/commonTest",
            format = scenario.format,
            lineup = scenario.lineup,
            events = events,
            explicitEnvelope = explicit,
            policy = Policy(every = 5, markers = true),
        )
    }

    // --- random/ --------------------------------------------------------------------------------------------------

    private fun randomVector(
        sport: Sport,
        index: Int,
    ): String {
        val presets = Presets.all.filter { it.sport == sport }
        val preset = presets[index % presets.size]
        val seed = "$sport/random/$index".hashCode().toLong()
        val length = 40 + Random(seed).nextInt(100)
        val match = RandomMatches.generate(Presets.all.indexOf(preset), seed, length, deviceCount = 1 + index % 3)
        return render(
            id = "GV-${tag(sport)}-RANDOM-${(index + 1).toString().padStart(3, '0')}",
            title = "Zufallsmatch ${index + 1} ${preset.id} mit ${match.devices} Gerät(en) (generiert)",
            description =
                "Deterministisches Zufallsmatch (Seed aus Sportart und Nummer): verzweigte Historien mehrerer Geräte mit gemeinsamen " +
                    "Eltern, gleichen Lamports, Doppel-Taps, Undo-Ketten, Korrekturen und ungültigen Events. Events in kanonischer " +
                    "Ordnung mit expliziter Hülle (lamport, deviceId, parentId, wallClockMs).",
            source = "Advantly 01-architektur.md §4.4, §8.1; 02-scoring-regeln.md §7.3",
            format = match.format,
            lineup = match.lineup,
            events = EventLog.distinctOrdered(match.events),
            explicitEnvelope = true,
            policy = Policy(every = 30, markers = false),
        )
    }

    // --- Ausgabe --------------------------------------------------------------------------------------------------

    private fun synthetic(payloads: List<EventPayload>): List<ScoreEvent> =
        payloads.mapIndexed { i, p -> VectorIo.envelope(i, "e${i + 1}", if (i == 0) null else "e$i", p) }

    @Suppress("LongParameterList")
    private fun render(
        id: String,
        title: String,
        description: String,
        source: String,
        format: MatchFormat,
        lineup: Lineup,
        events: List<ScoreEvent>,
        explicitEnvelope: Boolean,
        policy: Policy,
    ): String {
        val checkpointIndices = sortedSetOf(0, events.size)
        var engine = ScoringEngine.replay(format, lineup, emptyList())
        events.forEachIndexed { i, event ->
            engine = ScoringEngine.apply(engine, event)
            val marker = policy.markers && engine.state.effects.any { it in markerEffects }
            if (marker || (i + 1) % policy.every == 0) checkpointIndices += i + 1
        }
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"schema\": 1,\n")
        sb.append("  \"id\": ${str(id)},\n")
        sb.append("  \"title\": ${str(title)},\n")
        sb.append("  \"description\": ${str("$description Nicht von Hand ändern: gradlew :shared:core-scoring:generateTestVectors.")},\n")
        sb.append("  \"sources\": [${str(source)}],\n")
        sb.append("  \"config\": ${compact(vectorJson.encodeToJsonElement(MatchFormat.serializer(), format))},\n")
        sb.append("  \"lineup\": ${compact(vectorJson.encodeToJsonElement(Lineup.serializer(), lineup))},\n")
        sb.append("  \"events\": [\n")
        sb.append(events.joinToString(",\n") { "    " + compact(eventJson(it, explicitEnvelope)) })
        sb.append("\n  ],\n")
        sb.append("  \"expect\": [\n")
        sb.append(
            checkpointIndices.joinToString(",\n") { n ->
                val state = ScoringEngine.fold(format, lineup, events.subList(0, n))
                val cp =
                    buildJsonObject {
                        put("afterEvent", n)
                        put("label", if (n == 0) "Start" else "nach ${events[n - 1].id.value}")
                        put("state", VectorIo.canonical(state))
                    }
                "    " + compact(cp)
            },
        )
        sb.append("\n  ]\n")
        sb.append("}\n")
        return sb.toString()
    }

    private fun eventJson(
        event: ScoreEvent,
        explicitEnvelope: Boolean,
    ): JsonObject {
        val body = vectorJson.encodeToJsonElement(EventPayload.serializer(), event.payload) as JsonObject
        val head = linkedMapOf<String, JsonElement>("id" to JsonPrimitive(event.id.value))
        if (explicitEnvelope) {
            head["lamport"] = JsonPrimitive(event.lamport)
            head["deviceId"] = JsonPrimitive(event.deviceId.value)
            head["parentId"] = event.parentId?.let { JsonPrimitive(it.value) } ?: JsonNull
            head["wallClockMs"] = JsonPrimitive(event.wallClockMs)
        }
        return JsonObject(head + body)
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
            dir.walkBottomUp().filter { it.isDirectory && it != dir && it.listFiles().isNullOrEmpty() }.forEach { it.delete() }
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
