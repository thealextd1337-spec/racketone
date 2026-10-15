package eu.advantly.core.scoring

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.EventId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchFormat
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.Side
import kotlin.random.Random

/**
 * Zufällig erzeugtes Match für Property-Tests und Golden-Vektoren (deterministisch über den Seed).
 *
 * @property events alle erzeugten Events in **Erzeugungsreihenfolge** (nicht in Log-Ordnung)
 * @property incremental Ergebnis von [ScoringEngine.apply] in Erzeugungsreihenfolge (also teils außer der Ordnung)
 */
data class RandomMatch(
    val format: MatchFormat,
    val lineup: Lineup,
    val events: List<ScoreEvent>,
    val incremental: EngineState,
    val devices: Int,
)

/**
 * Erzeugt plausible, aber bewusst „schmutzige“ Historien: überwiegend Punkte, dazu UI-Undos, Redo, Undo mittlerer
 * Events, Pausen, Korrekturen, Satzende-Antworten und ungültige Events (→ `ignoredEvents`).
 *
 * Mehrere Geräte (1–3) zählen jeweils auf **ihrem** Stand (eigene Lamport-Uhr, `parentId` = eigener Log-Kopf) und
 * synchronisieren nur gelegentlich. Dadurch entstehen verzweigte Historien mit gemeinsamen Eltern, gleiche Lamports
 * (Tie-Break über `deviceId`), Doppel-Taps innerhalb von 5 s, Widersprüche und Zustellung außer der Ordnung.
 */
object RandomMatches {
    private class Device(
        val id: DeviceId,
        var engine: EngineState,
    ) {
        var clock = 0L
        var seq = 0L

        fun receive(event: ScoreEvent) {
            engine = ScoringEngine.apply(engine, event)
            clock = maxOf(clock, event.lamport)
        }
    }

    fun generate(
        presetIndex: Int,
        seed: Long,
        length: Int,
        deviceCount: Int? = null,
    ): RandomMatch {
        val random = Random(seed)
        val preset = Presets.all[presetIndex % Presets.all.size]
        val format = preset.format(preset.disciplines[random.nextInt(preset.disciplines.size)])
        val lineup = lineupFor(format.discipline, firstServer = if (random.nextBoolean()) "A1" else "B1")
        val start = ScoringEngine.replay(format, lineup, emptyList())
        val devices = List(deviceCount ?: (1 + random.nextInt(3))) { Device(DeviceId("d${it + 1}"), start) }
        val all = mutableListOf<ScoreEvent>()
        var global = start
        var clockMs = 0L

        fun emit(
            device: Device,
            payload: EventPayload,
            lamport: Long = device.clock + 1,
            parent: EventId? =
                device.engine.events
                    .lastOrNull()
                    ?.id,
            wallClockMs: Long = clockMs,
        ): ScoreEvent {
            device.seq += 1
            val event =
                ScoreEvent(
                    EventId("${device.id.value}-${device.seq}"),
                    MatchId("m"),
                    device.id,
                    device.seq,
                    lamport,
                    wallClockMs,
                    parent,
                    payload,
                )
            device.receive(event)
            all += event
            global = ScoringEngine.apply(global, event)
            return event
        }

        while (all.size < length) {
            clockMs += 2_000L + random.nextInt(8_000)
            val device = devices[random.nextInt(devices.size)]
            val r = random.nextInt(100)
            if (devices.size > 1 && r < 12) {
                // Sync: zwei Geräte gleichen ihre Logs in zufälliger Reihenfolge ab
                val other = devices.filter { it !== device }.random(random)
                val missingHere =
                    other.engine.events
                        .filter { it !in device.engine.events }
                        .shuffled(random)
                val missingThere =
                    device.engine.events
                        .filter { it !in other.engine.events }
                        .shuffled(random)
                missingHere.forEach(device::receive)
                missingThere.forEach(other::receive)
                continue
            }
            if (devices.size > 1 && r < 17 && device.engine.state.status == MatchStatus.IN_PROGRESS) {
                // Doppel-Tap bzw. Widerspruch: zwei Geräte mit gleichem Kopf tippen fast gleichzeitig
                val other = devices.filter { it !== device }.random(random)
                val parent =
                    device.engine.events
                        .lastOrNull()
                        ?.id
                val lamport = maxOf(device.clock, other.clock) + 1
                val side = if (random.nextBoolean()) Side.A else Side.B
                emit(device, PointWon(side), lamport, parent)
                val otherSide = if (random.nextInt(4) == 0) side.other else side
                emit(other, PointWon(otherSide), lamport, parent, clockMs + random.nextInt(-3_000, 3_001))
                continue
            }
            emit(device, payloadFor(device.engine, lineup, random))
        }
        return RandomMatch(format, lineup, all.toList(), global, devices.size)
    }

    private fun payloadFor(
        engine: EngineState,
        lineup: Lineup,
        random: Random,
    ): EventPayload {
        val state = engine.state
        val known = engine.events
        val players = lineup.a + lineup.b
        val r = random.nextInt(100)
        return when {
            state.status == MatchStatus.AWAITING_DECISION && r < 85 -> {
                ContinueDecision(
                    anotherSet = random.nextInt(10) < 7,
                    nextSetKind = if (random.nextInt(4) == 0) NextSetKind.MATCH_TIEBREAK else NextSetKind.REGULAR,
                )
            }

            state.status == MatchStatus.FINISHED && r < 50 && engine.undoTarget != null -> {
                Undo(engine.undoTarget!!)
            }

            r < 74 -> {
                PointWon(if (random.nextInt(100) < 55) Side.A else Side.B)
            }

            r < 82 && engine.undoTarget != null -> {
                Undo(engine.undoTarget!!)
            }

            r < 84 && known.isNotEmpty() -> {
                Undo(known[random.nextInt(known.size)].id)
            }

            r < 87 -> {
                Paused
            }

            r < 89 -> {
                Resumed
            }

            r < 95 -> {
                when (random.nextInt(4)) {
                    0 -> {
                        Correction.initialServer(players.random(random))
                    }

                    1 -> {
                        Correction.server(players.random(random), serverNumber = random.nextInt(1, 3), receiver = players.random(random))
                    }

                    2 -> {
                        val side = if (random.nextBoolean()) Side.A else Side.B
                        Correction.servingOrder(side, lineup.players(side).shuffled(random))
                    }

                    else -> {
                        val side = if (random.nextBoolean()) Side.A else Side.B
                        Correction.receivePositions(side, lineup.players(side).random(random))
                    }
                }
            }

            r < 97 && known.isNotEmpty() -> {
                PointDetailSet(known[random.nextInt(known.size)].id, PointDetail(PointDetailKind.WINNER))
            }

            else -> {
                MatchEnded(
                    EndReason.entries[random.nextInt(EndReason.entries.size)],
                    if (random.nextInt(5) == 0) {
                        null
                    } else if (random.nextBoolean()) {
                        Side.A
                    } else {
                        Side.B
                    },
                )
            }
        }
    }
}
