package eu.advantly.core.scoring

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.Discipline
import eu.advantly.core.model.EventId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchFormat
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import kotlin.test.assertEquals

/** Spieler-ID-Kurzform für Tests. */
fun p(id: String): PlayerId = PlayerId(id)

/** Standard-Lineup: A1/B1 bzw. A1,A2/B1,B2, A1 schlägt auf. */
fun lineupFor(
    discipline: Discipline,
    firstServer: String = "A1",
): Lineup =
    if (discipline == Discipline.SINGLES) {
        Lineup(listOf(p("A1")), listOf(p("B1")), p(firstServer))
    } else {
        Lineup(listOf(p("A1"), p("A2")), listOf(p("B1"), p("B2")), p(firstServer))
    }

/**
 * Test-Treiber: erzeugt Events mit synthetischer Hülle, wendet sie inkrementell an und prüft dabei jedes Mal,
 * dass das inkrementelle Ergebnis dem vollständigen Fold entspricht.
 */
class MatchDriver(
    val format: MatchFormat,
    val lineup: Lineup = lineupFor(format.discipline),
) {
    private var engine: EngineState = ScoringEngine.replay(format, lineup, emptyList())
    private val log = mutableListOf<ScoreEvent>()

    val events: List<ScoreEvent> get() = log
    val state: MatchState get() = engine.state
    val engineState: EngineState get() = engine

    fun send(payload: EventPayload): EventId {
        val index = log.size
        val id = EventId("e${index + 1}")
        val event =
            ScoreEvent(
                id = id,
                matchId = MatchId("m"),
                deviceId = DeviceId("d"),
                deviceSeq = index + 1L,
                lamport = index + 1L,
                wallClockMs = index * 10_000L,
                parentId = log.lastOrNull()?.id,
                payload = payload,
            )
        log += event
        engine = ScoringEngine.apply(engine, event)
        assertEquals(ScoringEngine.fold(format, lineup, log), engine.state, "apply ≠ fold nach ${event.id}")
        return id
    }

    fun point(side: Side): EventId = send(PointWon(side))

    /** Punkte als Zeichenkette, z. B. `"AABBA"`. */
    fun points(sequence: String): MatchDriver {
        sequence.filter { it == 'A' || it == 'B' }.forEach { point(if (it == 'A') Side.A else Side.B) }
        return this
    }

    /** Hält [games] Spiele lang jeweils das Spiel des Aufschlägers zu null (Tennis/Padel). */
    fun holdGames(games: Int): MatchDriver {
        repeat(games) { repeat(4) { point(checkNotNull(state.serve).side) } }
        return this
    }

    /** UI-Undo auf das letzte wirksame Zähl-Event. */
    fun undo(): EventId = send(Undo(checkNotNull(engine.undoTarget)))
}
