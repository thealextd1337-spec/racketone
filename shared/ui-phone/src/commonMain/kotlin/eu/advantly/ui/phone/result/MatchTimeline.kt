package eu.advantly.ui.phone.result

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import eu.advantly.core.model.Sport
import eu.advantly.core.scoring.Callout
import eu.advantly.core.scoring.MomentKind
import eu.advantly.core.scoring.MomentOutcome
import eu.advantly.health.HealthQuality
import eu.advantly.presentation.match.MatchSetup
import eu.advantly.presentation.stats.MatchStats
import eu.advantly.presentation.stats.PulseTrack
import eu.advantly.presentation.stats.TimelineMoment
import eu.advantly.presentation.stats.pulseSegments
import eu.advantly.ui.phone.common.AdvantlyCard
import eu.advantly.ui.phone.common.SectionTitle
import eu.advantly.ui.phone.common.stateScore
import eu.advantly.ui.phone.common.teamName
import eu.advantly.ui.phone.designsystem.AdvantlyColors
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.designsystem.Radii
import eu.advantly.ui.phone.designsystem.Spacing
import eu.advantly.ui.phone.resources.Res
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
import eu.advantly.ui.phone.resources.timeline_card_pulse
import eu.advantly.ui.phone.resources.timeline_clock
import eu.advantly.ui.phone.resources.timeline_empty
import eu.advantly.ui.phone.resources.timeline_highlights
import eu.advantly.ui.phone.resources.timeline_hint
import eu.advantly.ui.phone.resources.timeline_marker_a11y
import eu.advantly.ui.phone.resources.timeline_pulse
import eu.advantly.ui.phone.resources.timeline_quality_gaps
import eu.advantly.ui.phone.resources.timeline_quality_no_hr
import eu.advantly.ui.phone.resources.timeline_quality_sparse
import eu.advantly.ui.phone.resources.timeline_score_before
import eu.advantly.ui.phone.resources.timeline_title
import org.jetbrains.compose.resources.stringResource

/** Reihenfolge der Arten in Titel und Markerfarbe: das Wichtigste zuerst. */
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

/**
 * Zeitleiste im Match-Detail (Plan Puls-Highlights §5): x-Achse Spielzeit ohne Pausen, Marker je Highlight; Antippen
 * zeigt den Stand **vor** dem Punkt und ob die Chance verwertet oder abgewehrt wurde. Darunter dieselben Highlights als
 * barrierefreie Liste. Mit [pulse] zeichnet sie zusätzlich die Pulskurve (Lücken und Pausen unterbrechen die Linie),
 * darunter einen Hinweis bei schlechter Datenqualität, und die Karte zeigt den Ø-Puls (Trend); ohne Puls bleibt es bei
 * den Highlights.
 */
@Composable
internal fun MatchTimeline(
    setup: MatchSetup,
    stats: MatchStats,
    modifier: Modifier = Modifier,
    pulse: PulseTrack? = null,
) {
    val colors = AdvantlyTheme.colors
    val moments = stats.moments
    var selected by remember(moments) { mutableStateOf<Int?>(null) }
    val segments = remember(pulse, stats.playClock) { pulse?.let(stats.playClock::pulseSegments).orEmpty() }
    Column(modifier.testTag("timeline")) {
        SectionTitle(stringResource(Res.string.timeline_title))
        if (moments.isEmpty() && segments.isEmpty()) {
            Text(stringResource(Res.string.timeline_empty), color = colors.text2, modifier = Modifier.testTag("timeline_empty"))
            return@Column
        }
        val values = segments.flatten().map { it.bpm }
        if (values.isNotEmpty()) {
            Text(
                stringResource(Res.string.timeline_pulse, values.min(), values.max()),
                style = AdvantlyTheme.typography.caption,
                color = colors.text2,
                modifier = Modifier.testTag("timeline_pulse"),
            )
        }
        if (moments.isNotEmpty()) {
            Text(stringResource(Res.string.timeline_hint), style = AdvantlyTheme.typography.caption, color = colors.text2)
        }
        val duration = stats.playClock.durationMs.coerceAtLeast(1)
        val markerSize = 24.dp
        BoxWithConstraints(Modifier.fillMaxWidth().height(if (values.isEmpty()) 40.dp else 140.dp).padding(top = Spacing.xs)) {
            val axisHeight = 12.dp
            Canvas(Modifier.matchParentSize().clearAndSetSemantics {}) {
                val axisY = size.height - markerSize.toPx() / 2
                drawLine(colors.line, Offset(0f, axisY), Offset(size.width, axisY), strokeWidth = 1.dp.toPx())
                moments.forEach { m ->
                    val x = m.playMs.toFloat() / duration * size.width
                    drawLine(colors.line, Offset(x, axisY - axisHeight.toPx()), Offset(x, axisY), 1.dp.toPx())
                }
                if (values.isEmpty()) return@Canvas
                val min = values.min()
                val range = (values.max() - min).coerceAtLeast(1)
                val top = 4.dp.toPx()
                val bottom = axisY - axisHeight.toPx()
                for (segment in segments) {
                    val points =
                        segment.map {
                            Offset(it.playMs.toFloat() / duration * size.width, bottom - (it.bpm - min).toFloat() / range * (bottom - top))
                        }
                    if (points.size == 1) {
                        drawCircle(colors.danger, 2.dp.toPx(), points.single())
                    } else {
                        val path = Path().apply { points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
                        drawPath(path, colors.danger, style = Stroke(width = 2.dp.toPx()))
                    }
                }
            }
            moments.forEachIndexed { index, moment ->
                val x = maxWidth * (moment.playMs.toFloat() / duration) - markerSize / 2
                val description = stringResource(Res.string.timeline_marker_a11y, playTime(moment.playMs), momentTitle(moment, setup.sport))
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .offset(x = x)
                        .size(markerSize)
                        .testTag("timeline_marker_$index")
                        .clickable(role = Role.Button) { selected = index }
                        .semantics { contentDescription = description },
                    contentAlignment = Alignment.Center,
                ) {
                    val size = if (selected == index) 14.dp else 10.dp
                    Box(Modifier.size(size).background(markerColor(moment, colors), Radii.pillShape))
                }
            }
        }
        pulse?.quality?.let { quality ->
            val hint =
                when (quality) {
                    HealthQuality.OK -> null
                    HealthQuality.SPARSE_HR -> Res.string.timeline_quality_sparse
                    HealthQuality.GAPS -> Res.string.timeline_quality_gaps
                    HealthQuality.NO_HR -> Res.string.timeline_quality_no_hr
                }
            if (hint != null) {
                Text(
                    stringResource(hint),
                    style = AdvantlyTheme.typography.caption,
                    color = colors.text2,
                    modifier = Modifier.padding(top = Spacing.xs).testTag("timeline_quality"),
                )
            }
        }
        selected?.let { moments.getOrNull(it) }?.let { MomentCard(it, setup, pulse?.averageAround(it.moment.wallClockMs)) }
        SectionTitle(stringResource(Res.string.timeline_highlights))
        Column(Modifier.testTag("timeline_list"), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            moments.forEachIndexed { index, moment ->
                Text(
                    listOf(playTime(moment.playMs), momentTitle(moment, setup.sport), outcomeText(moment, setup)).joinToString(" · "),
                    style = AdvantlyTheme.typography.body,
                    color = if (selected == index) colors.text else colors.text2,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = Spacing.minTouchTarget)
                            .clickable(role = Role.Button) { selected = index }
                            .padding(vertical = Spacing.xs)
                            .testTag("timeline_item_$index"),
                )
            }
        }
    }
}

/** Karte zum angetippten Highlight: Arten, Stand vor dem Punkt, verwertet/abgewehrt, Ø-Puls (falls bekannt), Spielzeit. */
@Composable
private fun MomentCard(
    moment: TimelineMoment,
    setup: MatchSetup,
    averageBpm: Int?,
) {
    AdvantlyCard(Modifier.padding(top = Spacing.s).testTag("timeline_card")) {
        Text(momentTitle(moment, setup.sport), style = AdvantlyTheme.typography.label, color = AdvantlyTheme.colors.text)
        Text(
            stringResource(Res.string.timeline_score_before, stateScore(moment.before, setup.sport)),
            style = AdvantlyTheme.typography.body,
            color = AdvantlyTheme.colors.text,
            modifier = Modifier.testTag("timeline_card_score"),
        )
        Text(
            outcomeText(moment, setup),
            style = AdvantlyTheme.typography.body,
            color = AdvantlyTheme.colors.text2,
            modifier = Modifier.testTag("timeline_card_outcome"),
        )
        averageBpm?.let {
            Text(
                stringResource(Res.string.timeline_card_pulse, it),
                style = AdvantlyTheme.typography.body,
                color = AdvantlyTheme.colors.text2,
                modifier = Modifier.testTag("timeline_card_pulse"),
            )
        }
        Text(playTime(moment.playMs), style = AdvantlyTheme.typography.caption, color = AdvantlyTheme.colors.text2)
    }
}

private fun markerColor(
    moment: TimelineMoment,
    colors: AdvantlyColors,
): Color =
    when (KIND_ORDER.first { it in moment.moment.kinds }) {
        MomentKind.MATCH_WON, MomentKind.MATCH_POINT -> colors.brand
        MomentKind.SET_WON, MomentKind.SET_POINT -> colors.success
        MomentKind.COMEBACK -> colors.danger
        MomentKind.BREAK, MomentKind.BREAK_POINT -> colors.hint
        else -> colors.text2
    }

/** Spielzeit „m:ss“ ab Matchbeginn ohne Pausen. */
@Composable
private fun playTime(ms: Long): String {
    val seconds = ms / 1_000
    return stringResource(Res.string.timeline_clock, (seconds / 60).toInt(), (seconds % 60).toString().padStart(2, '0'))
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
