package eu.advantly.core.scoring

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.EventId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchFormat
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.Side
import kotlin.random.Random

/** Zufällig erzeugtes Match für Property-Tests (deterministisch über den Seed). */
data class RandomMatch(
    val format: MatchFormat,
    val lineup: Lineup,
    val events: List<ScoreEvent>,
    val incremental: EngineState,
)

/**
 * Erzeugt eine plausible, aber bewusst „schmutzige“ Eventfolge: überwiegend Punkte, dazu UI-Undos, Redo,
 * Undo mittlerer Events, Pausen, Korrekturen, Satzende-Antworten und ungültige Events (→ `ignoredEvents`).
 * Zwei Geräte mit Lamport-Uhr; Events werden inkrementell über [ScoringEngine.apply] angewendet.
 */
object RandomMatches {
    fun generate(
        presetIndex: Int,
        seed: Long,
        length: Int,
    ): RandomMatch {
        val random = Random(seed)
        val preset = Presets.all[presetIndex % Presets.all.size]
        val format = preset.format(preset.disciplines[random.nextInt(preset.disciplines.size)])
        val lineup = lineupFor(format.discipline, firstServer = if (random.nextBoolean()) "A1" else "B1")
        val players = lineup.a + lineup.b
        var engine = ScoringEngine.replay(format, lineup, emptyList())
        val events = mutableListOf<ScoreEvent>()
        var lamport = 0L
        repeat(length) { i ->
            val state = engine.state
            val r = random.nextInt(100)
            val payload: EventPayload =
                when {
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

                    r < 84 && events.isNotEmpty() -> {
                        Undo(events[random.nextInt(events.size)].id)
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
                                Correction.server(
                                    players.random(random),
                                    serverNumber = random.nextInt(1, 3),
                                    receiver = players.random(random),
                                )
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

                    r < 97 && events.isNotEmpty() -> {
                        PointDetailSet(events[random.nextInt(events.size)].id, PointDetail(PointDetailKind.WINNER))
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
            lamport += 1
            val device = if (random.nextBoolean()) "phone" else "watch"
            val event =
                ScoreEvent(
                    id = EventId("e${i + 1}"),
                    matchId = MatchId("m"),
                    deviceId = DeviceId(device),
                    deviceSeq = i + 1L,
                    lamport = lamport,
                    wallClockMs = i * 7_000L,
                    parentId = events.lastOrNull()?.id,
                    payload = payload,
                )
            events += event
            engine = ScoringEngine.apply(engine, event)
        }
        return RandomMatch(format, lineup, events, engine)
    }
}
