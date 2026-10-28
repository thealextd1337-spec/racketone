package eu.advantly.wear.live

import eu.advantly.core.model.Discipline
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.ServeTracking
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.model.TelemetryVisibility
import eu.advantly.core.scoring.NextSetKind
import eu.advantly.core.scoring.ServeOutcome
import eu.advantly.match.session.MatchSession
import eu.advantly.match.session.ServerChoiceContext
import eu.advantly.presentation.match.LocalDay
import eu.advantly.wear.match.FormatChoice
import eu.advantly.wear.match.PlayerSlot
import eu.advantly.wear.match.WatchDirectory
import eu.advantly.wear.scoring.TapGuard
import kotlinx.coroutines.flow.StateFlow

/** Ein typisierter Eintritt fuer alle Controlleraktionen; Android injiziert dieselbe enge Operationsgrenze. */
class WatchController(
    session: MatchSession,
    directory: WatchDirectory,
    prefs: WatchPrefs,
    wallClock: () -> Long,
    elapsed: () -> Long,
    uptime: () -> Long,
    today: () -> LocalDay,
    tapGuard: TapGuard = TapGuard(),
    private val operationBoundary: (() -> Unit) -> Boolean = {
        it()
        true
    },
    private val disposeBoundary: () -> Unit = {},
) {
    private val core = WatchControllerCore(session, directory, prefs, wallClock, elapsed, uptime, today, tapGuard)
    val state: StateFlow<WatchUiState> get() = core.state
    var effects: ((WatchEffect) -> Unit)?
        get() = core.effects
        set(value) {
            core.effects = value
        }

    private fun <T> action(block: () -> T): T? {
        var value: T? = null
        return if (operationBoundary { value = block() }) value else null
    }

    fun dispose() {
        try {
            core.dispose()
        } finally {
            disposeBoundary()
        }
    }

    fun setInteractive(active: Boolean) {
        action { core.setInteractive(active) }
    }

    fun start() {
        action { core.start() }
    }

    fun tick() {
        action { core.tick() }
    }

    fun onWake() {
        action { core.onWake() }
    }

    fun newMatch() {
        action { core.newMatch() }
    }

    fun repeatLast(): Boolean = action { core.repeatLast() } ?: false

    fun resume(matchId: MatchId): Boolean = action { core.resume(matchId) } ?: false

    fun setWorkoutEnabled(enabled: Boolean) {
        action { core.setWorkoutEnabled(enabled) }
    }

    fun setTelemetryVisibility(visibility: TelemetryVisibility) {
        action { core.setTelemetryVisibility(visibility) }
    }

    fun selectSport(sport: Sport) {
        action { core.selectSport(sport) }
    }

    fun selectDiscipline(discipline: Discipline) {
        action { core.selectDiscipline(discipline) }
    }

    fun selectFormat(choice: FormatChoice) {
        action { core.selectFormat(choice) }
    }

    fun selectPlayer(
        slot: PlayerSlot,
        player: PlayerId?,
    ) {
        action { core.selectPlayer(slot, player) }
    }

    fun selectGamesPerSet(games: Int) {
        action { core.selectGamesPerSet(games) }
    }

    fun setServeTracking(tracking: ServeTracking) {
        action { core.setServeTracking(tracking) }
    }

    fun selectServer(
        server: Side,
        serverIndex: Int = 0,
    ): Boolean = action { core.selectServer(server, serverIndex) } ?: false

    fun back() {
        action { core.back() }
    }

    fun point(side: Side): Boolean = action { core.point(side) } ?: false

    fun removePoint(side: Side): Boolean = action { core.removePoint(side) } ?: false

    fun setServe(outcome: ServeOutcome): Boolean = action { core.setServe(outcome) } ?: false

    fun openServerChoice() {
        action { core.openServerChoice() }
    }

    fun chooseServer(player: PlayerId): Boolean = action { core.chooseServer(player) } ?: false

    fun chooseServer(
        player: PlayerId,
        expected: ServerChoiceContext?,
    ): Boolean = action { core.chooseServer(player, expected) } ?: false

    fun startNextSet(kind: NextSetKind): Boolean = action { core.startNextSet(kind) } ?: false

    fun undo(): Boolean = action { core.undo() } ?: false

    fun redo(): Boolean = action { core.redo() } ?: false

    fun pause(): Boolean = action { core.pause() } ?: false

    fun resumePlay(): Boolean = action { core.resumePlay() } ?: false

    fun lock() {
        action { core.lock() }
    }

    fun unlock() {
        action { core.unlock() }
    }

    fun continueDecision(
        anotherSet: Boolean,
        matchTiebreak: Boolean = false,
    ): Boolean = action { core.continueDecision(anotherSet, matchTiebreak) } ?: false

    fun requestEnd() {
        action { core.requestEnd() }
    }

    fun requestCorrection() {
        action { core.requestCorrection() }
    }

    fun correctServer(player: PlayerId): Boolean = action { core.correctServer(player) } ?: false

    fun swapPositions(side: Side): Boolean = action { core.swapPositions(side) } ?: false

    fun confirmEnd(): Boolean = action { core.confirmEnd() } ?: false

    fun requestTakeOver() {
        action { core.requestTakeOver() }
    }

    fun confirmTakeOver(): Boolean = action { core.confirmTakeOver() } ?: false

    fun dismissOverlay() {
        action { core.dismissOverlay() }
    }

    fun followUp(): Boolean = action { core.followUp() } ?: false

    fun close() {
        action { core.close() }
    }
}
