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
import androidx.compose.runtime.DisposableEffect
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
import eu.advantly.core.model.TelemetryVisibility
import eu.advantly.presentation.auth.AuthStep
import eu.advantly.presentation.auth.AuthViewModel
import eu.advantly.presentation.auth.GoogleCredential
import eu.advantly.presentation.match.LiveMatchSnapshot
import eu.advantly.presentation.match.ScorerOption
import eu.advantly.presentation.match.StartOptions
import eu.advantly.presentation.match.WatchStatus
import eu.advantly.presentation.push.NotificationSettingsPort
import eu.advantly.presentation.push.PushTarget
import eu.advantly.presentation.stats.transfer
import eu.advantly.presentation.support.SupportViewModel
import eu.advantly.ui.phone.app.AdoptedHealthConsentDialog
import eu.advantly.ui.phone.app.AdvantlyAppModel
import eu.advantly.ui.phone.app.AppTab
import eu.advantly.ui.phone.app.ComingSoonScreen
import eu.advantly.ui.phone.app.LocalHealthConsent
import eu.advantly.ui.phone.app.LocalHealthDialogOpen
import eu.advantly.ui.phone.app.LocalTelemetryVisibility
import eu.advantly.ui.phone.app.MiniScoreBar
import eu.advantly.ui.phone.app.Overlay
import eu.advantly.ui.phone.app.ProfileScreen
import eu.advantly.ui.phone.app.TabBar
import eu.advantly.ui.phone.app.WHATS_NEW_KEY
import eu.advantly.ui.phone.app.WhatsNewSheet
import eu.advantly.ui.phone.app.rememberAdvantlyAppModel
import eu.advantly.ui.phone.app.whatsNewPoints
import eu.advantly.ui.phone.auth.AccountGate
import eu.advantly.ui.phone.common.LocalArchivedPlayerIds
import eu.advantly.ui.phone.common.LocalSelfAliases
import eu.advantly.ui.phone.common.LocalSelfId
import eu.advantly.ui.phone.common.PlaceholderAssignments
import eu.advantly.ui.phone.common.UpdateRequiredBanner
import eu.advantly.ui.phone.common.safeContentPadding
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.friends.FriendsScreen
import eu.advantly.ui.phone.friends.HomeLiveSection
import eu.advantly.ui.phone.friends.LiveFollowScreen
import eu.advantly.ui.phone.friends.LiveVisitorsButton
import eu.advantly.ui.phone.friends.MatchSharingSection
import eu.advantly.ui.phone.friends.MatchVisitorsSection
import eu.advantly.ui.phone.history.HeadToHeadLinks
import eu.advantly.ui.phone.history.HeadToHeadScreen
import eu.advantly.ui.phone.history.HistoryScreen
import eu.advantly.ui.phone.home.HomeScreen
import eu.advantly.ui.phone.home.WatchInstallHost
import eu.advantly.ui.phone.live.LiveScoreScreen
import eu.advantly.ui.phone.live.matchLabel
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.action_cancel
import eu.advantly.ui.phone.resources.watch_takeover_confirm
import eu.advantly.ui.phone.resources.watch_takeover_text
import eu.advantly.ui.phone.resources.watch_takeover_title
import eu.advantly.ui.phone.resources.wizard_start
import eu.advantly.ui.phone.resources.wizard_title_scorer
import eu.advantly.ui.phone.result.PulseVisibilitySection
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
 * @param authModel Konto (Pflicht, 0.3.0): ohne nutzbares Konto zeigt die App nur den Konto-Fluss; `null` (Build ohne Backend,
 *   iOS) zählt ohne Konto
 * @param recoveryHold Recovery-Hold bzw. sein Dialog ist offen: der Konto-Fluss wartet, bis er entschieden ist
 * @param buildNumber `advantly.buildNumber` dieser App für „Was ist neu?“ (Plan 0.5.1 §6); `0` = nie zeigen (Preview)
 * @param notifications Push-Einstellung (nur Android mit Firebase); `null` blendet Profil-Abschnitt und Hinweis aus
 * @param pushTarget angetippte Benachrichtigung: öffnet den Freunde-Tab am Ziel, danach [onPushTargetHandled]
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
    recoveryHold: Boolean = false,
    buildNumber: Int = 0,
    notifications: NotificationSettingsPort? = null,
    pushTarget: PushTarget? = null,
    onPushTargetHandled: () -> Unit = {},
) {
    val accountState = authModel?.state?.collectAsState()?.value
    val archived by model.players.archivedPlayerIds.collectAsState()
    val self by model.players.self.collectAsState()
    val aliases by model.players.ownerAliases.collectAsState()
    val updateRequired by model.live.updateRequired.collectAsState()
    val telemetryVisibility by model.telemetry.telemetryVisibility.collectAsState()
    val healthConsent by model.telemetry.healthConsent.collectAsState()
    val navigator = model.navigator
    // Ein laufendes Match wird nicht unterbrochen: der Konto-Fluss erscheint nach dem Verlassen des Live-Scores.
    val accountRequired = accountState?.accountRequired == true && !recoveryHold && navigator.top != Overlay.Live
    if (autoBenchmark) {
        LaunchedEffect(Unit) { navigator.push(Overlay.Live) }
    }
    BackHandler(enabled = navigator.top != null && !accountRequired) { model.onBack() }
    // Angetippte Benachrichtigung: Freunde-Tab; das Ziel übernimmt der Tab, sobald er sichtbar ist.
    LaunchedEffect(pushTarget) {
        if (pushTarget != null) {
            navigator.stack.clear()
            navigator.tab = AppTab.Friends
        }
    }
    // App wieder im Vordergrund: Stand der Uhr neu prüfen (Uhr-App evtl. inzwischen installiert/geöffnet)
    LifecycleResumeEffect(model) {
        model.live.refreshWatchStatus()
        onPauseOrDispose {}
    }
    // Im Assistenten gewählte Zuschauer gehen an den Konto-Host (vorgemerkt bis zum Upload des Matches).
    DisposableEffect(model, authModel) {
        model.onVisitorsChosen = authModel?.friends?.let { friends -> { id, visitors -> friends.queueVisitors(id.value, visitors) } }
        onDispose { model.onVisitorsChosen = null }
    }
    // Schritt HEALTH im Konto-Einstieg nur, solange „Pulsverlauf aufzeichnen“ auf diesem Gerät offen ist (Plan 0.5.1 §5.1).
    DisposableEffect(model, authModel) {
        authModel?.healthDecisionOpen = { model.telemetry.healthConsentOpen.value }
        onDispose { authModel?.healthDecisionOpen = null }
    }
    val healthToConfirm by model.telemetry.healthConsentToConfirm.collectAsState()
    // „Was ist neu?“ (Plan 0.5.1 §6): nach einem Update, erst nach dem Zustimmungs-Gate, nie in einem laufenden Match.
    val openMatches by model.live.openMatches.collectAsState()
    var lastSeenBuild by remember(model) { mutableStateOf(model.live.localNote(WHATS_NEW_KEY)?.toIntOrNull()) }
    val seeWhatsNew = {
        model.live.putLocalNote(WHATS_NEW_KEY, buildNumber.toString())
        lastSeenBuild = buildNumber
    }
    // Neuinstallation bzw. neue Anmeldung: Der Einstieg fragt selbst (Schritt HEALTH) und übernimmt den Stand. Ohne
    // Konto-Host (iOS, Build ohne Backend) gilt nur ein fehlender Stand ohne jedes Match als Neuinstallation – wer schon
    // gespielt hat, aktualisiert von einer Version vor „Was ist neu?“ (Review N5).
    val pastMatches by model.history.matches.collectAsState()
    val freshInstall = lastSeenBuild == null && pastMatches.isEmpty() && openMatches.isEmpty()
    val signingIn =
        (accountState?.unconfirmed == false && accountState.step in SIGN_IN_STEPS) || (authModel == null && freshInstall)
    // Im Recovery-Hold schreibt die App nichts (geschützte Daten bleiben unverändert).
    LaunchedEffect(signingIn, buildNumber, recoveryHold) {
        if (signingIn && !recoveryHold && buildNumber > 0 && lastSeenBuild != buildNumber) seeWhatsNew()
    }
    val whatsNewPoints = remember(buildNumber) { whatsNewPoints(buildNumber) }
    val showWhatsNew =
        buildNumber > 0 &&
            !signingIn &&
            (lastSeenBuild ?: 0) < buildNumber &&
            whatsNewPoints.isNotEmpty() &&
            (accountState == null || (accountState.step == AuthStep.ACCOUNT && !accountState.unconfirmed && !accountRequired)) &&
            !recoveryHold &&
            navigator.top == null &&
            openMatches.none { it.isRunning }
    BackHandler(enabled = showWhatsNew) { seeWhatsNew() }
    CompositionLocalProvider(
        LocalSelfId provides self.id,
        LocalSelfAliases provides aliases,
        LocalArchivedPlayerIds provides archived,
        LocalTelemetryVisibility provides telemetryVisibility,
        LocalHealthConsent provides healthConsent,
        LocalHealthDialogOpen provides remember(model) { { open: Boolean -> model.healthDialogOpen = open } },
    ) {
        AdvantlyTheme(darkTheme = darkTheme) {
            Box(modifier.fillMaxSize().background(AdvantlyTheme.colors.bg)) {
                // Unter einer Vollbild-Ebene werden die Tabs nicht gezeichnet: nichts Verdecktes bleibt bedienbar
                // oder für Screenreader erreichbar.
                if ((navigator.top == null && !showWhatsNew) || accountRequired) {
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
                                accountRequired = accountRequired,
                                notifications = notifications,
                                pushTarget = pushTarget,
                                onPushTargetHandled = onPushTargetHandled,
                            )
                        }
                    }
                }
                when (val top = navigator.top.takeUnless { accountRequired }) {
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
                            // Zuschauer-Wahl lebt nur, solange der Assistent offen ist.
                            var visitors by remember { mutableStateOf(emptySet<String>()) }
                            WizardScreen(
                                model.wizard,
                                onClose = { navigator.pop() },
                                onStart = { model.finishWizard(visitors) },
                                busyMatch = model.watchMatch(),
                                friends = friends,
                                visitors = visitors,
                                onVisitorsChange = { visitors = it },
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
                                visitors = { snapshot ->
                                    authModel?.friends?.let { friends -> LiveVisitorsButton(friends, snapshot.matchId.value) }
                                },
                            )
                        }
                    }

                    is Overlay.Result -> {
                        val history by model.history.matches.collectAsState()
                        val revision = history.firstOrNull { it.matchId == top.matchId }
                        val record = remember(top.matchId, revision) { model.history.record(top.matchId) }
                        val health by model.health.records.collectAsState()
                        val pulseRevision by model.telemetry.healthRevision.collectAsState()
                        // Neu gelesen, sobald sich gespeicherte Serien ändern (nachgelieferte Serie der Uhr)
                        val pulse = remember(top.matchId, health.revision) { model.health.pulseTrack(top.matchId) }
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
                            canEditResult = remember(top.matchId, revision) { model.history.canCorrectResult(top.matchId) },
                            onCorrectResult = { sets, servers -> model.history.correctResult(top.matchId, sets, servers) },
                            onPlayAgain = { model.startMatch(it) },
                            pulse = pulse,
                            // shortcut: „überfällig“ nur beim Öffnen/bei neuen Serien bewertet; Ticker, falls nötig
                            pulseTransfer =
                                remember(top.matchId, pulseRevision, pulse, record) {
                                    record?.let {
                                        model.telemetry
                                            .pulseSetting(top.matchId)
                                            .transfer(pulse != null, it.summary.finishedAtMs, model.clock.nowMs())
                                    }
                                },
                            // Einwilligung „Pulsverlauf aufzeichnen“ steht im Profil unter „Puls & Schritte“
                            onEnablePulse = {
                                navigator.stack.clear()
                                navigator.tab = AppTab.Profile
                            },
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
                                    MatchVisitorsSection(friends, top.matchId.value)
                                }
                            },
                            pulseSettings = { PulseVisibilitySection(model.telemetry, top.matchId) },
                        )
                    }

                    is Overlay.Following -> {
                        Column(Modifier.fillMaxSize().safeContentPadding()) {
                            authModel?.friends?.let { friends ->
                                LiveFollowScreen(friends, top.matchId, onBack = { navigator.pop() }, clock = model.clock)
                            }
                        }
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
                if (showWhatsNew) WhatsNewSheet(model, versionName, whatsNewPoints, watchInstall, onDone = seeWhatsNew)
                if (!accountRequired) {
                    model.pendingScorerChoice?.let { ScorerDialog(model) }
                    model.pendingWatchTakeOver?.let { WatchTakeOverDialog(model, it.busy) }
                    // Plan 0.5.1 §5.4: nie über einer Vollbild-Ebene, „Was ist neu?“ oder einem anderen Dialog (Review N7)
                    val calm =
                        navigator.top == null && !showWhatsNew && !model.healthDialogOpen &&
                            model.pendingScorerChoice == null && model.pendingWatchTakeOver == null
                    if (healthToConfirm && calm) AdoptedHealthConsentDialog(model.telemetry)
                }
            }
        }
    }
}

/** Tabs, Mini-Scorebar und Tab-Leiste (nur ohne Vollbild-Ebene); mit [accountRequired] stattdessen der Konto-Fluss. */
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
    accountRequired: Boolean,
    notifications: NotificationSettingsPort?,
    pushTarget: PushTarget?,
    onPushTargetHandled: () -> Unit,
) {
    val active by model.live.active.collectAsState()
    val open by model.live.openMatches.collectAsState()
    val navigator = model.navigator
    var showSupport by remember(navigator.tab) { mutableStateOf(false) }
    val onSupport = if (supportModel != null) ({ showSupport = true }) else null
    val openAccount = {
        authModel?.refresh()
        navigator.tab = AppTab.Profile
    }
    if (accountRequired && authModel != null) {
        // Vollbild ohne Tab-Leiste und ohne Überspringen; Hilfe und ein laufendes Match bleiben erreichbar.
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                if (showSupport && supportModel != null) {
                    BackHandler { showSupport = false }
                    SupportScreen(supportModel, supportDiagnostics + mapOf("app_version" to versionName, "commit" to buildCommit)) {
                        showSupport = false
                    }
                } else {
                    AccountGate(authModel, googleCredential, onSupport, onShareExport, model.telemetry)
                }
            }
            active?.let { snapshot -> MiniScoreBar(snapshot, onClick = { navigator.push(Overlay.Live) }, more = open.size - 1) }
        }
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
                            live = {
                                authModel?.friends?.let { friends ->
                                    HomeLiveSection(friends, model.clock) { navigator.push(Overlay.Following(it)) }
                                }
                            },
                        )
                    }

                    AppTab.History -> {
                        HistoryScreen(model.history, onOpen = model::openResult)
                    }

                    AppTab.Friends -> {
                        val friends = authModel?.friends
                        if (friends == null) {
                            // Build ohne Backend: Freunde gibt es hier nicht, P1 bleibt unverändert.
                            ComingSoonScreen(AppTab.Friends)
                        } else {
                            FriendsScreen(
                                friends,
                                onOpenAccount = openAccount,
                                onShareText = onShareText,
                                onOpenLive = { navigator.push(Overlay.Following(it)) },
                                clock = model.clock,
                                notifications = notifications,
                                link = pushTarget,
                                onLinkHandled = onPushTargetHandled,
                            )
                        }
                    }

                    AppTab.Profile -> {
                        ProfileScreen(
                            versionName,
                            players = model.players,
                            onPlayerHistory = { navigator.push(Overlay.PlayerHistory(it)) },
                            telemetry = model.telemetry,
                            buildCommit = buildCommit,
                            buildTimeUtc = buildTimeUtc,
                            account = authModel,
                            google = googleCredential,
                            onFriends = { navigator.tab = AppTab.Friends },
                            onSupport = onSupport,
                            onRestorePrevious = onRestorePrevious,
                            onShareExport = onShareExport,
                            notifications = notifications,
                        )
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
    var pulse by remember { mutableStateOf<TelemetryVisibility?>(null) }
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
                pulseVisibility = pulse,
                onPulseVisibility = { pulse = it },
                onRecheck = model.live::refreshWatchStatus,
                busyMatch = busy,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { model.confirmScorerChoice(StartOptions(effective, workout, pulseVisibility = pulse)) },
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

/** Schritte einer Anmeldung (Neuinstallation, Abmeldung): kein „Was ist neu?“ danach. */
private val SIGN_IN_STEPS = setOf(AuthStep.AGE, AuthStep.PROVIDER, AuthStep.CODE)
