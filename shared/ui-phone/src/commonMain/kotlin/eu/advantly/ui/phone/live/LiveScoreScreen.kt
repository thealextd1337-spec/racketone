package eu.advantly.ui.phone.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.advantly.presentation.live.DemoScenario
import eu.advantly.presentation.live.FakeTennis
import eu.advantly.presentation.live.ScoreHint
import eu.advantly.presentation.live.Team
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
import eu.advantly.ui.phone.resources.action_more
import eu.advantly.ui.phone.resources.action_outdoor
import eu.advantly.ui.phone.resources.action_redo
import eu.advantly.ui.phone.resources.action_undo
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
import eu.advantly.ui.phone.resources.live_format_best_of_3
import eu.advantly.ui.phone.resources.live_serve
import eu.advantly.ui.phone.resources.live_sport_tennis
import eu.advantly.ui.phone.resources.live_team_them
import eu.advantly.ui.phone.resources.live_team_them_name
import eu.advantly.ui.phone.resources.live_team_us
import eu.advantly.ui.phone.resources.live_team_us_name
import eu.advantly.ui.phone.resources.menu_auto_benchmark
import eu.advantly.ui.phone.resources.menu_debounce
import eu.advantly.ui.phone.resources.menu_demo_breakpoint
import eu.advantly.ui.phone.resources.menu_demo_deuce
import eu.advantly.ui.phone.resources.menu_demo_matchpoint
import eu.advantly.ui.phone.resources.menu_demo_setpoint
import eu.advantly.ui.phone.resources.menu_demo_tiebreak
import eu.advantly.ui.phone.resources.menu_keep_screen_on
import eu.advantly.ui.phone.resources.menu_latency_overlay
import eu.advantly.ui.phone.resources.menu_new_match
import eu.advantly.ui.phone.resources.menu_outdoor_contrast
import eu.advantly.ui.phone.resources.menu_reset_measurements
import eu.advantly.ui.phone.resources.undo_snackbar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/** Spieler-Namen des Fake-Matches (Daten, keine UI-Texte). */
private const val FAKE_NAME_US = "Alex"
private const val FAKE_NAME_THEM = "Jonas"

/** Anteil der Bildschirmhöhe, den Kopf + Overlay + Satztabelle im Hochformat höchstens belegen. */
private const val INFO_MAX_FRACTION = 0.4f

/** Sichtdauer der Undo-Snackbar. */
private const val UNDO_NOTICE_MILLIS = 4_000L

/**
 * H6 Live-Score Handy (04 §4.1) – Spike S4. Schwarzer Live-Screen, zwei Tap-Flächen (Wir oben/Orange,
 * Gegner unten/Blau), Satztabelle, Hinweis-Pill, Undo per Knopf/Wischgeste + Snackbar, Haptik, Outdoor-Kontrast,
 * Latenz-Overlay (Eingabe → nächster Frame-Beginn nach Draw). Zustand liegt im [LiveScoreViewModel]
 * (übersteht Rotation und Prozesstod), siehe [rememberLiveScoreController].
 *
 * [onBenchmarkFinished] erhält die Logzeile eines Auto-Benchmarks (Menü oder [autoBenchmark] beim Start).
 */
@Composable
fun LiveScoreScreen(
    modifier: Modifier = Modifier,
    controller: LiveScoreController = rememberLiveScoreController(),
    autoBenchmark: Boolean = false,
    onBenchmarkFinished: (String) -> Unit = {},
) {
    val haptics = rememberHaptics()
    DisposableEffect(haptics) {
        controller.haptics = haptics
        onDispose { }
    }
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

    if (autoBenchmark) {
        LaunchedEffect(Unit) {
            delay(1_500)
            onBenchmarkFinished(controller.runAutoBenchmark().toLogLine())
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
                }.windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = Spacing.s)) {
            val landscape = maxWidth > maxHeight
            val infoMaxHeight = maxHeight * INFO_MAX_FRACTION
            val info: @Composable (Modifier) -> Unit = { infoModifier ->
                Column(infoModifier) {
                    Header(controller)
                    if (controller.showLatencyOverlay) {
                        LatencyOverlay(controller, frameStats, Modifier.padding(top = 6.dp))
                    }
                    SetTable(
                        state = controller.state,
                        usName = FAKE_NAME_US,
                        themName = FAKE_NAME_THEM,
                        header = stringResource(Res.string.live_format_best_of_3),
                        elapsed = { ElapsedTime(controller.outdoorContrast) },
                        outdoor = controller.outdoorContrast,
                        modifier = Modifier.padding(top = Spacing.s),
                    )
                }
            }
            if (landscape) {
                // Querformat: Info + Fußleiste links (scrollbar, damit bei großer Schrift nichts abgeschnitten wird),
                // Tap-Flächen rechts nebeneinander.
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    Column(Modifier.weight(0.42f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                        info(Modifier.fillMaxWidth())
                        Footer(controller, Modifier.padding(top = Spacing.s))
                    }
                    PanelsWithSnackbar(controller, reduceMotion, landscape = true, Modifier.weight(0.58f).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    // Info (Kopf, Overlay, Satztabelle) höchstens 40 % der Höhe und scrollbar: Bei 200 % Schrift und
                    // vollem Overlay behalten die Tap-Flächen so ihren Platz.
                    info(Modifier.fillMaxWidth().heightIn(max = infoMaxHeight).verticalScroll(rememberScrollState()))
                    PanelsWithSnackbar(controller, reduceMotion, landscape = false, Modifier.weight(1f).padding(top = Spacing.s))
                    Footer(controller, Modifier.padding(top = Spacing.s))
                }
            }
        }
    }
}

/** Tap-Flächen; die Undo-Snackbar liegt unten über der Gegner-Fläche (wächst nicht in die Fußleiste). */
@Composable
private fun PanelsWithSnackbar(
    controller: LiveScoreController,
    reduceMotion: Boolean,
    landscape: Boolean,
    modifier: Modifier,
) {
    Box(modifier) {
        Panels(controller, reduceMotion, landscape, Modifier.fillMaxSize())
        controller.undoNotice?.let { notice ->
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
private fun Header(controller: LiveScoreController) {
    Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
        SportChip(text = stringResource(Res.string.live_sport_tennis), outdoor = controller.outdoorContrast)
    }
}

@Composable
private fun ElapsedTime(outdoor: Boolean) {
    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        val start = monotonicNanos()
        while (true) {
            delay(1_000)
            seconds = ((monotonicNanos() - start) / 1_000_000_000L).toInt()
        }
    }
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
    reduceMotion: Boolean,
    landscape: Boolean,
    modifier: Modifier,
) {
    val state = controller.state
    val hint = FakeTennis.hint(state)
    val usName = stringResource(Res.string.live_team_us_name)
    val themName = stringResource(Res.string.live_team_them_name)
    val pointUs = pointText(FakeTennis.pointLabel(state, Team.Us))
    val pointThem = pointText(FakeTennis.pointLabel(state, Team.Them))
    val serving = stringResource(Res.string.a11y_serving, if (state.server == Team.Us) usName else themName)
    val undoLabel = stringResource(Res.string.action_undo)
    val serveLabel = stringResource(Res.string.live_serve)

    val usContent =
        ScorePanelContent(
            team = Team.Us,
            teamLabel = stringResource(Res.string.live_team_us),
            playerName = FAKE_NAME_US,
            pointText = pointUs,
            serving = state.server == Team.Us && !state.isFinished,
            serveLabel = serveLabel,
            a11yDescription = stringResource(Res.string.a11y_point_for, usName, FAKE_NAME_US, pointUs, pointThem) + " " + serving,
            undoLabel = undoLabel,
        )
    val themContent =
        ScorePanelContent(
            team = Team.Them,
            teamLabel = stringResource(Res.string.live_team_them),
            playerName = FAKE_NAME_THEM,
            pointText = pointThem,
            serving = state.server == Team.Them && !state.isFinished,
            serveLabel = serveLabel,
            a11yDescription = stringResource(Res.string.a11y_point_for, themName, FAKE_NAME_THEM, pointUs, pointThem) + " " + serving,
            undoLabel = undoLabel,
        )

    @Composable
    fun Panel(
        panelContent: ScorePanelContent,
        panelModifier: Modifier,
    ) {
        val team = panelContent.team
        ScorePanel(
            content = panelContent,
            direction = controller.lastDirection,
            flashKey = if (controller.flashTeam == team) controller.generation else 0,
            outdoor = controller.outdoorContrast,
            reduceMotion = reduceMotion,
            enabled = true,
            onTap = { controller.onTap(team, it) },
            onSwipeDown = { controller.undo(it) },
            modifier = panelModifier.onGloballyPositioned { controller.panelBounds[team] = it.boundsInWindow() },
        )
    }

    // Oben/Unten im Hochformat, nebeneinander im Querformat (04 §4.1 H6) – richtet sich nach dem Bildschirm,
    // nicht nach der Restfläche (bei 200 % Schrift wäre die sonst „quer“).
    Box(modifier) {
        if (landscape) {
            Column(Modifier.fillMaxSize()) {
                HintBand(hint, controller.outdoorContrast, reduceMotion, landscape)
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    Panel(usContent, Modifier.weight(1f).fillMaxSize())
                    Panel(themContent, Modifier.weight(1f).fillMaxSize())
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Panel(usContent, Modifier.weight(1f).fillMaxWidth())
                HintBand(hint, controller.outdoorContrast, reduceMotion, landscape)
                Panel(themContent, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

/** Mittelband zwischen den Flächen (nicht scorend, 04 §4.3.3). */
@Composable
private fun HintBand(
    hint: ScoreHint?,
    outdoor: Boolean,
    reduceMotion: Boolean,
    landscape: Boolean,
) {
    Box(Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(vertical = Spacing.xs), contentAlignment = Alignment.Center) {
        if (hint != null) HintPill(hint, outdoor, reduceMotion, landscape)
    }
}

@Composable
private fun Footer(
    controller: LiveScoreController,
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
            enabled = controller.canUndo,
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
            MoreMenu(controller, expanded = menuOpen, onDismiss = { menuOpen = false })
        }
    }
}

@Composable
private fun MoreMenu(
    controller: LiveScoreController,
    expanded: Boolean,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()

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
        Item(Res.string.menu_outdoor_contrast, controller.outdoorContrast) { controller.outdoorContrast = !controller.outdoorContrast }
        Item(Res.string.menu_keep_screen_on, controller.keepScreenOn) { controller.keepScreenOn = !controller.keepScreenOn }
        Item(Res.string.menu_latency_overlay, controller.showLatencyOverlay) {
            controller.showLatencyOverlay = !controller.showLatencyOverlay
        }
        Item(Res.string.menu_debounce, controller.debounceEnabled) { controller.debounceEnabled = !controller.debounceEnabled }
        HorizontalDivider()
        Item(Res.string.menu_demo_breakpoint) { controller.demo(DemoScenario.BreakPoint) }
        Item(Res.string.menu_demo_setpoint) { controller.demo(DemoScenario.SetPoint) }
        Item(Res.string.menu_demo_matchpoint) { controller.demo(DemoScenario.MatchPoint) }
        Item(Res.string.menu_demo_deuce) { controller.demo(DemoScenario.Deuce) }
        Item(Res.string.menu_demo_tiebreak) { controller.demo(DemoScenario.Tiebreak) }
        HorizontalDivider()
        Item(Res.string.menu_auto_benchmark) {
            if (!controller.benchmarkRunning) scope.launch { println(controller.runAutoBenchmark().toLogLine()) }
        }
        Item(Res.string.action_redo, enabled = controller.canRedo, tag = "menu_redo") { controller.redo() }
        Item(Res.string.menu_reset_measurements) { controller.resetMeasurements() }
        Item(Res.string.menu_new_match) { controller.newMatch() }
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
        notice.before.gamesUs != notice.after.gamesUs ||
            notice.before.gamesThem != notice.after.gamesThem ||
            notice.before.completedSets != notice.after.completedSets
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
        Text(value, style = style, color = valueColor)
    }
}
