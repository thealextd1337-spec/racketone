package eu.advantly.ui.phone.result

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.scoring.Callout
import eu.advantly.core.scoring.MomentKind
import eu.advantly.core.scoring.MomentOutcome
import eu.advantly.health.HealthQuality
import eu.advantly.presentation.match.MatchSetup
import eu.advantly.presentation.match.MatchSummary
import eu.advantly.presentation.stats.CourseGame
import eu.advantly.presentation.stats.CourseSection
import eu.advantly.presentation.stats.MatchStats
import eu.advantly.presentation.stats.PulsePoint
import eu.advantly.presentation.stats.PulseTrack
import eu.advantly.presentation.stats.PulseTransfer
import eu.advantly.presentation.stats.TimelineMoment
import eu.advantly.presentation.stats.axisTicks
import eu.advantly.presentation.stats.courseSections
import eu.advantly.presentation.stats.placeSymbols
import eu.advantly.presentation.stats.pulseSegments
import eu.advantly.presentation.stats.pulseSummary
import eu.advantly.presentation.stats.pulseTicks
import eu.advantly.presentation.stats.within
import eu.advantly.ui.phone.app.LocalHealthConsent
import eu.advantly.ui.phone.common.AdvantlyCard
import eu.advantly.ui.phone.common.SectionTitle
import eu.advantly.ui.phone.common.durationText
import eu.advantly.ui.phone.common.scorePair
import eu.advantly.ui.phone.common.stateScore
import eu.advantly.ui.phone.common.teamName
import eu.advantly.ui.phone.designsystem.AdvantlyColors
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.designsystem.Spacing
import eu.advantly.ui.phone.history.setsLine
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.detail_break
import eu.advantly.ui.phone.resources.detail_progress_empty
import eu.advantly.ui.phone.resources.detail_rallies
import eu.advantly.ui.phone.resources.detail_unfinished
import eu.advantly.ui.phone.resources.moment_break
import eu.advantly.ui.phone.resources.moment_break_point
import eu.advantly.ui.phone.resources.moment_comeback
import eu.advantly.ui.phone.resources.moment_converted
import eu.advantly.ui.phone.resources.moment_deciding_point
import eu.advantly.ui.phone.resources.moment_game_point_pickleball
import eu.advantly.ui.phone.resources.moment_game_won_pickleball
import eu.advantly.ui.phone.resources.moment_golden_point
import eu.advantly.ui.phone.resources.moment_match_point
import eu.advantly.ui.phone.resources.moment_match_won
import eu.advantly.ui.phone.resources.moment_no_ad_point
import eu.advantly.ui.phone.resources.moment_saved
import eu.advantly.ui.phone.resources.moment_set_point
import eu.advantly.ui.phone.resources.moment_set_won
import eu.advantly.ui.phone.resources.moment_star_point
import eu.advantly.ui.phone.resources.moment_tiebreak
import eu.advantly.ui.phone.resources.timeline_breaks_a11y
import eu.advantly.ui.phone.resources.timeline_card_pulse
import eu.advantly.ui.phone.resources.timeline_clock
import eu.advantly.ui.phone.resources.timeline_corrected
import eu.advantly.ui.phone.resources.timeline_enable_pulse
import eu.advantly.ui.phone.resources.timeline_game_duration
import eu.advantly.ui.phone.resources.timeline_game_pulse
import eu.advantly.ui.phone.resources.timeline_legend_pulse
import eu.advantly.ui.phone.resources.timeline_legend_serve
import eu.advantly.ui.phone.resources.timeline_marker_a11y
import eu.advantly.ui.phone.resources.timeline_pulse_missing
import eu.advantly.ui.phone.resources.timeline_pulse_overdue
import eu.advantly.ui.phone.resources.timeline_pulse_pending
import eu.advantly.ui.phone.resources.timeline_quality_gaps
import eu.advantly.ui.phone.resources.timeline_quality_no_hr
import eu.advantly.ui.phone.resources.timeline_quality_sparse
import eu.advantly.ui.phone.resources.timeline_score_before
import eu.advantly.ui.phone.resources.timeline_serve
import eu.advantly.ui.phone.resources.timeline_set
import eu.advantly.ui.phone.resources.timeline_title
import eu.advantly.ui.phone.resources.wizard_team_them
import eu.advantly.ui.phone.resources.wizard_team_us
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

/** Reihenfolge der Arten in Titel und Symbol: das Wichtigste zuerst. */
private val KIND_ORDER =
    listOf(
        MomentKind.MATCH_WON,
        MomentKind.SET_WON,
        MomentKind.COMEBACK,
        MomentKind.MATCH_POINT,
        MomentKind.SET_POINT,
        MomentKind.BREAK,
        MomentKind.BREAK_POINT,
        MomentKind.DECIDING_POINT,
        MomentKind.TIEBREAK_STARTED,
    )

/** Höhe der Grafik (Balken und Pulskurve), der Zeitachse darunter und Platz für die bpm-Beschriftung rechts. */
private val CHART_HEIGHT = 120.dp
private val AXIS_HEIGHT = 16.dp
private val PULSE_LABELS = 28.dp

/** Sichtbarer Kreis eines Highlight-Symbols; die Tippfläche ist [Spacing.minTouchTarget] breit. */
private val SYMBOL = 22.dp

/**
 * Spielverlauf im Match-Detail (Plan 0.5.1 §7c): je Satz eine Grafik untereinander (bei Rally-Sportarten eine für das
 * Match), Achse = Spielzeit ohne Pausen. Ein Balken je Game, so breit wie das Game dauert und so hoch wie die
 * Ballwechseldifferenz (oben Wir, unten Gegner); weißer Punkt = Break, gelber Punkt an der Mittellinie = Aufschläger.
 * Mit [pulse] liegt die Pulskurve darüber (gleiche Skala in allen Sätzen, bpm rechts), die Balken werden blasser.
 *
 * Unter jeder Grafik stehen die Highlights als Symbole (Tippfläche ≥ 48 dp; nahe Symbole rücken auseinander, zu viele
 * teilen sich eine Fläche, jedes Tippen wählt das nächste). Antippen zeigt eine gestrichelte Linie zum Zeitpunkt und
 * darunter die Karte mit dem Stand **vor** dem Punkt; Antippen eines Balkens die Karte des Games. Ohne Puls und ohne
 * Einwilligung führt „Pulsverlauf aktivieren“ zu [onEnablePulse]; [pulseTransfer] meldet einen noch ausstehenden
 * Pulsverlauf der Uhr. Bei [corrected] weist sie darauf hin, dass sie die gezählten Punkte zeigt. Darunter die
 * aufklappbare Game-Liste.
 */
@Composable
internal fun MatchTimeline(
    summary: MatchSummary,
    stats: MatchStats,
    modifier: Modifier = Modifier,
    pulse: PulseTrack? = null,
    onEnablePulse: (() -> Unit)? = null,
    corrected: Boolean = false,
    pulseTransfer: PulseTransfer? = null,
) {
    val setup = summary.setup
    val colors = AdvantlyTheme.colors
    val perSet = setup.sport.isTennisLike
    val sections = remember(stats, perSet) { stats.courseSections(perSet) }
    val segments = remember(pulse, stats.playClock) { pulse?.let(stats.playClock::pulseSegments).orEmpty() }
    var selectedGame by remember(stats) { mutableStateOf<Int?>(null) }
    var selectedMoment by remember(stats) { mutableStateOf<Int?>(null) }
    Column(modifier.testTag("timeline")) {
        SectionTitle(stringResource(Res.string.timeline_title))
        if (sections.isEmpty()) {
            Text(stringResource(Res.string.detail_progress_empty), color = colors.text2, modifier = Modifier.testTag("timeline_empty"))
            PulseNotes(pulse, onEnablePulse, pulseTransfer)
            return@Column
        }
        val total = segments.pulseSummary()
        Legend(total?.let { stringResource(Res.string.timeline_legend_pulse, it.average, it.max) }, perSet)
        val values = segments.flatten().map { it.bpm }
        // gleiche Skala in allen Sätzen, etwas Luft über und unter der Kurve
        val scale = if (values.isEmpty()) null else (values.min() - 5)..(values.max() + 5)
        val maxMargin = stats.progression.maxOf { abs(it.margin) }.coerceAtLeast(1)
        sections.forEachIndexed { number, section ->
            val inSection = if (section.timed) segments.within(section.startMs, section.endMs) else emptyList()
            val header = sectionHeader(section, summary)
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = colors.text, fontWeight = FontWeight.SemiBold)) { append(header.first) }
                    header.second?.let { withStyle(SpanStyle(color = colors.text2)) { append(" · $it") } }
                },
                style = AdvantlyTheme.typography.body,
                modifier = Modifier.padding(top = Spacing.m, bottom = Spacing.xs).testTag("timeline_set_$number"),
            )
            val breaks = section.games.count { it.game.isBreak }
            val description =
                listOfNotNull(
                    listOfNotNull(header.first, header.second).joinToString(" · "),
                    stringResource(Res.string.timeline_breaks_a11y, breaks).takeIf { perSet },
                    inSection.pulseSummary()?.let { stringResource(Res.string.timeline_legend_pulse, it.average, it.max) },
                ).joinToString(", ")
            CourseChart(
                section = section,
                setup = setup,
                stats = stats,
                pulse = inSection,
                scale = scale,
                maxMargin = maxMargin,
                selectedGame = selectedGame,
                selectedMoment = selectedMoment,
                description = description,
                onGame = {
                    selectedGame = it.takeIf { it != selectedGame }
                    selectedMoment = null
                },
                onMoment = { members ->
                    selectedMoment = members.getOrNull(members.indexOf(selectedMoment) + 1) ?: members.first()
                    selectedGame = null
                },
            )
            section.games.firstOrNull { it.index == selectedGame }?.let { GameCard(it, section.timed, setup, inSection) }
            section.moments.firstOrNull { it.index == selectedMoment }?.let { MomentCard(stats.moments[it.index], setup, pulse) }
        }
        pulse?.quality?.let { quality ->
            val hint =
                when (quality) {
                    HealthQuality.OK -> null
                    HealthQuality.SPARSE_HR -> Res.string.timeline_quality_sparse
                    HealthQuality.GAPS -> Res.string.timeline_quality_gaps
                    HealthQuality.NO_HR -> Res.string.timeline_quality_no_hr
                }
            if (hint != null) Note(stringResource(hint), "timeline_quality")
        }
        if (corrected) Note(stringResource(Res.string.timeline_corrected), "timeline_corrected")
        PulseNotes(pulse, onEnablePulse, pulseTransfer)
        GameHistoryList(setup, stats.progression)
    }
}

/** Überschrift eines Diagramms: „Satz 1 · 6:4“ bzw. bei Rally-Sportarten die Sätze des Matches, dazu die Spielzeit. */
@Composable
private fun sectionHeader(
    section: CourseSection,
    summary: MatchSummary,
): Pair<String, String?> {
    val title =
        section.set?.let { stringResource(Res.string.timeline_set, it) + " · " + scorePair(section.score) } ?: setsLine(summary)
    return title to durationText(section.durationMs).takeIf { section.timed }
}

/** Zeile 1: „■ Wir ■ Gegner ━ Puls · Ø x · Max y“; Zeile 2 (Tennis/Padel): „● Break ● Aufschlag (oben Wir, unten Gegner)“. */
@Composable
private fun Legend(
    pulse: String?,
    perSet: Boolean,
) {
    val colors = AdvantlyTheme.colors
    val style = AdvantlyTheme.typography.caption
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = colors.teamUsChart)) { append("■ ") }
                append(stringResource(Res.string.wizard_team_us) + "  ")
                withStyle(SpanStyle(color = colors.teamThemChart)) { append("■ ") }
                append(stringResource(Res.string.wizard_team_them))
            },
            style = style,
            color = colors.text2,
        )
        if (pulse != null) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = colors.danger)) { append("━ ") }
                    append(pulse)
                },
                style = style,
                color = colors.text2,
                modifier = Modifier.testTag("timeline_pulse"),
            )
        }
    }
    if (perSet) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = colors.text)) { append("● ") }
                append(stringResource(Res.string.detail_break) + "  ")
                withStyle(SpanStyle(color = colors.hint)) { append("● ") }
                append(stringResource(Res.string.timeline_legend_serve))
            },
            style = style,
            color = colors.text2,
        )
    }
}

/** Grafik eines Abschnitts mit Zeitachse, darunter die Highlight-Symbole; Balken und Symbole sind einzeln antippbar. */
@Composable
private fun CourseChart(
    section: CourseSection,
    setup: MatchSetup,
    stats: MatchStats,
    pulse: List<List<PulsePoint>>,
    scale: IntRange?,
    maxMargin: Int,
    selectedGame: Int?,
    selectedMoment: Int?,
    description: String,
    onGame: (Int) -> Unit,
    onMoment: (List<Int>) -> Unit,
) {
    val colors = AdvantlyTheme.colors
    val measurer = rememberTextMeasurer()
    val labelStyle = AdvantlyTheme.typography.caption.copy(fontSize = 10.sp, color = colors.text3)
    val touch = Spacing.minTouchTarget
    val ticks =
        if (section.timed) axisTicks(section.startMs, section.endMs).map { it to axisTime(it) } else emptyList()
    BoxWithConstraints(Modifier.fillMaxWidth().height(CHART_HEIGHT + AXIS_HEIGHT + touch)) {
        val plotWidth = maxWidth - if (scale != null) PULSE_LABELS else 0.dp
        val span = (section.endMs - section.startMs).coerceAtLeast(1)
        val xOf = { ms: Long -> plotWidth * ((ms - section.startMs).toFloat() / span) }
        Canvas(Modifier.fillMaxWidth().height(CHART_HEIGHT + AXIS_HEIGHT).semantics { contentDescription = description }) {
            val chart = CHART_HEIGHT.toPx()
            val plot = plotWidth.toPx()
            val middle = chart / 2
            drawRoundRect(colors.surface2, size = Size(plot, chart), cornerRadius = CornerRadius(6.dp.toPx()))
            drawLine(colors.text3, Offset(0f, middle), Offset(plot, middle), 1.dp.toPx())
            for (game in section.games) {
                val x0 = xOf(game.startMs).toPx()
                val x1 = xOf(game.endMs).toPx()
                val margin = game.game.margin
                val height = abs(margin).toFloat() / maxMargin * (middle - 10.dp.toPx())
                val top = if (margin >= 0) middle - height else middle
                val color =
                    when {
                        margin > 0 -> colors.teamUsChart
                        margin < 0 -> colors.teamThemChart
                        else -> colors.text2
                    }
                val selected = game.index == selectedGame
                val alpha =
                    if (selected) {
                        1f
                    } else if (scale != null) {
                        0.45f
                    } else {
                        0.85f
                    }
                drawRoundRect(
                    color.copy(alpha = alpha),
                    Offset(x0 + 1.dp.toPx(), top),
                    Size(maxOf(x1 - x0 - 2.dp.toPx(), 2.dp.toPx()), maxOf(height, 2.dp.toPx())),
                    CornerRadius(2.dp.toPx()),
                )
                if (selected) {
                    drawRoundRect(
                        colors.text,
                        Offset(x0 + 0.5.dp.toPx(), 2.dp.toPx()),
                        Size(maxOf(x1 - x0 - 1.dp.toPx(), 2.dp.toPx()), chart - 4.dp.toPx()),
                        CornerRadius(3.dp.toPx()),
                        style = Stroke(1.5.dp.toPx()),
                    )
                }
                val center = (x0 + x1) / 2
                if (game.game.isBreak) {
                    val y = if (margin >= 0) top - 5.dp.toPx() else top + height + 5.dp.toPx()
                    drawCircle(colors.text, 2.6.dp.toPx(), Offset(center, y))
                }
                game.game.server?.let { server ->
                    val y = if (server == Side.A) middle - 5.dp.toPx() else middle + 5.dp.toPx()
                    drawCircle(colors.bg, 3.6.dp.toPx(), Offset(center, y))
                    drawCircle(colors.hint, 2.8.dp.toPx(), Offset(center, y))
                }
            }
            if (scale != null) {
                val range = (scale.last - scale.first).coerceAtLeast(1)
                val yOf = { bpm: Int -> chart - 3.dp.toPx() - (bpm - scale.first).toFloat() / range * (chart - 6.dp.toPx()) }
                for (segment in pulse) {
                    val points = segment.map { Offset(xOf(it.playMs).toPx(), yOf(it.bpm)) }
                    if (points.size == 1) {
                        drawCircle(colors.danger, 2.dp.toPx(), points.single())
                    } else {
                        val path = Path().apply { points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
                        drawPath(path, colors.danger, style = Stroke(width = 1.8.dp.toPx(), join = StrokeJoin.Round))
                    }
                }
                for (bpm in pulseTicks(scale.first, scale.last)) {
                    val layout = measurer.measure(bpm.toString(), labelStyle)
                    drawText(layout, topLeft = Offset(plot + 4.dp.toPx(), yOf(bpm) - layout.size.height / 2))
                }
            }
            section.moments.firstOrNull { it.index == selectedMoment }?.let { moment ->
                val x = xOf(moment.atMs).toPx()
                drawLine(
                    colors.text,
                    Offset(x, 0f),
                    Offset(x, chart),
                    1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
                )
            }
            for ((ms, label) in ticks) {
                val layout = measurer.measure(label, labelStyle)
                val x = (xOf(ms).toPx() - layout.size.width / 2).coerceIn(0f, maxOf(0f, plot - layout.size.width))
                drawText(layout, topLeft = Offset(x, chart + 2.dp.toPx()))
            }
        }
        // Je Balken ein fokussierbarer Knoten für Screenreader; Tippen wertet eine gemeinsame Fläche darüber aus, damit
        // auch Games, die kürzer als die Mindest-Tippbreite sind, gut treffbar bleiben (Review N6).
        for (game in section.games) {
            val description = gameDescription(game.game, setup)
            Box(
                Modifier
                    .offset(x = xOf(game.startMs))
                    .size(xOf(game.endMs) - xOf(game.startMs), CHART_HEIGHT)
                    .testTag("timeline_game_${game.index}")
                    .semantics {
                        role = Role.Button
                        this.contentDescription = description
                        onClick {
                            onGame(game.index)
                            true
                        }
                    },
            )
        }
        val bars = section.games.map { xOf(it.startMs).value..xOf(it.endMs).value }
        val currentOnGame by rememberUpdatedState(onGame)
        Box(
            Modifier.size(plotWidth, CHART_HEIGHT).pointerInput(section, bars, touch) {
                detectTapGestures { tap ->
                    gameAt(tap.x.toDp().value, bars, touch.value)?.let { currentOnGame(section.games[it].index) }
                }
            },
        )
        val slots = placeSymbols(section.moments.map { xOf(it.atMs).value }, plotWidth.value, touch.value)
        for (slot in slots) {
            val members = slot.members.map { section.moments[it].index }
            val shown = members.firstOrNull { it == selectedMoment } ?: members.minBy { importance(stats.moments[it]) }
            val moment = stats.moments[shown]
            val color = markerColor(moment, colors)
            val description =
                members
                    .map { i ->
                        stringResource(
                            Res.string.timeline_marker_a11y,
                            playTime(stats.moments[i].playMs),
                            momentTitle(stats.moments[i], setup.sport),
                        )
                    }.joinToString("; ")
            Box(
                Modifier
                    .offset(x = Dp(slot.center) - touch / 2, y = CHART_HEIGHT + AXIS_HEIGHT)
                    .size(touch)
                    .testTag("timeline_marker_${members.first()}")
                    .clickable(role = Role.Button) { onMoment(members) }
                    .semantics { this.contentDescription = description },
                contentAlignment = Alignment.Center,
            ) {
                val selected = shown == selectedMoment
                Box(
                    Modifier
                        .size(if (selected) SYMBOL + 2.dp else SYMBOL)
                        .background(colors.surface2, CircleShape)
                        .border(if (selected) 2.5.dp else 1.5.dp, color, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(momentSymbol(moment, setup.sport), color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                // geteilte Fläche: Anzahl oben rechts, jedes Tippen zeigt das nächste Highlight
                if (members.size > 1) {
                    Text(
                        members.size.toString(),
                        color = colors.text2,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 6.dp),
                    )
                }
            }
        }
    }
}

/** Karte zum angetippten Game: Ballwechsel, Sieger und Break, Dauer, Puls Ø/Max im Game, Aufschläger. */
@Composable
private fun GameCard(
    game: CourseGame,
    timed: Boolean,
    setup: MatchSetup,
    pulse: List<List<PulsePoint>>,
) {
    val progress = game.game
    val colors = AdvantlyTheme.colors
    AdvantlyCard(Modifier.padding(top = Spacing.s).testTag("timeline_game_card")) {
        Text(
            listOfNotNull(
                gameLabel(progress, setup),
                progress.winner?.let { teamName(setup, it) } ?: stringResource(Res.string.detail_unfinished),
                stringResource(Res.string.detail_break).takeIf { progress.isBreak },
            ).joinToString(" · "),
            style = AdvantlyTheme.typography.label,
            color = colors.text,
        )
        CardLine(stringResource(Res.string.detail_rallies, scorePair(progress.pointsWon)), "timeline_game_rallies")
        if (timed) {
            CardLine(
                stringResource(Res.string.timeline_game_duration, playTime(game.endMs - game.startMs)),
                "timeline_game_duration",
            )
        }
        pulse.within(game.startMs, game.endMs).pulseSummary()?.let {
            CardLine(stringResource(Res.string.timeline_game_pulse, it.average, it.max), "timeline_game_pulse")
        }
        progress.server?.let { CardLine(stringResource(Res.string.timeline_serve, teamName(setup, it)), "timeline_game_serve") }
    }
}

/** Karte zum angetippten Highlight: Arten, Stand vor dem Punkt, verwertet/abgewehrt, Aufschläger, Ø-Puls, Spielzeit. */
@Composable
private fun MomentCard(
    moment: TimelineMoment,
    setup: MatchSetup,
    pulse: PulseTrack?,
) {
    AdvantlyCard(Modifier.padding(top = Spacing.s).testTag("timeline_card")) {
        Text(momentTitle(moment, setup.sport), style = AdvantlyTheme.typography.label, color = AdvantlyTheme.colors.text)
        Text(
            stringResource(Res.string.timeline_score_before, stateScore(moment.before, setup.sport)),
            style = AdvantlyTheme.typography.body,
            color = AdvantlyTheme.colors.text,
            modifier = Modifier.testTag("timeline_card_score"),
        )
        CardLine(outcomeText(moment, setup), "timeline_card_outcome")
        moment.before.serve
            ?.side
            ?.let { CardLine(stringResource(Res.string.timeline_serve, teamName(setup, it)), "timeline_card_serve") }
        pulse?.averageAround(moment.moment.wallClockMs)?.let {
            CardLine(stringResource(Res.string.timeline_card_pulse, it), "timeline_card_pulse")
        }
        Text(playTime(moment.playMs), style = AdvantlyTheme.typography.caption, color = AdvantlyTheme.colors.text2)
    }
}

@Composable
private fun CardLine(
    text: String,
    tag: String,
) {
    Text(text, style = AdvantlyTheme.typography.body, color = AdvantlyTheme.colors.text2, modifier = Modifier.testTag(tag))
}

@Composable
private fun Note(
    text: String,
    tag: String,
) {
    Text(
        text,
        style = AdvantlyTheme.typography.caption,
        color = AdvantlyTheme.colors.text2,
        modifier = Modifier.padding(top = Spacing.xs).testTag(tag),
    )
}

/**
 * Ohne Pulsverlauf: mit Einwilligung der Übertragungsstand der Uhr ([transfer], Plan 0.5.1 §7d), ohne Einwilligung der
 * Link „Pulsverlauf aktivieren“.
 */
@Composable
private fun PulseNotes(
    pulse: PulseTrack?,
    onEnablePulse: (() -> Unit)?,
    transfer: PulseTransfer?,
) {
    if (pulse != null) return
    if (LocalHealthConsent.current) {
        val text =
            when (transfer) {
                PulseTransfer.PENDING -> Res.string.timeline_pulse_pending
                PulseTransfer.OVERDUE -> Res.string.timeline_pulse_overdue
                PulseTransfer.MISSING -> Res.string.timeline_pulse_missing
                null -> return
            }
        Note(stringResource(text), "timeline_pulse_transfer")
        return
    }
    if (onEnablePulse == null) return
    Text(
        stringResource(Res.string.timeline_enable_pulse),
        style = AdvantlyTheme.typography.label,
        color = AdvantlyTheme.colors.brand,
        modifier =
            Modifier
                .heightIn(min = Spacing.minTouchTarget)
                .clickable(role = Role.Button, onClick = onEnablePulse)
                .padding(vertical = Spacing.xs)
                .testTag("timeline_enable_pulse"),
    )
}

/** Rang der wichtigsten Art (kleiner = wichtiger), wählt das Symbol einer geteilten Tippfläche. */
private fun importance(moment: TimelineMoment): Int = KIND_ORDER.indexOfFirst { it in moment.moment.kinds }

/** Symbolfarbe: Gewinne neutral, Chancen in Hinweisfarbe, Breaks in der Farbe des breakenden Teams. */
private fun markerColor(
    moment: TimelineMoment,
    colors: AdvantlyColors,
): Color =
    when (KIND_ORDER.first { it in moment.moment.kinds }) {
        MomentKind.MATCH_WON, MomentKind.SET_WON, MomentKind.COMEBACK -> colors.text
        MomentKind.BREAK -> if (moment.moment.side == Side.A) colors.teamUsChart else colors.teamThemChart
        MomentKind.TIEBREAK_STARTED -> colors.text2
        else -> colors.hint
    }

/** Symbol im Kreis: ★ Matchgewinn, ✓ Satzgewinn, sonst der Anfangsbuchstabe der wichtigsten Art („B“reak, „S“atzball …). */
@Composable
private fun momentSymbol(
    moment: TimelineMoment,
    sport: Sport,
): String =
    when (val kind = KIND_ORDER.first { it in moment.moment.kinds }) {
        MomentKind.MATCH_WON -> "★"
        MomentKind.SET_WON -> "✓"
        else -> kindLabel(kind, moment, sport).take(1).uppercase()
    }

/**
 * Game unter einem Tippen bei [x] (dp) in der Grafik (Review N6): Jeder Balken ([bars], von–bis in dp) ist mindestens
 * [minWidth] breit treffbar, um seine Mitte; breitere behalten ihre Fläche. Liegt [x] in mehreren Flächen, gewinnt die
 * nächste Mitte – so ist jedes Game an seiner Mitte erreichbar. `null` ohne Balken.
 */
internal fun gameAt(
    x: Float,
    bars: List<ClosedFloatingPointRange<Float>>,
    minWidth: Float,
): Int? {
    fun center(bar: ClosedFloatingPointRange<Float>) = (bar.start + bar.endInclusive) / 2

    fun outside(bar: ClosedFloatingPointRange<Float>) = maxOf(0f, abs(x - center(bar)) - maxOf(bar.endInclusive - bar.start, minWidth) / 2)
    return bars.indices.minWithOrNull(compareBy({ outside(bars[it]) }, { abs(x - center(bars[it])) }))
}

/** Spielzeit „m:ss“ ab Matchbeginn ohne Pausen. */
@Composable
private fun playTime(ms: Long): String {
    val seconds = ms / 1_000
    return stringResource(Res.string.timeline_clock, (seconds / 60).toInt(), (seconds % 60).toString().padStart(2, '0'))
}

/** Achsenbeschriftung „h:mm“ ab Matchbeginn ohne Pausen. */
@Composable
private fun axisTime(ms: Long): String {
    val minutes = ms / 60_000
    return stringResource(Res.string.timeline_clock, (minutes / 60).toInt(), (minutes % 60).toString().padStart(2, '0'))
}

/** Arten des Highlights, das Wichtigste zuerst, z. B. „Satzgewinn · Break · Breakball“. */
@Composable
private fun momentTitle(
    moment: TimelineMoment,
    sport: Sport,
): String = KIND_ORDER.filter { it in moment.moment.kinds }.map { kindLabel(it, moment, sport) }.joinToString(" · ")

@Composable
private fun kindLabel(
    kind: MomentKind,
    moment: TimelineMoment,
    sport: Sport,
): String =
    stringResource(
        when (kind) {
            MomentKind.BREAK_POINT -> {
                Res.string.moment_break_point
            }

            MomentKind.BREAK -> {
                Res.string.moment_break
            }

            MomentKind.DECIDING_POINT -> {
                when {
                    moment.before.current?.callout == Callout.STAR_POINT -> Res.string.moment_star_point
                    sport == Sport.PADEL -> Res.string.moment_golden_point
                    sport == Sport.TENNIS -> Res.string.moment_no_ad_point
                    else -> Res.string.moment_deciding_point
                }
            }

            MomentKind.TIEBREAK_STARTED -> {
                Res.string.moment_tiebreak
            }

            MomentKind.SET_POINT -> {
                if (sport == Sport.PICKLEBALL) Res.string.moment_game_point_pickleball else Res.string.moment_set_point
            }

            MomentKind.MATCH_POINT -> {
                Res.string.moment_match_point
            }

            MomentKind.SET_WON -> {
                if (sport == Sport.PICKLEBALL) Res.string.moment_game_won_pickleball else Res.string.moment_set_won
            }

            MomentKind.MATCH_WON -> {
                Res.string.moment_match_won
            }

            MomentKind.COMEBACK -> {
                Res.string.moment_comeback
            }
        },
    )

/** „Verwertet von Wir“ / „Abgewehrt von Jonas“; ohne Chance nur das Team, das den Punkt gewann. */
@Composable
private fun outcomeText(
    moment: TimelineMoment,
    setup: MatchSetup,
): String {
    val side = moment.moment.side
    return when (moment.moment.outcome) {
        MomentOutcome.CONVERTED -> stringResource(Res.string.moment_converted, teamName(setup, side))
        MomentOutcome.SAVED -> stringResource(Res.string.moment_saved, teamName(setup, side.other))
        null -> teamName(setup, side)
    }
}
