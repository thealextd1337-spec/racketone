package eu.advantly.ui.phone.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.presentation.live.LiveScoreRules
import eu.advantly.presentation.live.ScoreHint
import eu.advantly.presentation.match.LiveMatchSnapshot
import eu.advantly.presentation.match.WallClock
import eu.advantly.ui.phone.common.formatLabel
import eu.advantly.ui.phone.common.playerName
import eu.advantly.ui.phone.common.pointText
import eu.advantly.ui.phone.common.safeContentPadding
import eu.advantly.ui.phone.common.scoreSummary
import eu.advantly.ui.phone.common.sportColor
import eu.advantly.ui.phone.common.sportName
import eu.advantly.ui.phone.common.teamName
import eu.advantly.ui.phone.designsystem.AdvantlyIcons
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.designsystem.Radii
import eu.advantly.ui.phone.designsystem.Spacing
import eu.advantly.ui.phone.latency.InputClockSource
import eu.advantly.ui.phone.latency.formatMillis
import eu.advantly.ui.phone.platform.FrameStats
import eu.advantly.ui.phone.platform.KeepScreenOn
import eu.advantly.ui.phone.platform.displayRefreshRateHz
import eu.advantly.ui.phone.platform.monotonicNanos
import eu.advantly.ui.phone.platform.rememberFrameStats
import eu.advantly.ui.phone.platform.rememberHaptics
import eu.advantly.ui.phone.platform.rememberReduceMotion
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.a11y_point_for
import eu.advantly.ui.phone.resources.a11y_serving
import eu.advantly.ui.phone.resources.action_back
import eu.advantly.ui.phone.resources.action_more
import eu.advantly.ui.phone.resources.action_outdoor
import eu.advantly.ui.phone.resources.action_redo
import eu.advantly.ui.phone.resources.action_undo
import eu.advantly.ui.phone.resources.display_set_end
import eu.advantly.ui.phone.resources.latency_benchmark_running
import eu.advantly.ui.phone.resources.latency_counters
import eu.advantly.ui.phone.resources.latency_frames
import eu.advantly.ui.phone.resources.latency_input_to_draw
import eu.advantly.ui.phone.resources.latency_input_to_next_frame
import eu.advantly.ui.phone.resources.latency_no_samples
import eu.advantly.ui.phone.resources.latency_refresh_rate
import eu.advantly.ui.phone.resources.latency_source_event
import eu.advantly.ui.phone.resources.latency_source_handler
import eu.advantly.ui.phone.resources.latency_source_line
import eu.advantly.ui.phone.resources.latency_source_synthetic
import eu.advantly.ui.phone.resources.latency_stats
import eu.advantly.ui.phone.resources.latency_toggle
import eu.advantly.ui.phone.resources.live_no_match
import eu.advantly.ui.phone.resources.live_serve
import eu.advantly.ui.phone.resources.live_serve_line
import eu.advantly.ui.phone.resources.live_team_them
import eu.advantly.ui.phone.resources.live_team_them_name
import eu.advantly.ui.phone.resources.live_team_us
import eu.advantly.ui.phone.resources.live_team_us_name
import eu.advantly.ui.phone.resources.menu_auto_benchmark
import eu.advantly.ui.phone.resources.menu_correct_server
import eu.advantly.ui.phone.resources.menu_debounce
import eu.advantly.ui.phone.resources.menu_end_match
import eu.advantly.ui.phone.resources.menu_keep_screen_on
import eu.advantly.ui.phone.resources.menu_latency_overlay
import eu.advantly.ui.phone.resources.menu_outdoor_contrast
import eu.advantly.ui.phone.resources.menu_pause
import eu.advantly.ui.phone.resources.menu_reset_measurements
import eu.advantly.ui.phone.resources.menu_resume
import eu.advantly.ui.phone.resources.takeover_action
import eu.advantly.ui.phone.resources.undo_snackbar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/** Anteil der Bildschirmhöhe, den Kopf + Overlay + Satztabelle im Hochformat höchstens belegen. */
private const val INFO_MAX_FRACTION = 0.4f

/** Anteil der Bildschirmhöhe für Hinweise (Übernahme, abgelehnte Eingaben) im Hochformat. */
private const val NOTICES_MAX_FRACTION = 0.2f

/** Mindesthöhe beider Flächen zusammen – der Stand bleibt immer sichtbar. */
private val MIN_PANELS_HEIGHT = 120.dp

/** Sichtdauer der Undo-Snackbar. */
private const val UNDO_NOTICE_MILLIS = 4_000L

/** Offene Dialoge des Live-Screens, die der Nutzer selbst öffnet (Satz-/Matchende folgen aus dem Zustand). */
private enum class LiveDialog { EndMatch, CorrectServer }

/**
 * H6 Live-Score Handy (04 §4.1) über der echten Engine: schwarzer Screen, Tap-Flächen Wir (oben/Orange) und
 * Gegner (unten/Blau), Satztabelle, Hinweis-Pill, Aufschläger/Feld, Undo per Knopf/Wischgeste + Snackbar, Haptik,
 * Outdoor-Kontrast. Satzende-Frage (OPEN), Matchende (BEST_OF: Folge-Match), vorzeitiges Ende und
 * Aufschläger-Korrektur als Bottom-Sheets. Das Latenz-Overlay gibt es nur mit [debugTools].
 *
 * Zwei Modi (Single-Scorer, 02 §7.5): **Zähler/Director** wie oben; **Anzeige** (H6a), wenn ein anderes Gerät zählt –
 * gleiche Darstellung ohne Tap-Flächen, Undo und Match-Steuerung, deutlich als „Anzeige · Es zählt: …“ markiert, mit
 * „Stand vor X s“, Übernahmeangebot nach mehr als 30 s ohne Verbindung und bestätigtem „Zählen übernehmen“.
 *
 * @param onMinimize zurück zu den Tabs (Match läuft weiter, Mini-Scorebar)
 * @param onShowResult Ergebnisansicht des beendeten Matches
 * @param onBenchmarkNewMatch legt für den Auto-Benchmark ein frisches Match an (nur [debugTools])
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LiveScoreScreen(
    controller: LiveScoreController,
    modifier: Modifier = Modifier,
    debugTools: Boolean = false,
    onMinimize: () -> Unit = {},
    onShowResult: (MatchId) -> Unit = {},
    onBenchmarkNewMatch: (() -> Boolean)? = null,
    autoBenchmark: Boolean = false,
    onBenchmarkFinished: (String) -> Unit = {},
) {
    val haptics = rememberHaptics()
    DisposableEffect(haptics) {
        controller.haptics = haptics
        onDispose { }
    }
    LaunchedEffect(controller) { controller.port.active.collect(controller::sync) }
    KeepScreenOn(controller.keepScreenOn)
    val systemReduceMotion = rememberReduceMotion()
    val reduceMotion = systemReduceMotion || controller.outdoorContrast
    val frameStats = rememberFrameStats()
    DisposableEffect(frameStats) {
        controller.frameStats = frameStats
        onDispose { controller.frameStats = null }
    }
    val scope = rememberCoroutineScope()
    val tracker = controller.tracker

    if (autoBenchmark && onBenchmarkNewMatch != null) {
        LaunchedEffect(Unit) {
            delay(1_500)
            onBenchmarkFinished(controller.runAutoBenchmark(onBenchmarkNewMatch).toLogLine())
        }
    }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(AdvantlyTheme.colors.liveBackground)
                // Latenzmessung: Dieser Draw-Pass zeichnet den neuen Zustand auf; gemessen wird bis zum Beginn des
                // nächsten Frame-Callbacks – kein Nachweis, wann der Frame auf dem Display erscheint (LatencyTracker).
                .drawWithContent {
                    drawContent()
                    if (tracker.onDrawn(controller.generation, monotonicNanos())) {
                        scope.launch { withFrameNanos { tracker.onNextFrame(monotonicNanos()) } }
                    }
                }.safeContentPadding(),
    ) {
        val snapshot = controller.snapshot
        if (snapshot == null) {
            NoMatch(onMinimize)
            return@Box
        }
        var dialog by remember { mutableStateOf<LiveDialog?>(null) }
        var takeOverDialog by remember { mutableStateOf(false) }
        var rejectedSheet by remember { mutableStateOf(false) }
        val display = !snapshot.isScorer
        LaunchedEffect(display) { if (!display) takeOverDialog = false }
        val now = rememberNow(controller.monotonic, ticking = display)
        val status = snapshot.state.status
        val running = status == MatchStatus.IN_PROGRESS || status == MatchStatus.PAUSED
        val userDialog = dialog.takeIf { running && !display }
        // Satzende-Frage nur am Zähler (04 §3.3); die Anzeige zeigt stattdessen einen Hinweis über den Flächen
        val sheet = status == MatchStatus.FINISHED || (status == MatchStatus.AWAITING_DECISION && !display)
        val modal = sheet || userDialog != null || rejectedSheet
        // System-Zurück schließt zuerst einen vom Nutzer geöffneten Dialog (nicht den Live-Screen)
        BackHandler(enabled = userDialog != null || rejectedSheet) {
            dialog = null
            rejectedSheet = false
        }
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = Spacing.s)
                // Modales Sheet: Hintergrund für Screenreader ausblenden und Fokus nicht hineinlassen
                .then(
                    if (modal) {
                        Modifier
                            .clearAndSetSemantics {
                            }.focusProperties { onEnter = { cancelFocusChange() } }
                            .focusGroup()
                    } else {
                        Modifier
                    },
                ),
        ) {
            val landscape = maxWidth > maxHeight
            val infoMaxHeight = maxHeight * INFO_MAX_FRACTION
            val noticesMaxHeight = maxHeight * NOTICES_MAX_FRACTION
            val info: @Composable (Modifier) -> Unit = { infoModifier ->
                Column(infoModifier) {
                    Header(controller, snapshot, now, onMinimize)
                    LiveWorkoutTelemetry(snapshot.matchId, snapshot.setup.meta.watchWorkout, controller.outdoorContrast)
                    if (display) {
                        // Übernahmeangebot ersetzt den Anzeige-Banner (gleicher Platz) – kostet den Flächen keine Höhe
                        val staleOffer = snapshot.sync.offersTakeOver(now) && snapshot.state.status != MatchStatus.FINISHED
                        if (snapshot.waitingForScorer) {
                            // Match an die Uhr gesendet, sie hat sich noch nicht gemeldet (Feldtest W1-I)
                            WaitingForScorer({ controller.takeOverScoring() }, Modifier.padding(top = Spacing.xs))
                        } else if (staleOffer) {
                            StaleHint(
                                snapshot,
                                snapshot.sync.staleForMs(now),
                                { takeOverDialog = true },
                                Modifier.padding(top = Spacing.xs),
                            )
                        } else {
                            DisplayBanner(snapshot, controller.outdoorContrast, Modifier.padding(top = Spacing.xs))
                        }
                    }
                    if (debugTools && controller.showLatencyOverlay) {
                        LatencyOverlay(controller, frameStats, Modifier.padding(top = 6.dp))
                    }
                    SetTable(
                        snapshot = snapshot,
                        header = formatLabel(snapshot.setup.format),
                        elapsed = { ElapsedTime(snapshot, controller.clock, controller.outdoorContrast) },
                        outdoor = controller.outdoorContrast,
                        modifier = Modifier.padding(top = Spacing.s),
                    )
                }
            }
            val footer: @Composable (Modifier) -> Unit = { footerModifier ->
                if (display) {
                    DisplayFooter(controller, snapshot, onTakeOver = { takeOverDialog = true }, footerModifier)
                } else {
                    Footer(controller, snapshot, debugTools, onBenchmarkNewMatch, { dialog = it }, footerModifier)
                }
            }
            val panels: @Composable (Boolean, Modifier) -> Unit = { landscapePanels, panelsModifier ->
                PanelsWithSnackbar(controller, snapshot, reduceMotion, landscapePanels, panelsModifier)
            }
            // Hinweise haben einen eigenen Platz zwischen Satztabelle und Flächen – sie verdecken nie den Stand
            val notices: @Composable (Modifier) -> Unit = { noticesModifier ->
                Notices(controller, snapshot, { rejectedSheet = true }, noticesModifier)
            }
            if (landscape) {
                // Querformat: Info + Fußleiste links (scrollbar, damit bei großer Schrift nichts abgeschnitten wird),
                // Tap-Flächen rechts nebeneinander.
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    Column(Modifier.weight(0.42f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                        info(Modifier.fillMaxWidth())
                        notices(Modifier.padding(top = Spacing.s))
                        footer(Modifier.padding(top = Spacing.s))
                    }
                    panels(true, Modifier.weight(0.58f).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    // Info (Kopf, Overlay, Satztabelle) höchstens 40 % der Höhe und scrollbar: Bei 200 % Schrift und
                    // vollem Overlay behalten die Tap-Flächen so ihren Platz.
                    info(Modifier.fillMaxWidth().heightIn(max = infoMaxHeight).verticalScroll(rememberScrollState()))
                    // Hinweise höchstens 20 % (scrollbar), damit den Flächen auch bei 200 % Schrift Platz bleibt
                    notices(Modifier.padding(top = Spacing.s).heightIn(max = noticesMaxHeight).verticalScroll(rememberScrollState()))
                    panels(false, Modifier.weight(1f).padding(top = Spacing.s).heightIn(min = MIN_PANELS_HEIGHT))
                    footer(Modifier.padding(top = Spacing.s))
                }
            }
        }
        // Satzende (OPEN, nur Zähler) und Matchende ergeben sich aus dem Zustand – kein Auto-Weiter (04 §4.3.3).
        when {
            status == MatchStatus.AWAITING_DECISION && !display -> {
                SetEndSheet(controller, snapshot)
            }

            status == MatchStatus.FINISHED -> {
                MatchEndSheet(controller, snapshot, onShowResult = {
                    controller.port.dismiss()
                    onShowResult(snapshot.matchId)
                })
            }

            display -> {
                Unit
            }

            else -> {
                when (dialog) {
                    LiveDialog.EndMatch -> EndMatchSheet(controller, snapshot, onClose = { dialog = null })
                    LiveDialog.CorrectServer -> CorrectServerSheet(controller, snapshot, onClose = { dialog = null })
                    null -> Unit
                }
            }
        }
        if (rejectedSheet) {
            RejectedInputsSheet(snapshot, remember(snapshot.rejectedByRoleCount) { controller.port.rejectedInputs() }, onClose = {
                rejectedSheet = false
                controller.acknowledgeRejected()
            })
        }
        if (takeOverDialog && display) {
            TakeOverDialog(
                snapshot = snapshot,
                onConfirm = {
                    takeOverDialog = false
                    controller.takeOverScoring()
                },
                onDismiss = { takeOverDialog = false },
            )
        }
    }
}

@Composable
private fun NoMatch(onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(Spacing.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(Res.string.live_no_match), style = AdvantlyTheme.typography.title, color = AdvantlyTheme.colors.text)
        SheetButton(stringResource(Res.string.action_back), primary = true, onClick = onBack, modifier = Modifier.padding(top = Spacing.l))
    }
}

/**
 * Hinweise mit eigenem Platz im Layout (Review W1-I: nie über dem Stand): „N Eingaben nicht übernommen“ und auf
 * Anzeige-Geräten „Zähler entscheidet“ am Satzende. Das Übernahmeangebot sitzt im Info-Bereich (statt Anzeige-Banner).
 */
@Composable
private fun Notices(
    controller: LiveScoreController,
    snapshot: LiveMatchSnapshot,
    onViewRejected: () -> Unit,
    modifier: Modifier,
) {
    val display = !snapshot.isScorer
    val rejected = controller.rejectedNotice
    val setEnd = display && snapshot.state.status == MatchStatus.AWAITING_DECISION
    if (rejected == null && !setEnd) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        rejected?.let { count -> RejectedNotice(count, onView = onViewRejected, onDismiss = controller::acknowledgeRejected) }
        if (setEnd) {
            Text(
                stringResource(Res.string.display_set_end, snapshot.state.sets.size),
                style = AdvantlyTheme.typography.label,
                color = AdvantlyTheme.colors.text,
                modifier =
                    Modifier
                        .testTag("display_set_end")
                        .fillMaxWidth()
                        .background(AdvantlyTheme.colors.surface2, Radii.cardShape)
                        .padding(Spacing.m),
            )
        }
    }
}

/** Tap-Flächen; die Undo-Snackbar (nur am Zähler) liegt unten über der Gegner-Fläche (wächst nicht in die Fußleiste). */
@Composable
private fun PanelsWithSnackbar(
    controller: LiveScoreController,
    snapshot: LiveMatchSnapshot,
    reduceMotion: Boolean,
    landscape: Boolean,
    modifier: Modifier,
) {
    Box(modifier) {
        Panels(controller, snapshot, reduceMotion, landscape, Modifier.fillMaxSize())
        controller.absorbedNotice?.let { id ->
            AbsorbedNotice(id, onTimeout = { controller.dismissAbsorbedNotice(id) }, Modifier.align(Alignment.Center))
        }
        // Nach Rollenverlust kein „Wiederholen“ mehr (Review W1-I #5)
        controller.undoNotice?.takeIf { snapshot.isScorer }?.let { notice ->
            UndoSnackbar(
                notice = notice,
                onRedo = controller::redo,
                onTimeout = { controller.dismissUndoNotice(notice.id) },
                modifier = Modifier.align(Alignment.BottomCenter).padding(Spacing.s),
            )
        }
    }
}

@Composable
private fun Header(
    controller: LiveScoreController,
    snapshot: LiveMatchSnapshot,
    nowMs: Long,
    onMinimize: () -> Unit,
) {
    val outdoor = controller.outdoorContrast
    val sport = snapshot.setup.sport
    Row(
        Modifier.fillMaxWidth().heightIn(min = Spacing.minTouchTarget),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        val back = stringResource(Res.string.action_back)
        Box(
            Modifier
                .testTag("live_minimize")
                .size(Spacing.minTouchTarget)
                .clickable(role = Role.Button, onClickLabel = back, onClick = onMinimize)
                .semantics { contentDescription = back },
            contentAlignment = Alignment.Center,
        ) {
            Icon(AdvantlyIcons.ChevronDown, contentDescription = null, tint = if (outdoor) Color.White else AdvantlyTheme.colors.text)
        }
        SportChip(sport = sport, text = stringResource(sportName(sport)), outdoor = outdoor, modifier = Modifier.testTag("live_sport_chip"))
        Spacer(Modifier.weight(1f))
        SyncPill(snapshot, nowMs, outdoor)
    }
}

/** Matchdauer h:mm:ss seit der Genesis (bei beendetem Match eingefroren). */
@Composable
private fun ElapsedTime(
    snapshot: LiveMatchSnapshot,
    clock: WallClock,
    outdoor: Boolean,
) {
    var now by remember { mutableLongStateOf(clock.nowMs()) }
    LaunchedEffect(snapshot.finishedAtMs) {
        while (snapshot.finishedAtMs == null) {
            now = clock.nowMs()
            delay(1_000)
        }
    }
    val end = snapshot.finishedAtMs ?: now
    val seconds = ((end - snapshot.startedAtMs) / 1_000).coerceAtLeast(0)
    val h = seconds / 3600
    val m = (seconds / 60) % 60
    val s = seconds % 60
    Text(
        text = "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}",
        style = AdvantlyTheme.typography.scoreS.copy(fontSize = 15.sp),
        color = if (outdoor) Color.White else AdvantlyTheme.colors.text2,
    )
}

@Composable
private fun Panels(
    controller: LiveScoreController,
    snapshot: LiveMatchSnapshot,
    reduceMotion: Boolean,
    landscape: Boolean,
    modifier: Modifier,
) {
    val state = snapshot.state
    val setup = snapshot.setup
    val sport = setup.sport
    val hint = LiveScoreRules.hint(snapshot)
    val usName = stringResource(Res.string.live_team_us_name)
    val themName = stringResource(Res.string.live_team_them_name)
    val points = Side.entries.associateWith { pointText(LiveScoreRules.pointLabel(state, it)) }
    val serve = state.serve
    val serverName = serve?.let { playerName(setup.player(it.player)) }
    val serving = serve?.let { stringResource(Res.string.a11y_serving, if (it.side == Side.A) usName else themName) }.orEmpty()
    val undoLabel = stringResource(Res.string.action_undo)
    val serveWord = stringResource(Res.string.live_serve)
    val doubles = setup.lineup.a.size > 1
    val serveDetail =
        listOfNotNull(
            serveWord,
            serverName?.takeIf { doubles },
            serve?.servesRemaining?.let { "●".repeat(it) },
        ).joinToString(" · ")
    val serveCourt = courtText(serve?.court)?.takeIf { sport == Sport.BADMINTON || sport == Sport.PICKLEBALL }

    @Composable
    fun content(side: Side): ScorePanelContent {
        val names = teamName(setup, side)
        return ScorePanelContent(
            side = side,
            teamLabel = stringResource(if (side == Side.A) Res.string.live_team_us else Res.string.live_team_them),
            playerName = names,
            pointText = points.getValue(side),
            serving = serve?.side == side,
            serveLabel = serveDetail,
            serveCourt = serveCourt,
            serveColor = sportColor(sport),
            a11yDescription =
                stringResource(
                    Res.string.a11y_point_for,
                    if (side == Side.A) usName else themName,
                    names,
                    points.getValue(Side.A),
                    points.getValue(Side.B),
                ) + " " + serving,
            undoLabel = undoLabel,
        )
    }
    val usContent = content(Side.A)
    val themContent = content(Side.B)
    val enabled = state.status == MatchStatus.IN_PROGRESS || state.status == MatchStatus.PAUSED
    val interactive = snapshot.isScorer

    @Composable
    fun Panel(
        panelContent: ScorePanelContent,
        panelModifier: Modifier,
    ) {
        val side = panelContent.side
        ScorePanel(
            content = panelContent,
            direction = controller.lastDirection,
            flashKey = if (controller.flashSide == side) controller.generation else 0,
            outdoor = controller.outdoorContrast,
            reduceMotion = reduceMotion,
            enabled = enabled,
            onTap = { controller.onTap(side, it) },
            onSwipeDown = { controller.undo(it) },
            modifier = panelModifier.onGloballyPositioned { controller.panelBounds[side] = it.boundsInWindow() },
            interactive = interactive,
        )
    }

    val band: @Composable () -> Unit = { HintBand(snapshot, hint, serverName, controller.outdoorContrast, reduceMotion, landscape) }
    // Oben/Unten im Hochformat, nebeneinander im Querformat (04 §4.1 H6) – richtet sich nach dem Bildschirm,
    // nicht nach der Restfläche (bei 200 % Schrift wäre die sonst „quer“).
    Box(modifier) {
        if (landscape) {
            Column(Modifier.fillMaxSize()) {
                band()
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    Panel(usContent, Modifier.weight(1f).fillMaxSize())
                    Panel(themContent, Modifier.weight(1f).fillMaxSize())
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Panel(usContent, Modifier.weight(1f).fillMaxWidth())
                band()
                Panel(themContent, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

/**
 * Mittelband zwischen den Flächen (nicht scorend, 04 §4.3.3): Hinweis-Pill, darunter im Doppel „Aufschläger →
 * Rückschläger“ (02 §8.4) bzw. bei Pickleball die Ansage „4-2-1“.
 */
@Composable
private fun HintBand(
    snapshot: LiveMatchSnapshot,
    hint: ScoreHint?,
    serverName: String?,
    outdoor: Boolean,
    reduceMotion: Boolean,
    landscape: Boolean,
) {
    val setup = snapshot.setup
    val serve = snapshot.state.serve
    val receiverName = serve?.receiver?.let { playerName(setup.player(it)) }
    val line =
        when {
            setup.sport == Sport.PICKLEBALL -> {
                snapshot.state.scoreCall
            }

            setup.lineup.a.size > 1 && serverName != null && receiverName != null -> {
                stringResource(Res.string.live_serve_line, serverName, receiverName)
            }

            else -> {
                null
            }
        }
    Column(
        Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(vertical = Spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (hint != null) HintPill(hint, setup.sport, outdoor, reduceMotion, landscape)
        if (line != null && snapshot.state.status != MatchStatus.FINISHED) {
            Text(
                text = line,
                style = AdvantlyTheme.typography.label.copy(fontSize = 13.sp),
                color = if (outdoor) Color.White else AdvantlyTheme.colors.text2,
                modifier = Modifier.testTag("serve_line").padding(top = 2.dp),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun Footer(
    controller: LiveScoreController,
    snapshot: LiveMatchSnapshot,
    debugTools: Boolean,
    onBenchmarkNewMatch: (() -> Boolean)?,
    openDialog: (LiveDialog) -> Unit,
    modifier: Modifier,
) {
    val outdoor = controller.outdoorContrast
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp).height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        FooterButton(
            icon = AdvantlyIcons.Undo,
            label = stringResource(Res.string.action_undo),
            onClick = { controller.undo() },
            outdoor = outdoor,
            weight = 1.5f,
            enabled = snapshot.canUndo,
            emphasized = true,
            modifier = Modifier.testTag("undo_button"),
        )
        FooterButton(
            icon = AdvantlyIcons.Sun,
            label = stringResource(Res.string.action_outdoor),
            onClick = { controller.outdoorContrast = !controller.outdoorContrast },
            outdoor = outdoor,
            selected = outdoor,
            modifier = Modifier.testTag("outdoor_toggle"),
        )
        Box(Modifier.weight(1f).fillMaxSize()) {
            Row(Modifier.fillMaxSize()) {
                FooterButton(
                    icon = AdvantlyIcons.MoreVert,
                    label = stringResource(Res.string.action_more),
                    onClick = { menuOpen = true },
                    outdoor = outdoor,
                    modifier = Modifier.testTag("more_button"),
                )
            }
            MoreMenu(
                controller,
                snapshot,
                debugTools,
                onBenchmarkNewMatch,
                openDialog,
                expanded = menuOpen,
                onDismiss = { menuOpen = false },
            )
        }
    }
}

/** Fußleiste des Anzeige-Geräts: „Zählen übernehmen“ (mit Bestätigung) und Outdoor-Kontrast – kein Undo/Mehr. */
@Composable
private fun DisplayFooter(
    controller: LiveScoreController,
    snapshot: LiveMatchSnapshot,
    onTakeOver: () -> Unit,
    modifier: Modifier,
) {
    val outdoor = controller.outdoorContrast
    val finished = snapshot.state.status == MatchStatus.FINISHED
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp).height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        FooterButton(
            icon = AdvantlyIcons.Replay,
            label = stringResource(Res.string.takeover_action),
            onClick = onTakeOver,
            outdoor = outdoor,
            weight = 2f,
            enabled = !finished,
            emphasized = true,
            modifier = Modifier.testTag("takeover_button"),
        )
        FooterButton(
            icon = AdvantlyIcons.Sun,
            label = stringResource(Res.string.action_outdoor),
            onClick = { controller.outdoorContrast = !controller.outdoorContrast },
            outdoor = outdoor,
            selected = outdoor,
            modifier = Modifier.testTag("outdoor_toggle"),
        )
    }
}

@Composable
private fun MoreMenu(
    controller: LiveScoreController,
    snapshot: LiveMatchSnapshot,
    debugTools: Boolean,
    onBenchmarkNewMatch: (() -> Boolean)?,
    openDialog: (LiveDialog) -> Unit,
    expanded: Boolean,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val status = snapshot.state.status
    val running = status == MatchStatus.IN_PROGRESS || status == MatchStatus.PAUSED

    @Composable
    fun Item(
        text: StringResource,
        checked: Boolean? = null,
        enabled: Boolean = true,
        tag: String? = null,
        onClick: () -> Unit,
    ) {
        DropdownMenuItem(
            text = { Text(stringResource(text)) },
            enabled = enabled,
            modifier = if (tag != null) Modifier.testTag(tag) else Modifier,
            onClick = {
                onClick()
                if (checked == null) onDismiss()
            },
            trailingIcon =
                checked?.let {
                    {
                        Icon(
                            AdvantlyIcons.Check,
                            contentDescription = null,
                            tint = if (it) AdvantlyTheme.colors.brand else Color.Transparent,
                        )
                    }
                },
        )
    }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (status == MatchStatus.PAUSED) {
            Item(Res.string.menu_resume, tag = "menu_resume") { controller.resume() }
        } else {
            Item(Res.string.menu_pause, enabled = status == MatchStatus.IN_PROGRESS, tag = "menu_pause") { controller.pause() }
        }
        Item(Res.string.menu_correct_server, enabled = running, tag = "menu_correct_server") { openDialog(LiveDialog.CorrectServer) }
        Item(Res.string.action_redo, enabled = snapshot.canRedo, tag = "menu_redo") { controller.redo() }
        Item(Res.string.menu_end_match, enabled = running, tag = "menu_end_match") { openDialog(LiveDialog.EndMatch) }
        HorizontalDivider()
        Item(Res.string.menu_outdoor_contrast, controller.outdoorContrast) { controller.outdoorContrast = !controller.outdoorContrast }
        Item(Res.string.menu_keep_screen_on, controller.keepScreenOn) { controller.keepScreenOn = !controller.keepScreenOn }
        if (debugTools) {
            HorizontalDivider()
            Item(Res.string.menu_latency_overlay, controller.showLatencyOverlay) {
                controller.showLatencyOverlay = !controller.showLatencyOverlay
            }
            Item(Res.string.menu_debounce, controller.debounceEnabled) { controller.debounceEnabled = !controller.debounceEnabled }
            if (onBenchmarkNewMatch != null) {
                Item(Res.string.menu_auto_benchmark) {
                    if (!controller.benchmarkRunning) {
                        scope.launch { println(controller.runAutoBenchmark(onBenchmarkNewMatch).toLogLine()) }
                    }
                }
            }
            Item(Res.string.menu_reset_measurements) { controller.resetMeasurements() }
        }
    }
}

/** Undo-Snackbar „Rückgängig: 30:15 → 15:15“ + „Wiederholen“ (04 §5.6). */
@Composable
private fun UndoSnackbar(
    notice: UndoNotice,
    onRedo: () -> Unit,
    onTimeout: () -> Unit,
    modifier: Modifier,
) {
    LaunchedEffect(notice.id) {
        delay(UNDO_NOTICE_MILLIS)
        onTimeout()
    }
    val colors = AdvantlyTheme.colors
    val withGames =
        notice.before.current?.games != notice.after.current?.games ||
            notice.before.sets != notice.after.sets
    val text =
        stringResource(
            Res.string.undo_snackbar,
            scoreSummary(notice.before, withGames),
            scoreSummary(notice.after, withGames),
        )
    Row(
        modifier =
            modifier
                .testTag("undo_snackbar")
                .fillMaxWidth()
                .background(colors.surface2, Radii.cardShape)
                .border(1.dp, colors.line, Radii.cardShape)
                .padding(start = Spacing.l, end = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = AdvantlyTheme.typography.label,
            color = colors.text,
            modifier = Modifier.weight(1f).padding(vertical = Spacing.m),
        )
        Box(
            modifier =
                Modifier
                    .testTag("redo_button")
                    .height(Spacing.minTouchTarget)
                    .clickable(role = Role.Button, onClick = onRedo)
                    .padding(horizontal = Spacing.m),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(Res.string.action_redo),
                style = AdvantlyTheme.typography.label.copy(fontWeight = FontWeight.Bold),
                color = colors.brand,
            )
        }
    }
}

/**
 * Debug-Overlay: Eingabe → nächster Frame-Beginn nach Draw und Eingabe → Draw (p50/p95/max) **getrennt nach
 * Zeitquelle**, Tap-Zähler, Bildrate, Frame-Zeiten (Android). Handler-Fallback-Werte zählen nicht für G1.
 */
@Composable
private fun LatencyOverlay(
    controller: LiveScoreController,
    frameStats: FrameStats?,
    modifier: Modifier,
) {
    val tracker = controller.tracker

    // Neu zeichnen, sobald eine Messung dazukommt
    @Suppress("UNUSED_VARIABLE")
    val version = tracker.version
    val colors = AdvantlyTheme.colors
    val style = AdvantlyTheme.typography.caption.copy(fontSize = 11.sp, lineHeight = 14.sp)
    val refresh = displayRefreshRateHz()
    var expanded by rememberSaveable { mutableStateOf(true) }
    Column(
        modifier =
            modifier
                .testTag("latency_overlay")
                .fillMaxWidth()
                .clickable(onClickLabel = stringResource(Res.string.latency_toggle)) { expanded = !expanded }
                .background(Color(0xFF0E1013), Radii.chipShape)
                .border(1.dp, colors.line, Radii.chipShape)
                .padding(horizontal = Spacing.s, vertical = 6.dp),
    ) {
        if (controller.benchmarkRunning) {
            Text(stringResource(Res.string.latency_benchmark_running), style = style, color = colors.hint)
        }
        Text(
            text = stringResource(Res.string.latency_input_to_next_frame),
            style = style.copy(fontWeight = FontWeight.Bold),
            color = colors.text,
        )
        val summaries = tracker.summaries().let { if (expanded) it else it.take(1) }
        if (summaries.isEmpty()) {
            Text(stringResource(Res.string.latency_no_samples), style = style, color = colors.text2)
        }
        summaries.forEach { summary ->
            val label =
                stringResource(
                    when (summary.source) {
                        InputClockSource.InputEvent -> Res.string.latency_source_event
                        InputClockSource.Handler -> Res.string.latency_source_handler
                        InputClockSource.Synthetic -> Res.string.latency_source_synthetic
                    },
                )
            val primary = summary.source != InputClockSource.Handler
            Text(
                text =
                    stringResource(
                        Res.string.latency_source_line,
                        label,
                        stringResource(
                            Res.string.latency_stats,
                            formatMillis(summary.inputToNextFrameP50),
                            formatMillis(summary.inputToNextFrameP95),
                            formatMillis(summary.inputToNextFrameMax),
                            summary.samples.toString(),
                        ),
                    ),
                style = style,
                color = if (primary) colors.hint else colors.text2,
                modifier = Modifier.testTag("latency_${summary.source.name}"),
            )
            if (primary && expanded) {
                OverlayLine(
                    stringResource(Res.string.latency_input_to_draw),
                    "p50 ${formatMillis(summary.inputToDrawP50)} · p95 ${formatMillis(summary.inputToDrawP95)} ms",
                    style,
                    colors.text2,
                    colors.text2,
                )
            }
        }
        if (!expanded) return@Column
        val counters =
            stringResource(
                Res.string.latency_counters,
                controller.rawTaps.toString(),
                controller.countedPoints.toString(),
                controller.debouncedTaps.toString(),
            )
        val hz = refresh?.let { stringResource(Res.string.latency_refresh_rate, it.roundToInt().toString()) }
        Text(
            text = listOfNotNull(counters, hz).joinToString(" · "),
            style = style,
            color = colors.text2,
            modifier = Modifier.testTag("latency_counters"),
        )
        frameStats?.snapshot()?.takeIf { it.frames > 0 }?.let { snap ->
            Text(
                text =
                    stringResource(
                        Res.string.latency_frames,
                        formatMillis(snap.p95Millis),
                        snap.jankFrames.toString(),
                        snap.frames.toString(),
                    ),
                style = style,
                color = colors.text2,
            )
        }
    }
}

@Composable
private fun OverlayLine(
    title: String,
    value: String,
    style: TextStyle,
    titleColor: Color,
    valueColor: Color,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text(title, style = style.copy(fontWeight = FontWeight.Bold), color = titleColor)
        Text(value, style = style, color = valueColor, textAlign = TextAlign.Start)
    }
}
