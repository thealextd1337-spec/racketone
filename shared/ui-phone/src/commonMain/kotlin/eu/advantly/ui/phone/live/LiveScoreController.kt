package eu.advantly.ui.phone.live

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import eu.advantly.core.model.EventId
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.EndReason
import eu.advantly.core.scoring.MatchState
import eu.advantly.core.scoring.NextSetKind
import eu.advantly.core.scoring.ServeOutcome
import eu.advantly.match.session.ServerChoiceContext
import eu.advantly.match.session.ServerChoicePhase
import eu.advantly.presentation.live.HapticCue
import eu.advantly.presentation.live.LiveOperationRules
import eu.advantly.presentation.live.LiveScoreRules
import eu.advantly.presentation.live.LiveSettings
import eu.advantly.presentation.match.LiveMatchPort
import eu.advantly.presentation.match.LiveMatchSnapshot
import eu.advantly.presentation.match.MonotonicClock
import eu.advantly.presentation.match.WallClock
import eu.advantly.ui.phone.latency.InputClockSource
import eu.advantly.ui.phone.latency.LatencySummary
import eu.advantly.ui.phone.latency.LatencyTracker
import eu.advantly.ui.phone.platform.FrameStats
import eu.advantly.ui.phone.platform.Haptics
import eu.advantly.ui.phone.platform.inputEventAgeMillis
import eu.advantly.ui.phone.platform.monotonicNanos
import kotlinx.coroutines.delay

/** Vorher→Nachher für die Undo-Snackbar (04 §5.6). */
data class UndoNotice(
    val id: Int,
    val before: MatchState,
    val after: MatchState,
    val pointRemoved: Boolean = false,
)

/** Richtung der Ziffern-Animation: vorwärts (Punkt) oder rückwärts (Undo). */
enum class ScoreDirection { Forward, Backward }

/**
 * UI-State-Holder des Live-Screens über dem [LiveMatchPort]: spiegelt den Snapshot synchron in Compose-State
 * (Haptik und Animation im selben Frame wie die Eingabe), zählt Taps für das Latenz-Overlay und hält die
 * Live-Einstellungen. Änderungen von außen (andere Geräte, z. B. die zählende Uhr) kommen über [sync].
 * Nur vom UI-Thread benutzen.
 */
@Stable
class LiveScoreController(
    val port: LiveMatchPort,
    private val settings: LiveSettings = LiveSettings(),
    val tracker: LatencyTracker = LatencyTracker(),
    /** Uhr für die Matchdauer (gleiche Basis wie `startedAtMs` des Ports). */
    val clock: WallClock = WallClock.System,
    /** Monotone Uhr für „Stand vor X s“ (gleiche Basis wie `SyncInfo.lastScorerContactMono` des Ports). */
    val monotonic: MonotonicClock = MonotonicClock.System,
) {
    var snapshot: LiveMatchSnapshot? by mutableStateOf(port.active.value)
        private set

    /** Wird bei jeder Zustandsänderung erhöht (Roll-Animation, Flash, Latenzmessung). */
    var generation by mutableIntStateOf(0)
        private set
    var lastDirection by mutableStateOf(ScoreDirection.Forward)
        private set

    /** Side, deren Fläche zuletzt aufblitzen soll. */
    var flashSide by mutableStateOf<Side?>(null)
        private set
    var undoNotice by mutableStateOf<UndoNotice?>(null)
        private set

    /** ID des Hinweises „Doppelte Eingabe zusammengeführt“; steigt, sobald `absorbedTaps` wächst. */
    var absorbedNotice by mutableStateOf<Int?>(null)
        private set

    /**
     * Bereits bestätigte Zahl ignorierter Eingaben (02 §7.5); der Hinweis „N Eingaben nicht übernommen“ erscheint,
     * solange `rejectedByRoleCount` darüber liegt.
     */
    var acknowledgedRejected by mutableIntStateOf(0)
        private set

    /** Zeigt der Live-Screen den Hinweis „N Eingaben nicht übernommen“? */
    val rejectedNotice: Int?
        get() = snapshot?.rejectedByRoleCount?.takeIf { it > acknowledgedRejected }

    private var outdoorState by mutableStateOf(settings.outdoorContrast)
    private var keepScreenOnState by mutableStateOf(settings.keepScreenOn)
    private var overlayState by mutableStateOf(settings.showLatencyOverlay)
    private var debounceState by mutableStateOf(settings.debounceEnabled)

    var outdoorContrast: Boolean
        get() = outdoorState
        set(value) {
            outdoorState = value
            settings.outdoorContrast = value
        }
    var keepScreenOn: Boolean
        get() = keepScreenOnState
        set(value) {
            keepScreenOnState = value
            settings.keepScreenOn = value
        }
    var showLatencyOverlay: Boolean
        get() = overlayState
        set(value) {
            overlayState = value
            settings.showLatencyOverlay = value
        }
    var debounceEnabled: Boolean
        get() = debounceState
        set(value) {
            debounceState = value
            settings.debounceEnabled = value
        }

    var benchmarkRunning by mutableStateOf(false)
        private set

    var rawTaps by mutableIntStateOf(0)
        private set
    var countedPoints by mutableIntStateOf(0)
        private set
    var debouncedTaps by mutableIntStateOf(0)
        private set

    /** Wird vom Screen gesetzt ([eu.advantly.ui.phone.platform.rememberHaptics]). */
    var haptics: Haptics = Haptics.None

    /** Wird vom Screen gesetzt ([eu.advantly.ui.phone.platform.rememberFrameStats]); setzt sich mit den Messwerten zurück. */
    var frameStats: FrameStats? = null

    /** Fensterkoordinaten (px) der Tap-Flächen – nur für den Desktop-Benchmark (AWT-Klicks). */
    val panelBounds = mutableMapOf<Side, Rect>()

    var serverChoicePresentation: ServerChoiceContext? by mutableStateOf(null)
        private set
    private var choiceInteractive = false
    private val handledChoices = mutableSetOf<Triple<MatchId, Int, ServerChoicePhase>>()

    private fun ServerChoiceContext.window() = Triple(matchId, setIndex, phase)

    fun setChoiceInteractive(active: Boolean) {
        choiceInteractive = active
        updateServerChoice(snapshot, snapshot)
    }

    fun openServerChoice() {
        val current = snapshot ?: return
        if (current.isScorer && choiceInteractive) serverChoicePresentation = current.serverChoiceContext
    }

    fun dismissServerChoice() {
        serverChoicePresentation?.let { handledChoices += it.window() }
        serverChoicePresentation = null
    }

    private fun updateServerChoice(
        previous: LiveMatchSnapshot?,
        next: LiveMatchSnapshot?,
    ) {
        val context = next?.serverChoiceContext
        val old = previous?.serverChoiceContext
        if (context != null) {
            val reopened = previous?.matchId == next.matchId && next.pointsPlayed < previous.pointsPlayed
            val cleared = old?.window() == context.window() && old?.chosen != null && context.chosen == null
            if (reopened || cleared) handledChoices -= context.window()
        }
        if (next?.isScorer != true || context == null) {
            serverChoicePresentation = null
            return
        }
        if (serverChoicePresentation != null && serverChoicePresentation != context) serverChoicePresentation = null
        val needsReview = context.chosen == null || (context.phase == ServerChoicePhase.FIRST && next.canRedo)
        if (choiceInteractive && needsReview && context.window() !in handledChoices) serverChoicePresentation = context
    }

    var servePromptId: EventId? by mutableStateOf(null)
        private set

    /** Anzeigezeit der Leiste vorbei: ohne Eingabe erfasst Modus „Ja“ „1. Aufschlag“ (wie auf der Uhr). */
    fun dismissServePrompt(id: EventId) {
        if (servePromptId != id) return
        servePromptId = null
        val current = snapshot ?: return
        if (LiveOperationRules.autoServe(current) == null) return
        // still (ohne Haptik); „nie überschreiben“ prüft der Port an einer Stelle (settleServeInfo)
        if (current.lastPoint?.id == id && port.settleServeInfo(id)) {
            updateSnapshot(port.active.value)
            generation++
        }
    }

    fun removeLastPointOf(side: Side): Boolean {
        if (serverChoicePresentation != null) return false
        val before = snapshot
        if (before == null || !LiveOperationRules.canRemove(before, side) || !port.removeLastPointOf(side)) {
            haptics.perform(HapticCue.Rejected)
            return false
        }
        haptics.perform(HapticCue.Undo)
        val after = port.active.value ?: return false
        servePromptId = null
        apply(after, ScoreDirection.Backward, null, monotonicNanos(), InputClockSource.Handler)
        undoNotice = UndoNotice(++noticeIds, before.state, after.state, pointRemoved = true)
        return true
    }

    fun chooseServer(player: PlayerId): Boolean = snapshot?.serverChoiceContext?.let { chooseServer(player, it) } ?: false

    fun chooseServer(
        player: PlayerId,
        expected: ServerChoiceContext,
    ): Boolean {
        val current = snapshot?.serverChoiceContext ?: return false
        if (snapshot?.isScorer != true || !current.accepts(expected, player)) return false
        handledChoices += expected.window()
        serverChoicePresentation = null
        val chosen = metadataCommand { port.chooseServer(player, expected) }
        if (!chosen) sync(port.active.value)
        return chosen
    }

    fun setServeInfo(
        pointId: EventId,
        value: ServeOutcome?,
    ): Boolean {
        val current = snapshot ?: return false
        if (current.lastPoint?.id != pointId || LiveOperationRules.serveOptions(current).isEmpty()) return false
        if (value != null && value !in LiveOperationRules.serveOptions(current) &&
            value != LiveOperationRules.autoServe(current)
        ) {
            return false
        }
        return metadataCommand { port.setServeInfo(pointId, value) }
    }

    fun startNextSet(kind: NextSetKind): Boolean {
        val current = snapshot ?: return false
        if (!current.isScorer || kind !in current.nextSetOptions) return false
        val started = command { port.startNextSet(kind) }
        if (started) servePromptId = null
        return started
    }

    private var lastPointAtNanos = Long.MIN_VALUE

    /** Match des letzten Punktes: die Entprellung gilt je Match (Review W3-M #6, schneller Wechsel zwischen Plätzen). */
    private var lastPointMatch: MatchId? = null
    private var noticeIds = 0

    /** Übernimmt einen Snapshot, der nicht durch ein eigenes Kommando entstand (Flow des Ports). */
    fun sync(next: LiveMatchSnapshot?) {
        val current = snapshot
        if (next?.matchId == current?.matchId && next?.revision == current?.revision) return
        if (next?.matchId != current?.matchId) {
            undoNotice = null
            flashSide = null
            acknowledgedRejected = 0
        }
        // Rollenwechsel: kein Undo/Redo-Angebot aus der alten Rolle stehen lassen
        if (next?.role != current?.role) undoNotice = null
        updateSnapshot(next)
        generation++
    }

    /** Übernimmt [next] und meldet neu zusammengeführte Doppel-Taps (01 §4.4). */
    private fun updateSnapshot(next: LiveMatchSnapshot?) {
        val previous = snapshot
        if (next != null && previous != null && next.matchId == previous.matchId && next.absorbedTaps > previous.absorbedTaps) {
            absorbedNotice = ++noticeIds
        }
        if (next?.matchId != previous?.matchId || next?.isScorer != true || next.lastPoint?.id != servePromptId) {
            servePromptId = null
        }
        snapshot = next
        updateServerChoice(previous, next)
    }

    fun dismissAbsorbedNotice(id: Int) {
        if (absorbedNotice == id) absorbedNotice = null
    }

    /** Hinweis „N Eingaben nicht übernommen“ schließen (erscheint erst bei weiteren wieder). */
    fun acknowledgeRejected() {
        acknowledgedRejected = snapshot?.rejectedByRoleCount ?: 0
    }

    /** „Zählen übernehmen“ (nach Bestätigung im Dialog): dieses Gerät wird Zähler. */
    fun takeOverScoring(): Boolean = command { port.takeOverScoring() }

    /** Tap auf eine Fläche. [eventUptimeMillis] = `PointerInputChange.uptimeMillis` (null bei Bedienhilfe-Klick). */
    fun onTap(
        side: Side,
        eventUptimeMillis: Long?,
    ) {
        val (t0, source) = inputTime(eventUptimeMillis)
        score(side, t0, source)
    }

    private fun inputTime(eventUptimeMillis: Long?): Pair<Long, InputClockSource> {
        val now = monotonicNanos()
        val age = eventUptimeMillis?.let(::inputEventAgeMillis) ?: return now to InputClockSource.Handler
        return (now - age * 1_000_000) to InputClockSource.InputEvent
    }

    private fun score(
        side: Side,
        t0: Long,
        source: InputClockSource,
    ) {
        if (serverChoicePresentation != null) return
        rawTaps++
        val match = port.active.value?.matchId
        val sameMatch = match != null && match == lastPointMatch
        if (debounceEnabled && sameMatch && lastPointAtNanos != Long.MIN_VALUE && t0 - lastPointAtNanos < DEBOUNCE_NANOS) {
            debouncedTaps++
            haptics.perform(HapticCue.Rejected)
            return
        }
        if (!port.recordPoint(side)) {
            haptics.perform(HapticCue.Rejected)
            return
        }
        lastPointAtNanos = t0
        lastPointMatch = match
        countedPoints++
        undoNotice = null
        val after = port.active.value
        after?.let { haptics.perform(LiveScoreRules.cueFor(it.state, side)) }
        apply(after, ScoreDirection.Forward, side, t0, source)
        servePromptId =
            after?.lastPoint?.id?.takeIf {
                source != InputClockSource.Synthetic && LiveOperationRules.serveOptions(after).isNotEmpty()
            }
    }

    fun undo(eventUptimeMillis: Long? = null) {
        if (serverChoicePresentation != null) return
        servePromptId = null
        val (t0, source) = inputTime(eventUptimeMillis)
        val before = snapshot?.state
        if (!port.undo()) {
            haptics.perform(HapticCue.Rejected)
            return
        }
        haptics.perform(HapticCue.Undo)
        val after = port.active.value
        apply(after, ScoreDirection.Backward, null, t0, source)
        if (before != null && after != null) undoNotice = UndoNotice(++noticeIds, before, after.state)
    }

    fun redo() {
        if (serverChoicePresentation != null) return
        if (!port.redo()) return
        undoNotice = null
        apply(port.active.value, ScoreDirection.Forward, null, monotonicNanos(), InputClockSource.Handler)
    }

    fun dismissUndoNotice(id: Int) {
        if (undoNotice?.id == id) undoNotice = null
    }

    /** Antwort auf „Noch ein Satz?“. */
    fun continueDecision(
        anotherSet: Boolean,
        matchTiebreak: Boolean = false,
    ) = command { port.continueDecision(anotherSet, matchTiebreak) }

    /** Vorzeitiges Matchende (bestätigt im Dialog). */
    fun endMatch(
        reason: EndReason,
        side: Side?,
    ) = command { port.endMatch(reason, side) }

    /** „Beenden – Ergebnis gilt“ (F21, bestätigt im Dialog); als Anzeige übernimmt das Handy vorher. */
    fun stopMatch(): Boolean = command { port.stopMatch() }

    /** „Abbrechen (ohne Wertung)“ (F21, bestätigt im Dialog); als Anzeige übernimmt das Handy vorher. */
    fun abandonMatch(): Boolean = command { port.abandonMatch() }

    fun correctServer(
        player: PlayerId,
        retroactive: Boolean,
    ) = command { port.correctServer(player, retroactive) }

    fun pause() = command { port.pause() }

    fun resume() = command { port.resume() }

    /** Folge-Match in gleicher Besetzung; `true`, wenn es angelegt wurde. */
    fun createFollowUp(): Boolean = command { port.createFollowUp() != null }

    /** Metadaten lassen Undo/Redo und die Messung der Zähleingaben unberührt. */
    private inline fun metadataCommand(block: () -> Boolean): Boolean {
        val ok = block()
        if (ok) {
            updateSnapshot(port.active.value)
            flashSide = null
            generation++
        } else {
            haptics.perform(HapticCue.Rejected)
        }
        return ok
    }

    private inline fun command(block: () -> Boolean): Boolean {
        val ok = block()
        if (ok) {
            undoNotice = null
            apply(port.active.value, ScoreDirection.Forward, null, monotonicNanos(), InputClockSource.Handler)
        } else {
            haptics.perform(HapticCue.Rejected)
        }
        return ok
    }

    /** Setzt Latenz-Messwerte, Tap-Zähler und Frame-Statistik gemeinsam zurück (gleiches Messfenster). */
    fun resetMeasurements() {
        tracker.reset()
        frameStats?.reset()
        rawTaps = 0
        countedPoints = 0
        debouncedTaps = 0
    }

    /**
     * Auto-Benchmark: [points] Punkte im Abstand von [intervalMillis] ohne echte Eingabe (abwechselnd Wir/Gegner;
     * beim Tennis endet das nie). [newMatch] legt vorher ein frisches Match an. Misst Zustand → nächster
     * Frame-Beginn, z. B. im iOS-Simulator ohne Touch-Injektion.
     */
    suspend fun runAutoBenchmark(
        newMatch: () -> Boolean,
        points: Int = 40,
        intervalMillis: Long = 250,
    ): LatencySummary {
        // Kein frisches Match (z. B. echtes laufendes Match): nichts zählen, leere Messung
        if (!newMatch()) return tracker.summary(InputClockSource.Synthetic)
        benchmarkRunning = true
        try {
            sync(port.active.value)
            resetMeasurements()
            delay(intervalMillis * 2)
            repeat(points) { i ->
                score(if (i % 2 == 0) Side.A else Side.B, monotonicNanos(), InputClockSource.Synthetic)
                delay(intervalMillis)
            }
            delay(intervalMillis)
            return tracker.summary(InputClockSource.Synthetic)
        } finally {
            benchmarkRunning = false
        }
    }

    private fun apply(
        next: LiveMatchSnapshot?,
        direction: ScoreDirection,
        side: Side?,
        t0: Long,
        source: InputClockSource,
    ) {
        updateSnapshot(next)
        lastDirection = direction
        flashSide = side
        generation++
        tracker.onInput(generation, t0, source)
    }

    private companion object {
        const val DEBOUNCE_NANOS = 600L * 1_000_000
    }
}
