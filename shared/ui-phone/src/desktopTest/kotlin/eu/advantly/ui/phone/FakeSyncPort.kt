package eu.advantly.ui.phone

import eu.advantly.core.model.EventId
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.EndReason
import eu.advantly.core.scoring.NextSetKind
import eu.advantly.core.scoring.ServeOutcome
import eu.advantly.presentation.match.LiveMatchPort
import eu.advantly.presentation.match.LiveMatchSnapshot
import eu.advantly.presentation.match.MatchSetup
import eu.advantly.presentation.match.StartOptions
import eu.advantly.presentation.match.SyncInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Test-Fake: delegiert das Zählen an einen echten [LiveMatchPort] (In-Memory-Engine) und legt Sync-Status,
 * absorbierte Taps und abgelehnte Eingaben darüber – so, wie sie die Session liefert. Mehrgeräte-Abläufe (Anzeige,
 * Übernahme) testen `SingleScorerUiTest` und `SessionRig` mit der echten Session.
 */
class FakeSyncPort(
    private val base: LiveMatchPort,
) : LiveMatchPort by base {
    var sync: SyncInfo = SyncInfo.LOCAL_ONLY
    var absorbedTaps: Int = 0
    var rejectedByRole: Int = 0

    /** Nein-Antworten erzwingen (z. B. Engine lehnt `MatchEnded` ab). */
    var rejectEndMatch = false

    private val flow = MutableStateFlow<LiveMatchSnapshot?>(null)
    override val active: StateFlow<LiveMatchSnapshot?> get() = flow

    fun refresh() {
        flow.value =
            base.active.value?.let {
                it.copy(
                    sync = sync,
                    absorbedTaps = absorbedTaps,
                    rejectedByRoleCount = rejectedByRole,
                    revision = it.revision + revisionBump,
                )
            }
    }

    private var revisionBump = 0L

    /** Ändert die Fake-Daten wie ein eingehendes Sync-Update (neue Revision). */
    fun update(block: FakeSyncPort.() -> Unit) {
        block()
        revisionBump++
        refresh()
    }

    private inline fun <T> after(result: T): T = result.also { refresh() }

    override fun start(
        setup: MatchSetup,
        options: StartOptions,
    ): MatchId = after(base.start(setup, options))

    override fun recordPoint(side: Side): Boolean = after(base.recordPoint(side))

    override fun removeLastPointOf(side: Side): Boolean = after(base.removeLastPointOf(side))

    override fun setServeInfo(
        pointId: EventId,
        value: ServeOutcome?,
    ): Boolean = after(base.setServeInfo(pointId, value))

    override fun settleServeInfo(pointId: EventId): Boolean = after(base.settleServeInfo(pointId))

    override fun chooseServer(player: PlayerId): Boolean = after(base.chooseServer(player))

    override fun startNextSet(kind: NextSetKind): Boolean = after(base.startNextSet(kind))

    override fun undo(): Boolean = after(base.undo())

    override fun redo(): Boolean = after(base.redo())

    override fun continueDecision(
        anotherSet: Boolean,
        matchTiebreak: Boolean,
    ): Boolean = after(base.continueDecision(anotherSet, matchTiebreak))

    override fun endMatch(
        reason: EndReason,
        side: Side?,
    ): Boolean = if (rejectEndMatch) false else after(base.endMatch(reason, side))

    override fun correctServer(
        player: PlayerId,
        retroactive: Boolean,
    ): Boolean = after(base.correctServer(player, retroactive))

    override fun pause(): Boolean = after(base.pause())

    override fun resume(): Boolean = after(base.resume())

    override fun createFollowUp(): MatchId? = after(base.createFollowUp())

    override fun dismiss(): Boolean = after(base.dismiss())
}
