package eu.advantly.presentation.live

/**
 * Haptik-Ereignisse des Live-Scores (04 §5.8). Die Plattform-Umsetzung (Vibrator-Primitives bzw.
 * UIFeedbackGenerator) liegt in `ui-phone`.
 */
enum class HapticCue {
    PointUs,
    PointThem,
    Undo,
    GameWon,
    GameLost,
    SetWon,
    SetLost,
    ChangeEnds,
    Rejected,
}

/** Sprungziele zum Testen der Hinweis-Pill (wie die „Demo“-Knöpfe im Mockup). */
enum class DemoScenario {
    BreakPoint,
    SetPoint,
    MatchPoint,
    Deuce,
    Tiebreak,
}

/** Ereignis im Fake-Log. Die echte Engine (`core-scoring`) hat ein eigenes, reicheres Event-Modell. */
sealed interface LiveEvent {
    data class Point(
        val team: Team,
    ) : LiveEvent

    data class Demo(
        val scenario: DemoScenario,
    ) : LiveEvent
}

/** Ergebnis einer Eingabe: Vorher/Nachher (für Undo-Snackbar), Richtung (Roll-Animation) und Haptik. */
data class ScoreChange(
    val before: FakeTennisState,
    val after: FakeTennisState,
    val direction: Direction,
    val cue: HapticCue?,
    val team: Team?,
) {
    enum class Direction { Forward, Backward }
}

/**
 * Spike S4: Mini-„Event Sourcing“ für den Fake-Zustand – `state = fold(events)`, Undo entfernt das letzte
 * Ereignis (die echte Engine nutzt Kompensations-Events, 01 §3). Nicht thread-sicher: nur vom UI-Thread benutzen.
 */
class LiveScoreSession(
    private val format: FakeTennisFormat = FakeTennisFormat(),
) {
    private val events = mutableListOf<LiveEvent>()
    private val redoStack = mutableListOf<LiveEvent>()

    var state: FakeTennisState = FakeTennisState(format)
        private set

    val canUndo: Boolean get() = events.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Punkt für [team]; `null`, wenn das Match schon entschieden ist (UI meldet „abgelehnt“). */
    fun score(team: Team): ScoreChange? {
        if (state.isFinished) return null
        val change = append(LiveEvent.Point(team))
        redoStack.clear()
        return change
    }

    fun demo(scenario: DemoScenario): ScoreChange {
        val change = append(LiveEvent.Demo(scenario))
        redoStack.clear()
        return change
    }

    fun undo(): ScoreChange? {
        val last = events.removeLastOrNull() ?: return null
        redoStack += last
        val before = state
        state = fold(events)
        return ScoreChange(before, state, ScoreChange.Direction.Backward, HapticCue.Undo, (last as? LiveEvent.Point)?.team)
    }

    fun redo(): ScoreChange? {
        val next = redoStack.removeLastOrNull() ?: return null
        return append(next)
    }

    fun reset() {
        events.clear()
        redoStack.clear()
        state = FakeTennisState(format)
    }

    private fun append(event: LiveEvent): ScoreChange {
        val before = state
        events += event
        state = apply(before, event)
        val team = (event as? LiveEvent.Point)?.team
        val cue = team?.let { cueFor(before, state, it) }
        return ScoreChange(before, state, ScoreChange.Direction.Forward, cue, team)
    }

    private fun fold(log: List<LiveEvent>): FakeTennisState = log.fold(FakeTennisState(format), ::apply)

    private fun apply(
        state: FakeTennisState,
        event: LiveEvent,
    ): FakeTennisState =
        when (event) {
            is LiveEvent.Point -> FakeTennis.point(state, event.team)
            is LiveEvent.Demo -> demoState(event.scenario)
        }

    private fun demoState(scenario: DemoScenario): FakeTennisState {
        val base = FakeTennisState(format)
        return when (scenario) {
            // Gegner schlägt auf, wir führen 40:15 → Breakball Wir
            DemoScenario.BreakPoint -> {
                base.copy(gamesUs = 2, gamesThem = 3, pointsUs = 3, pointsThem = 1, server = Team.Them)
            }

            DemoScenario.SetPoint -> {
                base.copy(gamesUs = 5, gamesThem = 4, pointsUs = 3, pointsThem = 2, server = Team.Us)
            }

            DemoScenario.MatchPoint -> {
                base.copy(
                    completedSets = listOf(SetResult(6, 4)),
                    gamesUs = 5,
                    gamesThem = 3,
                    pointsUs = 3,
                    pointsThem = 0,
                    server = Team.Us,
                )
            }

            DemoScenario.Deuce -> {
                base.copy(gamesUs = 3, gamesThem = 3, pointsUs = 3, pointsThem = 3, server = Team.Them)
            }

            DemoScenario.Tiebreak -> {
                base.copy(
                    gamesUs = 6,
                    gamesThem = 6,
                    pointsUs = 5,
                    pointsThem = 4,
                    tiebreak = true,
                    tiebreakFirstServer = Team.Us,
                    server = Team.Them,
                )
            }
        }
    }

    companion object {
        /** Stärkstes Ereignis gewinnt: Satz > Seitenwechsel > Game > Punkt (04 §5.8, Spalte Handy). */
        fun cueFor(
            before: FakeTennisState,
            after: FakeTennisState,
            team: Team,
        ): HapticCue =
            when {
                after.completedSets.size > before.completedSets.size -> if (team == Team.Us) HapticCue.SetWon else HapticCue.SetLost
                FakeTennis.changeEnds(after) -> HapticCue.ChangeEnds
                after.games(team) > before.games(team) -> if (team == Team.Us) HapticCue.GameWon else HapticCue.GameLost
                else -> if (team == Team.Us) HapticCue.PointUs else HapticCue.PointThem
            }
    }
}
