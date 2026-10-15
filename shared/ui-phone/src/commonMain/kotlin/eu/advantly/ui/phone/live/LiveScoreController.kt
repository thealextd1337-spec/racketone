package eu.advantly.ui.phone.live

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.EndReason
import eu.advantly.core.scoring.MatchState
import eu.advantly.presentation.live.HapticCue
import eu.advantly.presentation.live.LiveScoreRules
import eu.advantly.presentation.live.LiveSettings
import eu.advantly.presentation.match.ForkChoice
import eu.advantly.presentation.match.LiveMatchPort
import eu.advantly.presentation.match.LiveMatchSnapshot
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
)

/** Richtung der Ziffern-Animation: vorwärts (Punkt) oder rückwärts (Undo). */
enum class ScoreDirection { Forward, Backward }

/**
 * UI-State-Holder des Live-Screens über dem [LiveMatchPort]: spiegelt den Snapshot synchron in Compose-State
 * (Haptik und Animation im selben Frame wie die Eingabe), zählt Taps für das Latenz-Overlay und hält die
 * Live-Einstellungen. Änderungen von außen (später: andere Geräte) kommen über [sync]. Nur vom UI-Thread benutzen.
 */
@Stable
class LiveScoreController(
    val port: LiveMatchPort,
    private val settings: LiveSettings = LiveSettings(),
    val tracker: LatencyTracker = LatencyTracker(),
    /** Uhr für die Matchdauer (gleiche Basis wie `startedAtMs` des Ports). */
    val clock: WallClock = WallClock.System,
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

    private var lastPointAtNanos = Long.MIN_VALUE
    private var noticeIds = 0

    /** Übernimmt einen Snapshot, der nicht durch ein eigenes Kommando entstand (Flow des Ports). */
    fun sync(next: LiveMatchSnapshot?) {
        val current = snapshot
        if (next?.matchId == current?.matchId && next?.revision == current?.revision) return
        if (next?.matchId != current?.matchId) {
            undoNotice = null
            flashSide = null
        }
        updateSnapshot(next)
        generation++
    }

    /** Übernimmt [next] und meldet neu zusammengeführte Doppel-Taps (01 §4.4). */
    private fun updateSnapshot(next: LiveMatchSnapshot?) {
        val previous = snapshot
        if (next != null && previous != null && next.matchId == previous.matchId && next.absorbedTaps > previous.absorbedTaps) {
            absorbedNotice = ++noticeIds
        }
        snapshot = next
    }

    fun dismissAbsorbedNotice(id: Int) {
        if (absorbedNotice == id) absorbedNotice = null
    }

    /** Verzweigung auflösen (Version A, Version B oder beide). */
    fun resolveFork(
        forkId: String,
        keep: ForkChoice,
    ) = command { port.resolveFork(forkId, keep) }

    /** Widerspruch bestätigen: beide Punkte bleiben. */
    fun dismissConflict(conflictId: String) = command { port.dismissConflict(conflictId) }

    /** 1-Tap-Undo eines Widerspruchs: Punkt für [side] zurücknehmen. */
    fun undoConflictPoint(
        conflictId: String,
        side: Side,
    ) = command { port.undoConflictPoint(conflictId, side) }

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
        rawTaps++
        if (debounceEnabled && lastPointAtNanos != Long.MIN_VALUE && t0 - lastPointAtNanos < DEBOUNCE_NANOS) {
            debouncedTaps++
            haptics.perform(HapticCue.Rejected)
            return
        }
        if (!port.recordPoint(side)) {
            haptics.perform(HapticCue.Rejected)
            return
        }
        lastPointAtNanos = t0
        countedPoints++
        undoNotice = null
        val after = port.active.value
        after?.let { haptics.perform(LiveScoreRules.cueFor(it.state, side)) }
        apply(after, ScoreDirection.Forward, side, t0, source)
    }

    fun undo(eventUptimeMillis: Long? = null) {
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

    fun correctServer(
        player: PlayerId,
        retroactive: Boolean,
    ) = command { port.correctServer(player, retroactive) }

    fun pause() = command { port.pause() }

    fun resume() = command { port.resume() }

    /** Folge-Match in gleicher Besetzung; `true`, wenn es angelegt wurde. */
    fun createFollowUp(): Boolean = command { port.createFollowUp() != null }

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
        newMatch: () -> Unit,
        points: Int = 40,
        intervalMillis: Long = 250,
    ): LatencySummary {
        benchmarkRunning = true
        try {
            newMatch()
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
