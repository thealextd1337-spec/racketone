package eu.advantly.ui.phone.live

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.advantly.presentation.live.DemoScenario
import eu.advantly.presentation.live.FakeTennisState
import eu.advantly.presentation.live.HapticCue
import eu.advantly.presentation.live.LiveScoreViewModel
import eu.advantly.presentation.live.ScoreChange
import eu.advantly.presentation.live.Team
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
    val before: FakeTennisState,
    val after: FakeTennisState,
)

/**
 * Controller zum [LiveScoreViewModel] des aktuellen ViewModelStoreOwners (Activity, ComposeUIViewController,
 * Desktop-Fenster). Der Zustand übersteht damit Rotation (ViewModel) und Prozesstod (SavedStateHandle).
 */
@Composable
fun rememberLiveScoreController(): LiveScoreController {
    val viewModel =
        viewModel {
            // Bietet ein Host keine SavedState-Unterstützung, bleibt es beim ViewModel ohne Prozesstod-Schutz.
            LiveScoreViewModel(runCatching { createSavedStateHandle() }.getOrElse { SavedStateHandle() })
        }
    return remember(viewModel) { LiveScoreController(viewModel) }
}

/**
 * UI-State-Holder des Live-Score-Screens (Spike S4) über dem [LiveScoreViewModel]: Compose-State für Zustand und
 * Einstellungen, Zähler für verlorene/entprellte Taps und den [LatencyTracker]. Nur vom UI-Thread benutzen.
 */
@Stable
class LiveScoreController(
    private val viewModel: LiveScoreViewModel = LiveScoreViewModel(),
    val tracker: LatencyTracker = LatencyTracker(),
) {
    var state: FakeTennisState by mutableStateOf(viewModel.state)
        private set
    var canUndo by mutableStateOf(viewModel.canUndo)
        private set

    /** Wird bei jeder Zustandsänderung erhöht (Roll-Animation, Flash, Latenzmessung). */
    var generation by mutableIntStateOf(0)
        private set
    var lastDirection by mutableStateOf(ScoreChange.Direction.Forward)
        private set

    /** Team, dessen Fläche zuletzt aufblitzen soll. */
    var flashTeam by mutableStateOf<Team?>(null)
        private set
    var undoNotice by mutableStateOf<UndoNotice?>(null)
        private set

    private var outdoorState by mutableStateOf(viewModel.outdoorContrast)
    private var keepScreenOnState by mutableStateOf(viewModel.keepScreenOn)
    private var overlayState by mutableStateOf(viewModel.showLatencyOverlay)
    private var debounceState by mutableStateOf(viewModel.debounceEnabled)

    var outdoorContrast: Boolean
        get() = outdoorState
        set(value) {
            outdoorState = value
            viewModel.outdoorContrast = value
        }
    var keepScreenOn: Boolean
        get() = keepScreenOnState
        set(value) {
            keepScreenOnState = value
            viewModel.keepScreenOn = value
        }
    var showLatencyOverlay: Boolean
        get() = overlayState
        set(value) {
            overlayState = value
            viewModel.showLatencyOverlay = value
        }
    var debounceEnabled: Boolean
        get() = debounceState
        set(value) {
            debounceState = value
            viewModel.debounceEnabled = value
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
    val panelBounds = mutableMapOf<Team, Rect>()

    private var lastPointAtNanos = Long.MIN_VALUE
    private var noticeIds = 0

    /** Tap auf eine Fläche. [eventUptimeMillis] = `PointerInputChange.uptimeMillis` (null bei Bedienhilfe-Klick). */
    fun onTap(
        team: Team,
        eventUptimeMillis: Long?,
    ) {
        val (t0, source) = inputTime(eventUptimeMillis)
        score(team, t0, source)
    }

    private fun inputTime(eventUptimeMillis: Long?): Pair<Long, InputClockSource> {
        val now = monotonicNanos()
        val age = eventUptimeMillis?.let(::inputEventAgeMillis) ?: return now to InputClockSource.Handler
        return (now - age * 1_000_000) to InputClockSource.InputEvent
    }

    private fun score(
        team: Team,
        t0: Long,
        source: InputClockSource,
    ) {
        rawTaps++
        if (debounceEnabled && lastPointAtNanos != Long.MIN_VALUE && t0 - lastPointAtNanos < DEBOUNCE_NANOS) {
            debouncedTaps++
            haptics.perform(HapticCue.Rejected)
            return
        }
        val change = viewModel.score(team)
        if (change == null) {
            haptics.perform(HapticCue.Rejected)
            return
        }
        lastPointAtNanos = t0
        countedPoints++
        undoNotice = null
        apply(change, t0, source)
    }

    fun undo(eventUptimeMillis: Long? = null) {
        val (t0, source) = inputTime(eventUptimeMillis)
        val change = viewModel.undo()
        if (change == null) {
            haptics.perform(HapticCue.Rejected)
            return
        }
        apply(change, t0, source)
        undoNotice = UndoNotice(++noticeIds, change.before, change.after)
    }

    fun redo() {
        val change = viewModel.redo() ?: return
        undoNotice = null
        apply(change, monotonicNanos(), InputClockSource.Handler)
    }

    fun dismissUndoNotice(id: Int) {
        if (undoNotice?.id == id) undoNotice = null
    }

    fun demo(scenario: DemoScenario) {
        undoNotice = null
        apply(viewModel.demo(scenario), monotonicNanos(), InputClockSource.Handler)
    }

    fun newMatch() {
        viewModel.reset()
        undoNotice = null
        lastPointAtNanos = Long.MIN_VALUE
        state = viewModel.state
        canUndo = false
        flashTeam = null
        generation++
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
     * Auto-Benchmark: [points] Punkte im Abstand von [intervalMillis] ohne echte Eingabe (abwechselnd Wir/Gegner,
     * endet also nie im Matchende). Misst Zustand → nächster Frame-Beginn, z. B. im iOS-Simulator ohne Touch-Injektion.
     */
    suspend fun runAutoBenchmark(
        points: Int = 40,
        intervalMillis: Long = 250,
    ): LatencySummary {
        benchmarkRunning = true
        try {
            newMatch()
            resetMeasurements()
            delay(intervalMillis * 2)
            repeat(points) { i ->
                score(if (i % 2 == 0) Team.Us else Team.Them, monotonicNanos(), InputClockSource.Synthetic)
                delay(intervalMillis)
            }
            delay(intervalMillis)
            return tracker.summary(InputClockSource.Synthetic)
        } finally {
            benchmarkRunning = false
        }
    }

    private fun apply(
        change: ScoreChange,
        t0: Long,
        source: InputClockSource,
    ) {
        // Haptik zuerst – sie soll nicht auf den Frame warten.
        change.cue?.let(haptics::perform)
        state = change.after
        canUndo = viewModel.canUndo
        lastDirection = change.direction
        flashTeam = change.team
        generation++
        tracker.onInput(generation, t0, source)
    }

    private companion object {
        const val DEBOUNCE_NANOS = 600L * 1_000_000
    }
}
