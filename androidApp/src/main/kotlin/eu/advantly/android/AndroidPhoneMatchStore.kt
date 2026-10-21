package eu.advantly.android

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import eu.advantly.core.model.EventId
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.model.TelemetryVisibility
import eu.advantly.core.scoring.EndReason
import eu.advantly.core.scoring.NextSetKind
import eu.advantly.core.scoring.ServeOutcome
import eu.advantly.device.link.CompanionDetector
import eu.advantly.device.link.CompanionState
import eu.advantly.match.session.AndroidSessionBoundary
import eu.advantly.match.session.MatchSession
import eu.advantly.match.session.ServerChoiceContext
import eu.advantly.presentation.match.MatchSetup
import eu.advantly.presentation.match.MatchStore
import eu.advantly.presentation.match.SessionMatchStore
import eu.advantly.presentation.match.StartOptions
import eu.advantly.ui.phone.app.AdvantlyAppModel

/** Typed Phone-Ports: jede speichernde/lesende Methode umfasst den echten Aufruf; keine Touch-/Coroutine-Tricks. */
internal class AndroidPhoneMatchStore private constructor(
    private val raw: SessionMatchStore,
    private val calls: AndroidSessionBoundary,
) : MatchStore by raw,
    AutoCloseable {
    override fun tryStart(
        setup: MatchSetup,
        options: StartOptions,
    ) = calls.run { raw.start(setup, options) }

    override fun tryAddGuest(name: String) = calls.run { raw.addGuest(name) }

    override fun trySaveFavorite(setup: MatchSetup) = calls.run { raw.saveFavorite(setup) }

    // Nonnull-Kompatibilitaetsmethoden sind keine Android-UI-Grenze. Falsche Verwendung scheitert VOR SQL.
    override fun start(
        setup: MatchSetup,
        options: StartOptions,
    ): MatchId = error("Android UI must use tryStart")

    override fun addGuest(name: String): eu.advantly.core.model.Player = error("Android UI must use tryAddGuest")

    override fun saveFavorite(setup: MatchSetup): eu.advantly.presentation.match.FavoriteLineup =
        error("Android UI must use trySaveFavorite")

    override fun refreshWatchStatus() {
        calls.run { raw.refreshWatchStatus() }
    }

    override fun setTelemetryVisibility(visibility: TelemetryVisibility) {
        calls.run { raw.setTelemetryVisibility(visibility) }
    }

    override fun watchTakeOverNeeded() = calls.run { raw.watchTakeOverNeeded() }

    override fun recordPoint(side: Side) = calls.run { raw.recordPoint(side) } ?: false

    override fun undo() = calls.run { raw.undo() } ?: false

    override fun redo() = calls.run { raw.redo() } ?: false

    override fun continueDecision(
        anotherSet: Boolean,
        matchTiebreak: Boolean,
    ) = calls.run { raw.continueDecision(anotherSet, matchTiebreak) } ?: false

    override fun startNextSet(kind: NextSetKind) = calls.run { raw.startNextSet(kind) } ?: false

    override fun removeLastPointOf(side: Side) = calls.run { raw.removeLastPointOf(side) } ?: false

    override fun chooseServer(player: PlayerId) = calls.run { raw.chooseServer(player) } ?: false

    override fun chooseServer(
        player: PlayerId,
        expected: ServerChoiceContext,
    ) = calls.run { raw.chooseServer(player, expected) } ?: false

    override fun setServeInfo(
        pointId: EventId,
        value: ServeOutcome?,
    ) = calls.run { raw.setServeInfo(pointId, value) } ?: false

    override fun select(matchId: MatchId) = calls.run { raw.select(matchId) } ?: false

    override fun endMatch(
        reason: EndReason,
        side: Side?,
    ) = calls.run { raw.endMatch(reason, side) } ?: false

    override fun stopMatch() = calls.run { raw.stopMatch() } ?: false

    override fun abandonMatch() = calls.run { raw.abandonMatch() } ?: false

    override fun correctServer(
        player: PlayerId,
        retroactive: Boolean,
    ) = calls.run { raw.correctServer(player, retroactive) } ?: false

    override fun pause() = calls.run { raw.pause() } ?: false

    override fun resume() = calls.run { raw.resume() } ?: false

    override fun createFollowUp() = calls.run { raw.createFollowUp() }

    override fun dismiss() = calls.run { raw.dismiss() } ?: false

    override fun takeOverScoring() = calls.run { raw.takeOverScoring() } ?: false

    override fun rejectedInputs() = calls.run { raw.rejectedInputs() }.orEmpty()

    override fun canAssignPlayer(matchId: MatchId) = calls.run { raw.canAssignPlayer(matchId) } ?: false

    override fun assignmentFailure(
        matchId: MatchId,
        slot: PlayerId,
        player: PlayerId?,
    ) = calls.run {
        raw.assignmentFailure(matchId, slot, player)
    }

    override fun assignPlayer(
        matchId: MatchId,
        slot: PlayerId,
        player: PlayerId?,
    ) = calls.run { raw.assignPlayer(matchId, slot, player) } ?: false

    override fun record(matchId: MatchId) = calls.run { raw.record(matchId) }

    override fun delete(matchId: MatchId) = calls.run { raw.delete(matchId) } ?: false

    override fun recentPlayers(sport: Sport) = calls.run { raw.recentPlayers(sport) }.orEmpty()

    override fun removeFavorite(id: String) {
        calls.run { raw.removeFavorite(id) }
    }

    override fun canRename(id: PlayerId) = calls.run { raw.canRename(id) } ?: false

    override fun deleteGuest(id: PlayerId) = calls.run { raw.deleteGuest(id) } ?: false

    override fun renameGuest(
        id: PlayerId,
        name: String,
    ) = calls.run { raw.renameGuest(id, name) } ?: false

    override fun observeOwnerIdentity(onChanged: () -> Unit): () -> Unit =
        calls.run { raw.observeOwnerIdentity { calls.run(onChanged) } } ?: {}

    override fun close() {
        try {
            raw.close()
        } finally {
            calls.close()
        }
    }

    companion object {
        fun create(
            context: Context,
            session: MatchSession,
            companion: CompanionDetector? = null,
        ): AndroidPhoneMatchStore {
            val calls = AndroidSessionBoundary.forSession(context, session)
            val guardedCompanion =
                companion?.let { delegate ->
                    object : CompanionDetector by delegate {
                        override fun start(listener: (CompanionState) -> Unit) = delegate.start { state -> calls.run { listener(state) } }
                    }
                }
            try {
                return AndroidPhoneMatchStore(SessionMatchStore(session, companion = guardedCompanion), calls)
            } catch (error: Exception) {
                try {
                    guardedCompanion?.stop()
                } catch (cleanup: Exception) {
                    if (cleanup !== error) error.addSuppressed(cleanup)
                }
                calls.close()
                throw error
            }
        }
    }
}

/** Android besitzt seinen Portdecorator selbst; das Common-ViewModel muss keinen konkreten Store casten. */
internal class AndroidPhoneAppViewModel(
    private val store: AndroidPhoneMatchStore,
    savedState: SavedStateHandle,
) : ViewModel() {
    val model =
        AdvantlyAppModel(
            store,
            store,
            store,
            settings =
                eu.advantly.presentation.live
                    .LiveSettings(savedState),
            telemetry = store,
        )

    override fun onCleared() {
        try {
            model.wizard.close()
        } finally {
            store.close()
        }
    }
}
