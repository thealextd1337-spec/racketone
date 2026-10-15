package eu.advantly.ui.phone.live

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import eu.advantly.presentation.live.DemoScenario
import eu.advantly.presentation.live.FakeTennisState
import eu.advantly.presentation.live.HapticCue
import eu.advantly.presentation.live.LiveScoreSession
import eu.advantly.presentation.live.ScoreChange
import eu.advantly.presentation.live.Team
import eu.advantly.ui.phone.latency.InputClockSource
import eu.advantly.ui.phone.latency.LatencySummary
import eu.advantly.ui.phone.latency.LatencyTracker
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
 * State-Holder des Live-Score-Screens (Spike S4). Hält den Fake-Zustand aus `presentation`, Einstellungen,
 * Zähler für verlorene/entprellte Taps und den [LatencyTracker]. Nur vom UI-Thread benutzen.
 */
@Stable
class LiveScoreController(
    private val session: LiveScoreSession = LiveScoreSession(),
    val tracker: LatencyTracker = LatencyTracker(),
) {
    var state: FakeTennisState by mutableStateOf(session.state)
        private set
    var canUndo by mutableStateOf(false)
        private set

    /** Wird bei jeder Zustandsänderung erhöht (Roll-Animation, Flash, Latenzmessung). */
    var generation by mutableIntStateOf(0)
        private set
    var lastDirection by mutableStateOf(ScoreChange.Direction.Forward)
        private set

    /** Team, dessen Fläche zuletzt aufblitzen soll, und der Zähler dazu. */
    var flashTeam by mutableStateOf<Team?>(null)
        private set
    var undoNotice by mutableStateOf<UndoNotice?>(null)
        private set

    var outdoorContrast by mutableStateOf(false)
    var keepScreenOn by mutableStateOf(true)
    var showLatencyOverlay by mutableStateOf(true)
    var debounceEnabled by mutableStateOf(false)
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

    /** Fensterkoordinaten (px) der Tap-Flächen – nur für den Desktop-Benchmark (AWT-Klicks). */
    val panelBounds = mutableMapOf<Team, Rect>()

    private var lastPointAtNanos = Long.MIN_VALUE
    private var noticeIds = 0

    /** Tap auf eine Fläche. [eventUptimeMillis] = `PointerInputChange.uptimeMillis` (null bei Bedienhilfe-Klick). */
    fun onTap(
        team: Team,
        eventUptimeMillis: Long?,
    ) {
        val now = monotonicNanos()
        val age = eventUptimeMillis?.let(::inputEventAgeMillis)
        val t0 = if (age != null) now - age * 1_000_000 else now
        score(team, t0, if (age != null) InputClockSource.InputEvent else InputClockSource.Handler)
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
        val change = session.score(team)
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
        val now = monotonicNanos()
        val age = eventUptimeMillis?.let(::inputEventAgeMillis)
        val change = session.undo()
        if (change == null) {
            haptics.perform(HapticCue.Rejected)
            return
        }
        apply(
            change,
            if (age != null) now - age * 1_000_000 else now,
            if (age != null) InputClockSource.InputEvent else InputClockSource.Handler,
        )
        undoNotice = UndoNotice(++noticeIds, change.before, change.after)
    }

    fun redo() {
        val change = session.redo() ?: return
        undoNotice = null
        apply(change, monotonicNanos(), InputClockSource.Handler)
    }

    fun dismissUndoNotice(id: Int) {
        if (undoNotice?.id == id) undoNotice = null
    }

    fun demo(scenario: DemoScenario) {
        undoNotice = null
        apply(session.demo(scenario), monotonicNanos(), InputClockSource.Handler)
    }

    fun newMatch() {
        session.reset()
        undoNotice = null
        lastPointAtNanos = Long.MIN_VALUE
        state = session.state
        canUndo = false
        flashTeam = null
        generation++
    }

    fun resetMeasurements() {
        tracker.reset()
        rawTaps = 0
        countedPoints = 0
        debouncedTaps = 0
    }

    /**
     * Auto-Benchmark: [points] Punkte im Abstand von [intervalMillis] ohne echte Eingabe (abwechselnd Wir/Gegner,
     * endet also nie im Matchende). Misst Zustand → Frame, z. B. im iOS-Simulator ohne Touch-Injektion.
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
            return tracker.summary()
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
        canUndo = session.canUndo
        lastDirection = change.direction
        flashTeam = change.team
        generation++
        tracker.onInput(generation, t0, source)
    }

    private companion object {
        const val DEBOUNCE_NANOS = 600L * 1_000_000
    }
}
