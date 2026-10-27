package eu.advantly.ui.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.LifecycleResumeEffect
import eu.advantly.presentation.auth.AuthViewModel
import eu.advantly.presentation.auth.GoogleCredential
import eu.advantly.presentation.match.LiveMatchSnapshot
import eu.advantly.presentation.match.ScorerOption
import eu.advantly.presentation.match.StartOptions
import eu.advantly.presentation.match.WatchStatus
import eu.advantly.presentation.support.SupportViewModel
import eu.advantly.ui.phone.app.AdvantlyAppModel
import eu.advantly.ui.phone.app.AppTab
import eu.advantly.ui.phone.app.ComingSoonScreen
import eu.advantly.ui.phone.app.LocalTelemetryVisibility
import eu.advantly.ui.phone.app.MiniScoreBar
import eu.advantly.ui.phone.app.Overlay
import eu.advantly.ui.phone.app.TabBar
import eu.advantly.ui.phone.app.rememberAdvantlyAppModel
import eu.advantly.ui.phone.auth.AccountScreen
import eu.advantly.ui.phone.auth.StartLoginHost
import eu.advantly.ui.phone.auth.StartLoginScreen
import eu.advantly.ui.phone.auth.shouldShowStartLogin
import eu.advantly.ui.phone.common.LocalArchivedPlayerIds
import eu.advantly.ui.phone.common.LocalSelfAliases
import eu.advantly.ui.phone.common.LocalSelfId
import eu.advantly.ui.phone.common.PlaceholderAssignments
import eu.advantly.ui.phone.common.UpdateRequiredBanner
import eu.advantly.ui.phone.common.safeContentPadding
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.friends.FriendsScreen
import eu.advantly.ui.phone.friends.MatchSharingSection
import eu.advantly.ui.phone.history.HeadToHeadLinks
import eu.advantly.ui.phone.history.HeadToHeadScreen
import eu.advantly.ui.phone.history.HistoryScreen
import eu.advantly.ui.phone.home.HomeScreen
import eu.advantly.ui.phone.home.WatchInstallHost
import eu.advantly.ui.phone.live.LiveScoreScreen
import eu.advantly.ui.phone.live.matchLabel
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.action_cancel
import eu.advantly.ui.phone.resources.auth_open
import eu.advantly.ui.phone.resources.profile_restore_previous
import eu.advantly.ui.phone.resources.support_open
import eu.advantly.ui.phone.resources.watch_takeover_confirm
import eu.advantly.ui.phone.resources.watch_takeover_text
import eu.advantly.ui.phone.resources.watch_takeover_title
import eu.advantly.ui.phone.resources.wizard_start
import eu.advantly.ui.phone.resources.wizard_title_scorer
import eu.advantly.ui.phone.result.ResultScreen
import eu.advantly.ui.phone.support.SupportScreen
import eu.advantly.ui.phone.wizard.ScorerChooser
import eu.advantly.ui.phone.wizard.WizardScreen
import org.jetbrains.compose.resources.stringResource

/**
 * Wurzel der Phone-App (04 §2.1): fünf Tabs Start · Verlauf · Spielen (+) · Freunde · Profil, Mini-Scorebar für
 * das laufende Match und Vollbild-Ebenen (Assistent, Live-Score, Ergebnis). Der Live-Score ist immer dunkel, der
 * Rest folgt [darkTheme] (Default: System).
 *
 * @param debugTools Latenz-Overlay, Entprellung und Auto-Benchmark im Live-Menü (nur Debug-Builds/Preview)
 * @param autoBenchmark startet direkt ein Tennis-Match und den Auto-Benchmark (iOS-CI, Spike S4)
 * @param onRestorePrevious Recovery-Paket: späterer Übernahmeweg im Profil; nur gesetzt, solange der Host ihn anbietet
 * @param onShareMatchImage teilt die Ergebniskarte (Bild + Text mit Link) über die Plattform; `null` blendet „Teilen“ aus
 * @param onShareText Teilen-Menü der Plattform für reinen Text (z. B. Freundescode); `null` blendet Teilen aus
 * @param watchInstall Uhr-App auf einer Uhr ohne Advantly installieren (nur Android); `null` blendet den Hinweis aus
 * @param startLogin gespeicherte Wahl zum Startbildschirm „Konto oder ohne Konto“; `null` (iOS/Desktop) zeigt ihn nie
 * @param recoveryHold Recovery-Hold bzw. sein Dialog ist offen: der Startbildschirm wartet, bis er entschieden ist
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
    authModel: AuthViewModel? = null,
    googleCredential: (suspend () -> GoogleCredential)? = null,
    onBenchmarkFinished: (String) -> Unit = {},
    buildCommit: String = "",
    buildTimeUtc: String = "",
    supportModel: SupportViewModel? = null,
    supportDiagnostics: Map<String, String> = emptyMap(),
    onShareExport: (() -> Unit)? = null,
    onRestorePrevious: (() -> Unit)? = null,
    onShareMatchImage: (suspend (ImageBitmap, String) -> Unit)? = null,
    onShareText: ((String) -> Unit)? = null,
    watchInstall: WatchInstallHost? = null,
    startLogin: StartLoginHost? = null,
    recoveryHold: Boolean = false,
) {
    val authState = authModel?.state?.collectAsState()?.value
    val startLoginDecided = startLogin?.decided?.collectAsState()?.value
    // Wer angemeldet war, hat sich entschieden: nach Abmelden oder Kontolöschung kein erneuter Startbildschirm.
    LaunchedEffect(authState?.authenticated, startLogin) { if (authState?.authenticated == true) startLogin?.decide() }
    val showStartLogin =
        shouldShowStartLogin(
            available = authModel != null && startLoginDecided != null,
            decided = startLoginDecided == true,
            signedIn = authState?.authenticated == true,
            checking = authState?.busy == true,
            checkFailed = authState?.error != null,
            recoveryHold = recoveryHold,
        )
    val archived by model.players.archivedPlayerIds.collectAsState()
    val self by model.players.self.collectAsState()
    val aliases by model.players.ownerAliases.collectAsState()
    val updateRequired by model.live.updateRequired.collectAsState()
    val telemetryVisibility by model.telemetry.telemetryVisibility.collectAsState()
    val navigator = model.navigator
    if (autoBenchmark) {
        LaunchedEffect(Unit) { navigator.push(Overlay.Live) }
    }
    BackHandler(enabled = navigator.top != null) { model.onBack() }
    // App wieder im Vordergrund: Stand der Uhr neu prüfen (Uhr-App evtl. inzwischen installiert/geöffnet)
    LifecycleResumeEffect(model) {
        model.live.refreshWatchStatus()
        onPauseOrDispose {}
    }
    CompositionLocalProvider(
        LocalSelfId provides self.id,
        LocalSelfAliases provides aliases,
        LocalArchivedPlayerIds provides archived,
        LocalTelemetryVisibility provides telemetryVisibility,
    ) {
        AdvantlyTheme(darkTheme = darkTheme) {
            Box(modifier.fillMaxSize().background(AdvantlyTheme.colors.bg)) {
                // Unter einer Vollbild-Ebene werden die Tabs nicht gezeichnet: nichts Verdecktes bleibt bedienbar
                // oder für Screenreader erreichbar.
                if (navigator.top == null) {
                    Column(Modifier.fillMaxSize().safeContentPadding()) {
                        UpdateRequiredBanner(updateRequired)
                        Box(Modifier.weight(1f)) {
                            TabLayer(
                                model,
                                versionName,
                                authModel,
                                googleCredential,
                                buildCommit,
                                buildTimeUtc,
                                supportModel,
                                supportDiagnostics,
                                onShareExport,
                                onRestorePrevious,
                                onShareText,
                                watchInstall,
                                onStartLoginDecided = if (showStartLogin) startLogin?.let { it::decide } else null,
                            )
                        }
                    }
                }
                when (val top = navigator.top) {
                    Overlay.Wizard -> {
                        Column(Modifier.fillMaxSize().safeContentPadding()) {
                            UpdateRequiredBanner(updateRequired)
                            // Freunde nur mit nutzbarem Konto; sonst liefert das ViewModel eine leere Liste.
                            val friends =
                                authModel
                                    ?.friends
                                    ?.state
                                    ?.collectAsState()
                                    ?.value
                                    ?.friends
                                    .orEmpty()
                            WizardScreen(
                                model.wizard,
                                onClose = { navigator.pop() },
                                onStart = model::finishWizard,
                                busyMatch = model.watchMatch(),
                                friends = friends,
                            )
                        }
                    }

                    Overlay.Live -> {
                        AdvantlyTheme(darkTheme = true) {
                            LiveScoreScreen(
                                controller = model.controller,
                                playerAssignments = { PlaceholderAssignments(it.matchId, it.setup, model.history, model.players) },
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
                        val history by model.history.matches.collectAsState()
                        val revision = history.firstOrNull { it.matchId == top.matchId }
                        val record = remember(top.matchId, revision) { model.history.record(top.matchId) }
                        ResultScreen(
                            record = record,
                            statisticsCache = model.statistics,
                            headToHead = { setup -> HeadToHeadLinks(setup) { navigator.push(Overlay.PlayerHistory(it)) } },
                            playerAssignments = {
                                PlaceholderAssignments(
                                    it.summary.matchId,
                                    it.summary.setup,
                                    model.history,
                                    model.players,
                                )
                            },
                            onBack = { navigator.pop() },
                            onShareImage = onShareMatchImage,
                            onPlayAgain = { model.startMatch(it) },
                            onDelete = {
                                model.history.delete(top.matchId)
                                navigator.pop()
                            },
                            sharing = { shown ->
                                authModel?.friends?.let { friends ->
                                    MatchSharingSection(
                                        friends,
                                        top.matchId.value,
                                        slotName = { slot ->
                                            shown.summary.setup.players
                                                .firstOrNull { it.id.value == slot }
                                                ?.displayName
                                        },
                                        // „War ich nicht“/„Ausblenden“: kein lokales Löschen (Tombstone sperrte die
                                        // Rematerialisierung); der angestoßene Pull entzieht die lokale Kopie.
                                        onGone = { navigator.pop() },
                                    )
                                }
                            },
                        )
                    }

                    is Overlay.PlayerHistory -> {
                        HeadToHeadScreen(
                            top.playerId,
                            model.history,
                            { navigator.pop() },
                            model::openResult,
                            fallbackName =
                                model.players.guests.value
                                    .firstOrNull { it.id == top.playerId }
                                    ?.displayName
                                    .orEmpty(),
                        )
                    }

                    null -> {
                        Unit
                    }
                }
                model.pendingScorerChoice?.let { ScorerDialog(model) }
                model.pendingWatchTakeOver?.let { WatchTakeOverDialog(model, it.busy) }
            }
        }
    }
}

/** Tabs, Mini-Scorebar und Tab-Leiste (nur ohne Vollbild-Ebene); mit [onStartLoginDecided] stattdessen der Startbildschirm. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TabLayer(
    model: AdvantlyAppModel,
    versionName: String,
    authModel: AuthViewModel?,
    googleCredential: (suspend () -> GoogleCredential)?,
    buildCommit: String,
    buildTimeUtc: String,
    supportModel: SupportViewModel?,
    supportDiagnostics: Map<String, String>,
    onShareExport: (() -> Unit)?,
    onRestorePrevious: (() -> Unit)?,
    onShareText: ((String) -> Unit)?,
    watchInstall: WatchInstallHost?,
    onStartLoginDecided: (() -> Unit)?,
) {
    var showAccount by remember { mutableStateOf(false) }
    val active by model.live.active.collectAsState()
    val open by model.live.openMatches.collectAsState()
    val navigator = model.navigator
    var showSupport by remember(navigator.tab) { mutableStateOf(false) }
    val openAccount = {
        authModel?.refresh()
        showAccount = true
        navigator.tab = AppTab.Profile
    }
    if (onStartLoginDecided != null) {
        // Vollbild ohne Tab-Leiste; Konto führt in den bestehenden Fluss unter Profil → Konto.
        StartLoginScreen(
            onAccount = {
                onStartLoginDecided()
                openAccount()
            },
            onSkip = {
                onStartLoginDecided()
                navigator.tab = AppTab.Home
            },
        )
        return
    }
    Column(Modifier.fillMaxSize().safeContentPadding()) {
        Box(Modifier.weight(1f)) {
            if (showSupport && supportModel != null) {
                BackHandler { showSupport = false }
                SupportScreen(supportModel, supportDiagnostics + mapOf("app_version" to versionName, "commit" to buildCommit)) {
                    showSupport = false
                }
            } else {
                when (navigator.tab) {
                    AppTab.Home, AppTab.Play -> {
                        HomeScreen(
                            model,
                            clock = model.clock,
                            onSupport = if (supportModel != null) ({ showSupport = true }) else null,
                            watchInstall = watchInstall,
                        )
                    }

                    AppTab.History -> {
                        HistoryScreen(model.history, onOpen = model::openResult)
                    }

                    AppTab.Friends -> {
                        val friends = authModel?.friends
                        if (friends == null) {
                            // Build ohne Backend: Freunde gibt es hier nicht, P1 bleibt unverändert.
                            ComingSoonScreen(AppTab.Friends, versionName)
                        } else {
                            FriendsScreen(
                                friends,
                                onOpenAccount = openAccount,
                                onShareText = onShareText,
                            )
                        }
                    }

                    AppTab.Profile -> {
                        if (showAccount && authModel != null) {
                            AccountScreen(
                                authModel,
                                googleCredential,
                                onSupport =
                                    if (supportModel !=
                                        null
                                    ) {
                                        ({ showSupport = true })
                                    } else {
                                        null
                                    },
                                onClose = { showAccount = false },
                                onShareExport = onShareExport,
                            )
                        } else {
                            Column {
                                if (supportModel != null) {
                                    TextButton({ showSupport = true }, modifier = Modifier.testTag("profile_support")) {
                                        Text(stringResource(Res.string.support_open))
                                    }
                                }
                                if (authModel != null) {
                                    TextButton(
                                        {
                                            authModel.refresh()
                                            showAccount = true
                                        },
                                        modifier = Modifier.testTag("profile_account"),
                                    ) { Text(stringResource(Res.string.auth_open)) }
                                }
                                if (onRestorePrevious != null) {
                                    TextButton(onRestorePrevious, modifier = Modifier.testTag("profile_restore_previous")) {
                                        Text(stringResource(Res.string.profile_restore_previous))
                                    }
                                }
                                ComingSoonScreen(
                                    AppTab.Profile,
                                    versionName,
                                    players = model.players,
                                    onPlayerHistory = { navigator.push(Overlay.PlayerHistory(it)) },
                                    telemetry = model.telemetry,
                                    buildCommit = buildCommit,
                                    buildTimeUtc = buildTimeUtc,
                                )
                            }
                        }
                    }
                }
            }
        }
        active?.let { snapshot -> MiniScoreBar(snapshot, onClick = { navigator.push(Overlay.Live) }, more = open.size - 1) }
        TabBar(
            selected = navigator.tab,
            onSelect = { tab -> if (tab == AppTab.Play) model.onPlay() else navigator.tab = tab },
        )
    }
}

/** Schnellstart mit verbundener Uhr: „Wer zählt?“ bestätigen (04 §3.1, Presets vergeben keine Zählrechte). */
@Composable
private fun ScorerDialog(model: AdvantlyAppModel) {
    val watchStatus by model.live.watchStatus.collectAsState()
    val watchAvailable = watchStatus == WatchStatus.AVAILABLE
    LaunchedEffect(Unit) { model.live.refreshWatchStatus() }
    val busy = model.watchMatch()
    // Zählt die Uhr schon ein anderes Match, ist „Dieses Handy“ der Standard (F22); „Meine Uhr“ wechselt das Uhr-Match
    var scorer by remember { mutableStateOf(if (watchAvailable && busy == null) ScorerOption.MY_WATCH else ScorerOption.THIS_PHONE) }
    var workout by remember { mutableStateOf(true) }
    val effective = if (watchAvailable) scorer else ScorerOption.THIS_PHONE
    AlertDialog(
        onDismissRequest = model::cancelScorerChoice,
        title = { Text(stringResource(Res.string.wizard_title_scorer)) },
        text = {
            ScorerChooser(
                selected = effective,
                watchStatus = watchStatus,
                watchWorkout = workout,
                onSelect = { scorer = it },
                onWatchWorkout = { workout = it },
                onRecheck = model.live::refreshWatchStatus,
                busyMatch = busy,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { model.confirmScorerChoice(StartOptions(effective, workout)) },
                modifier = Modifier.testTag("scorer_dialog_start"),
            ) { Text(stringResource(Res.string.wizard_start)) }
        },
        dismissButton = {
            TextButton(onClick = model::cancelScorerChoice, modifier = Modifier.testTag("scorer_dialog_cancel")) {
                Text(stringResource(Res.string.action_cancel))
            }
        },
        modifier = Modifier.testTag("scorer_dialog"),
    )
}

/**
 * „Uhr nicht erreichbar“ (Review W3-M #5): Die Uhr zählt noch [busy]. Erst nach Bestätigung übernimmt das Handy dieses
 * Match und das neue startet auf der Uhr; Eingaben, die die Uhr danach dort noch macht, werden verworfen.
 */
@Composable
private fun WatchTakeOverDialog(
    model: AdvantlyAppModel,
    busy: LiveMatchSnapshot,
) {
    AlertDialog(
        onDismissRequest = model::cancelWatchTakeOver,
        title = { Text(stringResource(Res.string.watch_takeover_title)) },
        text = {
            Text(
                stringResource(Res.string.watch_takeover_text, matchLabel(busy)),
                modifier = Modifier.testTag("watch_takeover_text"),
            )
        },
        confirmButton = {
            TextButton(onClick = model::confirmWatchTakeOver, modifier = Modifier.testTag("watch_takeover_confirm")) {
                Text(stringResource(Res.string.watch_takeover_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = model::cancelWatchTakeOver, modifier = Modifier.testTag("watch_takeover_cancel")) {
                Text(stringResource(Res.string.action_cancel))
            }
        },
        modifier = Modifier.testTag("watch_takeover_dialog"),
    )
}
