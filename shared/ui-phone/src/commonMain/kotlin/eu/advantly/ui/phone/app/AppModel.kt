package eu.advantly.ui.phone.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.advantly.core.model.Discipline
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.PlayerId
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.core.scoring.Presets
import eu.advantly.presentation.live.LiveSettings
import eu.advantly.presentation.match.InMemoryMatchStore
import eu.advantly.presentation.match.InMemoryTelemetrySettings
import eu.advantly.presentation.match.LiveMatchPort
import eu.advantly.presentation.match.LiveMatchSnapshot
import eu.advantly.presentation.match.MatchHistoryPort
import eu.advantly.presentation.match.MatchSetup
import eu.advantly.presentation.match.MatchStore
import eu.advantly.presentation.match.MonotonicClock
import eu.advantly.presentation.match.PlayersPort
import eu.advantly.presentation.match.ScorerOption
import eu.advantly.presentation.match.SessionMatchStore
import eu.advantly.presentation.match.StartOptions
import eu.advantly.presentation.match.TelemetrySettingsPort
import eu.advantly.presentation.match.WallClock
import eu.advantly.presentation.stats.MatchStatisticsCache
import eu.advantly.presentation.wizard.MatchWizard
import eu.advantly.ui.phone.live.LiveScoreController

/** Start, der auf die bestätigte Übernahme des Matches der nicht erreichbaren Uhr wartet ([busy]). */
data class PendingWatchTakeOver(
    val setup: MatchSetup,
    val options: StartOptions,
    val busy: LiveMatchSnapshot,
)

/** Die fünf Tabs der Bottom-Navigation (04 §2.1); [Play] ist eine Aktion, kein eigener Inhalt. */
enum class AppTab { Home, History, Play, Friends, Profile }

/** Vollbild-Ebenen über den Tabs. */
sealed interface Overlay {
    data object Wizard : Overlay

    data object Live : Overlay

    data class PlayerHistory(
        val playerId: PlayerId,
    ) : Overlay

    data class Result(
        val matchId: MatchId,
    ) : Overlay
}

/** Einfache Navigation: aktiver Tab + Stapel von Vollbild-Ebenen. Compose-State, nur vom UI-Thread benutzen. */
@Stable
class AppNavigator {
    var tab by mutableStateOf(AppTab.Home)
    val stack = mutableStateListOf<Overlay>()

    val top: Overlay? get() = stack.lastOrNull()

    fun push(overlay: Overlay) {
        stack.remove(overlay)
        stack.add(overlay)
    }

    /** Ersetzt die oberste Ebene (z. B. Assistent → Live). */
    fun replaceTop(overlay: Overlay) {
        stack.removeLastOrNull()
        push(overlay)
    }

    fun pop(): Boolean = stack.removeLastOrNull() != null
}

/**
 * Wurzel-Zustand der Phone-App: Ports (App: `SessionMatchStore` über der Match-Session, Tests/Previews:
 * [InMemoryMatchStore]), Assistent, Live-Controller und Navigation. Lebt in einem ViewModel und übersteht damit
 * Rotation.
 */
@Stable
class AdvantlyAppModel(
    val live: LiveMatchPort,
    val history: MatchHistoryPort,
    val players: PlayersPort,
    settings: LiveSettings = LiveSettings(),
    /** Uhr für Anzeigen (Matchdauer, Wochenbilanz); dieselbe Basis wie die Zeitstempel des Ports. */
    val clock: WallClock = WallClock.System,
    /** Monotone Uhr für „Stand vor X s“; dieselbe Basis wie `SyncInfo.lastScorerContactMono` des Ports. */
    val monotonic: MonotonicClock = MonotonicClock.System,
    /** Telemetrie-Sichtbarkeit (P2-J); Standard: der Store hinter [players], sonst nur im Speicher. */
    val telemetry: TelemetrySettingsPort = players as? TelemetrySettingsPort ?: InMemoryTelemetrySettings(),
) {
    val wizard =
        MatchWizard(
            players,
            watchStatus = live.watchStatus,
            refreshWatch = live::refreshWatchStatus,
            watchBusy = { watchMatch() != null },
        )
    val controller = LiveScoreController(live, settings, clock = clock, monotonic = monotonic)
    val navigator = AppNavigator()
    val statistics = MatchStatisticsCache()

    /** Läuft ein Match (nicht beendet)? Dann führen „Spielen“ und Schnellstart zum Live-Score. */
    val hasRunningMatch: Boolean
        get() =
            live.active.value
                ?.state
                ?.status
                ?.let { it != MatchStatus.FINISHED } == true

    /**
     * Tab „Spielen (+)“ und „Neues Match“: immer der Assistent – laufende Matches laufen weiter (F22, mehrere Plätze);
     * zu ihnen führen Übersicht und Mini-Scorebar.
     */
    fun onPlay() {
        wizard.reset()
        navigator.push(Overlay.Wizard)
    }

    /** Live-Ansicht eines offenen Matches öffnen (Übersicht am Start, Match-Wechsel im Live-Screen). */
    fun openMatch(matchId: MatchId) {
        if (!live.select(matchId)) return
        controller.sync(live.active.value)
        if (navigator.top != Overlay.Live) navigator.push(Overlay.Live)
    }

    /** Das laufende Match, das die eigene Uhr gerade zählt (sie ist dann nicht frei, F22), sonst `null`. */
    fun watchMatch(): LiveMatchSnapshot? = live.openMatches.value.firstOrNull { it.watchScores }

    /** Schnellstart, der noch auf „Wer zählt?“ wartet (nur mit verbundener Uhr, 04 §3.1). */
    var pendingScorerChoice: MatchSetup? by mutableStateOf(null)
        private set

    /**
     * Einziger Weg zu `live.start` (Assistent, Schnellstart, „Nochmal in gleicher Besetzung“). Ohne [options]
     * (Schnellstart) wird die Zähler-Wahl bestätigt, sobald eine Uhr verbunden ist ([confirmScorerChoice]); ohne Uhr
     * zählt dieses Handy. Laufende Matches bleiben offen (F22) – es wird nichts abgebrochen.
     */
    fun startMatch(
        setup: MatchSetup,
        options: StartOptions? = null,
    ) {
        if (options == null && live.watchAvailable.value) {
            pendingScorerChoice = setup
            return
        }
        doStart(setup, options ?: StartOptions(ScorerOption.THIS_PHONE))
    }

    /** Zähler-Wahl des Schnellstarts bestätigen und starten. */
    fun confirmScorerChoice(options: StartOptions) {
        val setup = pendingScorerChoice ?: return
        pendingScorerChoice = null
        startMatch(setup, options)
    }

    fun cancelScorerChoice() {
        pendingScorerChoice = null
    }

    /**
     * Start mit „Meine Uhr“, während die nicht erreichbare Uhr noch ein anderes Match zählt (Review W3-M #5): wartet auf
     * die Bestätigung „Das Handy übernimmt …, spätere Uhr-Eingaben dort werden verworfen“.
     */
    var pendingWatchTakeOver: PendingWatchTakeOver? by mutableStateOf(null)
        private set

    /** Übernahme bestätigt: das Handy übernimmt das alte Uhr-Match, das neue Match startet auf der Uhr. */
    fun confirmWatchTakeOver() {
        val pending = pendingWatchTakeOver ?: return
        pendingWatchTakeOver = null
        doStart(pending.setup, pending.options.copy(takeOverWatchMatch = true))
    }

    fun cancelWatchTakeOver() {
        pendingWatchTakeOver = null
    }

    private fun doStart(
        setup: MatchSetup,
        options: StartOptions,
    ) {
        if (options.scorer == ScorerOption.MY_WATCH && !options.takeOverWatchMatch) {
            live.watchTakeOverNeeded()?.let { busy ->
                pendingWatchTakeOver = PendingWatchTakeOver(setup, options, busy)
                return
            }
        }
        live.start(setup, options)
        controller.sync(live.active.value)
        when (navigator.top) {
            Overlay.Wizard, is Overlay.Result -> navigator.replaceTop(Overlay.Live)
            else -> navigator.push(Overlay.Live)
        }
    }

    /** Assistent abschließen (Zähler und Workout-Wunsch aus dem Schritt „Wer zählt?“). */
    fun finishWizard() {
        val options = wizard.state.value.startOptions
        wizard.finish()?.let { startMatch(it, options) }
    }

    fun openResult(matchId: MatchId) {
        if (navigator.top == Overlay.Live) navigator.replaceTop(Overlay.Result(matchId)) else navigator.push(Overlay.Result(matchId))
    }

    /** Zurück-Geste: Assistent schrittweise, sonst oberste Ebene schließen. */
    fun onBack(): Boolean =
        when (navigator.top) {
            Overlay.Wizard -> wizard.back() || navigator.pop()
            null -> false
            else -> navigator.pop()
        }

    /**
     * Einfaches Tennis-Match für den Auto-Benchmark (Debug, Spike S4). Bricht nie ein echtes laufendes Match ab:
     * läuft eines, liefert es `false` und der Benchmark misst nichts.
     */
    fun startBenchmarkMatch(): Boolean {
        if (hasRunningMatch) return false
        val self = players.self.value
        val opponent = players.guests.value.firstOrNull() ?: players.addGuest(BENCHMARK_OPPONENT)
        live.start(
            MatchSetup(
                format = Presets.TENNIS_SINGLES_BO3.format(Discipline.SINGLES),
                lineup = Lineup(listOf(self.id), listOf(opponent.id), self.id),
                players = listOf(self, opponent),
            ),
        )
        controller.sync(live.active.value)
        return true
    }

    private companion object {
        /** Debug-Daten, kein UI-Text. */
        const val BENCHMARK_OPPONENT = "Bench"
    }
}

/** ViewModel-Hülle, damit [AdvantlyAppModel] Rotation übersteht; meldet den Session-Adapter beim Aufräumen ab. */
class AdvantlyAppViewModel(
    savedState: SavedStateHandle,
    private val store: MatchStore = InMemoryMatchStore(),
) : ViewModel() {
    val model = AdvantlyAppModel(store, store, store, LiveSettings(savedState), telemetry = store)

    override fun onCleared() {
        (store as? SessionMatchStore)?.close()
    }
}

/**
 * [AdvantlyAppModel] vom ViewModelStoreOwner des Hosts (Activity, ComposeUIViewController, Desktop-Fenster).
 *
 * @param store Quelle der Ports, nur beim ersten Anlegen des ViewModels aufgerufen (Main-Thread). Apps übergeben den
 *   `SessionMatchStore` über ihrer prozessweiten Session; Default ist der In-Memory-Store (Preview, Tests).
 */
@Composable
fun rememberAdvantlyAppModel(store: () -> MatchStore = { InMemoryMatchStore() }): AdvantlyAppModel {
    val vm =
        viewModel {
            // Bietet ein Host keine SavedState-Unterstützung, bleibt es beim ViewModel ohne Prozesstod-Schutz.
            AdvantlyAppViewModel(runCatching { createSavedStateHandle() }.getOrElse { SavedStateHandle() }, store())
        }
    return vm.model
}
