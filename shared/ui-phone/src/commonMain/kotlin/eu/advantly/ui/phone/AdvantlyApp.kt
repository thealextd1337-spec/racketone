package eu.advantly.ui.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.platform.testTag
import eu.advantly.ui.phone.app.AdvantlyAppModel
import eu.advantly.ui.phone.app.AppTab
import eu.advantly.ui.phone.app.ComingSoonScreen
import eu.advantly.ui.phone.app.MiniScoreBar
import eu.advantly.ui.phone.app.Overlay
import eu.advantly.ui.phone.app.TabBar
import eu.advantly.ui.phone.app.rememberAdvantlyAppModel
import eu.advantly.ui.phone.common.LocalSelfId
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.history.HistoryScreen
import eu.advantly.ui.phone.home.HomeScreen
import eu.advantly.ui.phone.live.LiveScoreScreen
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.abandon_confirm
import eu.advantly.ui.phone.resources.abandon_keep
import eu.advantly.ui.phone.resources.abandon_text
import eu.advantly.ui.phone.resources.abandon_title
import eu.advantly.ui.phone.result.ResultScreen
import eu.advantly.ui.phone.wizard.WizardScreen
import org.jetbrains.compose.resources.stringResource

/**
 * Wurzel der Phone-App (04 §2.1): fünf Tabs Start · Verlauf · Spielen (+) · Freunde · Profil, Mini-Scorebar für
 * das laufende Match und Vollbild-Ebenen (Assistent, Live-Score, Ergebnis). Der Live-Score ist immer dunkel, der
 * Rest folgt [darkTheme] (Default: System).
 *
 * @param debugTools Latenz-Overlay, Entprellung und Auto-Benchmark im Live-Menü (nur Debug-Builds/Preview)
 * @param autoBenchmark startet direkt ein Tennis-Match und den Auto-Benchmark (iOS-CI, Spike S4)
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AdvantlyApp(
    modifier: Modifier = Modifier,
    model: AdvantlyAppModel = rememberAdvantlyAppModel(),
    versionName: String = "",
    darkTheme: Boolean = isSystemInDarkTheme(),
    debugTools: Boolean = false,
    autoBenchmark: Boolean = false,
    onBenchmarkFinished: (String) -> Unit = {},
) {
    val self by model.players.self.collectAsState()
    val navigator = model.navigator
    if (autoBenchmark) {
        LaunchedEffect(Unit) { navigator.push(Overlay.Live) }
    }
    BackHandler(enabled = navigator.top != null) { model.onBack() }
    CompositionLocalProvider(LocalSelfId provides self.id) {
        AdvantlyTheme(darkTheme = darkTheme) {
            Box(modifier.fillMaxSize().background(AdvantlyTheme.colors.bg)) {
                // Unter einer Vollbild-Ebene werden die Tabs nicht gezeichnet: nichts Verdecktes bleibt bedienbar
                // oder für Screenreader erreichbar.
                if (navigator.top == null) {
                    TabLayer(model, versionName)
                }
                when (val top = navigator.top) {
                    Overlay.Wizard -> {
                        WizardScreen(model.wizard, onClose = { navigator.pop() }, onStart = model::finishWizard)
                    }

                    Overlay.Live -> {
                        AdvantlyTheme(darkTheme = true) {
                            LiveScoreScreen(
                                controller = model.controller,
                                debugTools = debugTools,
                                onMinimize = { navigator.pop() },
                                onShowResult = model::openResult,
                                onBenchmarkNewMatch = if (debugTools || autoBenchmark) model::startBenchmarkMatch else null,
                                autoBenchmark = autoBenchmark,
                                onBenchmarkFinished = onBenchmarkFinished,
                            )
                        }
                    }

                    is Overlay.Result -> {
                        ResultScreen(
                            record = model.history.record(top.matchId),
                            onBack = { navigator.pop() },
                            onPlayAgain = model::startMatch,
                            onDelete = {
                                model.history.delete(top.matchId)
                                navigator.pop()
                            },
                        )
                    }

                    null -> {
                        Unit
                    }
                }
                model.pendingStart?.let { AbandonDialog(model) }
            }
        }
    }
}

/** Tabs, Mini-Scorebar und Tab-Leiste (nur ohne Vollbild-Ebene). */
@Composable
private fun TabLayer(
    model: AdvantlyAppModel,
    versionName: String,
) {
    val active by model.live.active.collectAsState()
    val navigator = model.navigator
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Box(Modifier.weight(1f)) {
            when (navigator.tab) {
                AppTab.Home, AppTab.Play -> HomeScreen(model, clock = model.clock)
                AppTab.History -> HistoryScreen(model.history, onOpen = model::openResult)
                AppTab.Friends -> ComingSoonScreen(AppTab.Friends, versionName)
                AppTab.Profile -> ComingSoonScreen(AppTab.Profile, versionName)
            }
        }
        active?.let { snapshot -> MiniScoreBar(snapshot, onClick = { navigator.push(Overlay.Live) }) }
        TabBar(
            selected = navigator.tab,
            onSelect = { tab -> if (tab == AppTab.Play) model.onPlay() else navigator.tab = tab },
        )
    }
}

/** Bestätigung, bevor ein laufendes Match für einen neuen Start abgebrochen wird (Review W1-C, Punkt 1). */
@Composable
private fun AbandonDialog(model: AdvantlyAppModel) {
    AlertDialog(
        onDismissRequest = model::cancelPendingStart,
        title = { Text(stringResource(Res.string.abandon_title)) },
        text = { Text(stringResource(Res.string.abandon_text)) },
        confirmButton = {
            TextButton(onClick = model::confirmPendingStart, modifier = Modifier.testTag("abandon_confirm")) {
                Text(stringResource(Res.string.abandon_confirm), color = AdvantlyTheme.colors.danger)
            }
        },
        dismissButton = {
            TextButton(onClick = model::cancelPendingStart, modifier = Modifier.testTag("abandon_cancel")) {
                Text(stringResource(Res.string.abandon_keep))
            }
        },
        modifier = Modifier.testTag("abandon_dialog"),
    )
}
