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
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.core.scoring.Presets
import eu.advantly.presentation.live.LiveSettings
import eu.advantly.presentation.match.InMemoryMatchStore
import eu.advantly.presentation.match.LiveMatchPort
import eu.advantly.presentation.match.MatchHistoryPort
import eu.advantly.presentation.match.MatchSetup
import eu.advantly.presentation.match.PlayersPort
import eu.advantly.presentation.match.WallClock
import eu.advantly.presentation.wizard.MatchWizard
import eu.advantly.ui.phone.live.LiveScoreController

/** Die fünf Tabs der Bottom-Navigation (04 §2.1); [Play] ist eine Aktion, kein eigener Inhalt. */
enum class AppTab { Home, History, Play, Friends, Profile }

/** Vollbild-Ebenen über den Tabs. */
sealed interface Overlay {
    data object Wizard : Overlay

    data object Live : Overlay

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
 * Wurzel-Zustand der Phone-App: Ports (heute [InMemoryMatchStore], später `match-session`), Assistent,
 * Live-Controller und Navigation. Lebt in einem ViewModel und übersteht damit Rotation.
 */
@Stable
class AdvantlyAppModel(
    val live: LiveMatchPort,
    val history: MatchHistoryPort,
    val players: PlayersPort,
    settings: LiveSettings = LiveSettings(),
    /** Uhr für Anzeigen (Matchdauer, Wochenbilanz); dieselbe Basis wie die Zeitstempel des Ports. */
    val clock: WallClock = WallClock.System,
) {
    val wizard = MatchWizard(players)
    val controller = LiveScoreController(live, settings, clock = clock)
    val navigator = AppNavigator()

    /** Läuft ein Match (nicht beendet)? Dann führen „Spielen“ und Schnellstart zum Live-Score. */
    val hasRunningMatch: Boolean
        get() =
            live.active.value
                ?.state
                ?.status
                ?.let { it != MatchStatus.FINISHED } == true

    /** Tab „Spielen (+)“: laufendes Match → Live, sonst Assistent (04 §2.1). */
    fun onPlay() {
        if (live.active.value != null) {
            navigator.push(Overlay.Live)
        } else {
            wizard.reset()
            navigator.push(Overlay.Wizard)
        }
    }

    /** Wartender Start, solange der Nutzer den Abbruch des laufenden Matches noch nicht bestätigt hat. */
    var pendingStart: MatchSetup? by mutableStateOf(null)
        private set

    /**
     * Einziger Weg zu `live.start` (Assistent, Schnellstart, „Nochmal in gleicher Besetzung“): Läuft noch ein Match,
     * wird erst nach Bestätigung gestartet ([confirmPendingStart]) – sonst würde es stillschweigend abgebrochen.
     */
    fun startMatch(setup: MatchSetup) {
        if (hasRunningMatch) {
            pendingStart = setup
            return
        }
        doStart(setup)
    }

    /** Laufendes Match abbrechen (`ABANDONED`) und das wartende starten. */
    fun confirmPendingStart() {
        val setup = pendingStart ?: return
        pendingStart = null
        doStart(setup)
    }

    /** Wartenden Start verwerfen; das laufende Match bleibt unverändert. */
    fun cancelPendingStart() {
        pendingStart = null
    }

    private fun doStart(setup: MatchSetup) {
        live.start(setup)
        controller.sync(live.active.value)
        when (navigator.top) {
            Overlay.Wizard, is Overlay.Result -> navigator.replaceTop(Overlay.Live)
            else -> navigator.push(Overlay.Live)
        }
    }

    /** Assistent abschließen. */
    fun finishWizard() {
        wizard.finish()?.let(::startMatch)
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

    /** Einfaches Tennis-Match für den Auto-Benchmark (Debug, Spike S4). */
    fun startBenchmarkMatch() {
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
    }

    private companion object {
        /** Debug-Daten, kein UI-Text. */
        const val BENCHMARK_OPPONENT = "Bench"
    }
}

/** ViewModel-Hülle, damit [AdvantlyAppModel] Rotation übersteht. */
class AdvantlyAppViewModel(
    savedState: SavedStateHandle,
    store: InMemoryMatchStore = InMemoryMatchStore(),
) : ViewModel() {
    val model = AdvantlyAppModel(store, store, store, LiveSettings(savedState))
}

/** [AdvantlyAppModel] vom ViewModelStoreOwner des Hosts (Activity, ComposeUIViewController, Desktop-Fenster). */
@Composable
fun rememberAdvantlyAppModel(): AdvantlyAppModel {
    val vm =
        viewModel {
            // Bietet ein Host keine SavedState-Unterstützung, bleibt es beim ViewModel ohne Prozesstod-Schutz.
            AdvantlyAppViewModel(runCatching { createSavedStateHandle() }.getOrElse { SavedStateHandle() })
        }
    return vm.model
}
