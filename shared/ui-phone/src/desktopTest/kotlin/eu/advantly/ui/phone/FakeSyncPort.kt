package eu.advantly.ui.phone

import eu.advantly.core.model.MatchId
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.EndReason
import eu.advantly.presentation.match.ConflictInfo
import eu.advantly.presentation.match.ForkChoice
import eu.advantly.presentation.match.ForkInfo
import eu.advantly.presentation.match.LiveMatchPort
import eu.advantly.presentation.match.LiveMatchSnapshot
import eu.advantly.presentation.match.MatchSetup
import eu.advantly.presentation.match.SyncInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Test-Fake: delegiert das Zählen an einen echten [LiveMatchPort] (In-Memory-Engine) und legt Sync-Status,
 * Widersprüche, absorbierte Taps und Verzweigungen darüber – so, wie sie später die Session liefert.
 */
class FakeSyncPort(
    private val base: LiveMatchPort,
) : LiveMatchPort by base {
    var sync: SyncInfo = SyncInfo.LOCAL_ONLY
    var conflicts: List<ConflictInfo> = emptyList()
    var absorbedTaps: Int = 0
    var forks: List<ForkInfo> = emptyList()

    /** Nein-Antworten erzwingen (z. B. Engine lehnt `MatchEnded` ab). */
    var rejectEndMatch = false

    val resolved = mutableListOf<Pair<String, ForkChoice>>()
    val undoneConflictSides = mutableListOf<Side>()

    private val flow = MutableStateFlow<LiveMatchSnapshot?>(null)
    override val active: StateFlow<LiveMatchSnapshot?> get() = flow

    fun refresh() {
        flow.value =
            base.active.value?.let {
                it.copy(
                    sync = sync,
                    conflicts = conflicts,
                    absorbedTaps = absorbedTaps,
                    openForks = forks,
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

    override fun start(setup: MatchSetup): MatchId = after(base.start(setup))

    override fun recordPoint(side: Side): Boolean = after(base.recordPoint(side))

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

    override fun resolveFork(
        forkId: String,
        keep: ForkChoice,
    ): Boolean {
        if (forks.none { it.forkId == forkId }) return false
        resolved += forkId to keep
        update { forks = forks.filterNot { it.forkId == forkId } }
        return true
    }

    override fun dismissConflict(conflictId: String): Boolean {
        if (conflicts.none { it.id == conflictId }) return false
        update { conflicts = conflicts.filterNot { it.id == conflictId } }
        return true
    }

    override fun undoConflictPoint(
        conflictId: String,
        side: Side,
    ): Boolean {
        if (conflicts.none { it.id == conflictId }) return false
        undoneConflictSides += side
        base.undo()
        update { conflicts = conflicts.filterNot { it.id == conflictId } }
        return true
    }
}
