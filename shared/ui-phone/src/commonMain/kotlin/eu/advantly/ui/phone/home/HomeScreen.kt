package eu.advantly.ui.phone.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.presentation.match.FavoriteLineup
import eu.advantly.presentation.match.LiveMatchSnapshot
import eu.advantly.presentation.match.MatchSetup
import eu.advantly.presentation.match.MatchSummary
import eu.advantly.presentation.match.WallClock
import eu.advantly.ui.phone.app.AdvantlyAppModel
import eu.advantly.ui.phone.app.Overlay
import eu.advantly.ui.phone.common.AdvantlyCard
import eu.advantly.ui.phone.common.SectionTitle
import eu.advantly.ui.phone.common.SportIcon
import eu.advantly.ui.phone.common.StatTile
import eu.advantly.ui.phone.common.durationText
import eu.advantly.ui.phone.common.formatLabel
import eu.advantly.ui.phone.common.sportColor
import eu.advantly.ui.phone.common.sportName
import eu.advantly.ui.phone.common.teamName
import eu.advantly.ui.phone.designsystem.AdvantlyIcons
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.designsystem.Radii
import eu.advantly.ui.phone.designsystem.Spacing
import eu.advantly.ui.phone.live.compactScore
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.home_empty
import eu.advantly.ui.phone.resources.home_greeting
import eu.advantly.ui.phone.resources.home_greeting_named
import eu.advantly.ui.phone.resources.home_like_last_time
import eu.advantly.ui.phone.resources.home_new_match
import eu.advantly.ui.phone.resources.home_new_match_sub
import eu.advantly.ui.phone.resources.home_ready
import eu.advantly.ui.phone.resources.home_remove_favorite
import eu.advantly.ui.phone.resources.home_resume
import eu.advantly.ui.phone.resources.home_resume_finished
import eu.advantly.ui.phone.resources.home_section_favorites
import eu.advantly.ui.phone.resources.home_section_quick
import eu.advantly.ui.phone.resources.home_section_week
import eu.advantly.ui.phone.resources.home_versus
import eu.advantly.ui.phone.resources.home_week_matches
import eu.advantly.ui.phone.resources.home_week_time
import eu.advantly.ui.phone.resources.home_week_wins
import org.jetbrains.compose.resources.stringResource

private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000

/**
 * H1 Start (04 §4.1): Begrüßung, Resume-Karte (laufendes Match), „Neues Match“, Schnellstart („Wie letztes Mal“,
 * Favoriten) und Wochenbilanz aus dem Verlauf.
 */
@Composable
fun HomeScreen(
    model: AdvantlyAppModel,
    modifier: Modifier = Modifier,
    clock: WallClock = WallClock.System,
) {
    val self by model.players.self.collectAsState()
    val active by model.live.active.collectAsState()
    val last by model.players.lastSetup.collectAsState()
    val favorites by model.players.favorites.collectAsState()
    val history by model.history.matches.collectAsState()
    val running = active?.state?.status?.let { it != MatchStatus.FINISHED } == true
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.screenEdgeCompact)
            .testTag("home"),
    ) {
        Text(
            if (self.displayName.isBlank()) {
                stringResource(
                    Res.string.home_greeting,
                )
            } else {
                stringResource(Res.string.home_greeting_named, self.displayName)
            },
            style = AdvantlyTheme.typography.headline,
            color = AdvantlyTheme.colors.text,
            modifier = Modifier.padding(top = Spacing.xl, bottom = Spacing.l).semantics { heading() },
        )
        active?.let { ResumeCard(it, onClick = { model.navigator.push(Overlay.Live) }) }
        if (!running) {
            NewMatchHero(onClick = model::onPlay)
            if (last != null || favorites.isNotEmpty()) {
                SectionTitle(stringResource(Res.string.home_section_quick))
                last?.let { setup ->
                    QuickStartCard(
                        setup = setup,
                        title = stringResource(Res.string.home_like_last_time),
                        tag = "quick_last",
                        onStart = { model.startMatch(setup) },
                    )
                }
                if (favorites.isNotEmpty()) SectionTitle(stringResource(Res.string.home_section_favorites))
                favorites.forEachIndexed { index, favorite ->
                    FavoriteCard(favorite, index, onStart = { model.startMatch(favorite.setup) }, onRemove = {
                        model.players.removeFavorite(favorite.id)
                    })
                }
            }
        }
        WeekSummary(history, clock.nowMs())
        if (history.isEmpty() && active == null) {
            Text(
                stringResource(Res.string.home_empty),
                style = AdvantlyTheme.typography.body,
                color = AdvantlyTheme.colors.text2,
                modifier = Modifier.padding(vertical = Spacing.l),
            )
        }
    }
}

@Composable
private fun ResumeCard(
    snapshot: LiveMatchSnapshot,
    onClick: () -> Unit,
) {
    val sport = snapshot.setup.sport
    val finished = snapshot.state.status == MatchStatus.FINISHED
    AdvantlyCard(Modifier.testTag("resume_card").padding(bottom = Spacing.m), onClick = onClick, border = AdvantlyTheme.colors.brand) {
        Text(
            stringResource(if (finished) Res.string.home_resume_finished else Res.string.home_resume).uppercase(),
            style = AdvantlyTheme.typography.overline,
            color = AdvantlyTheme.colors.brand,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            SportIcon(sport, size = 20.dp)
            Text(
                teamName(snapshot.setup, Side.A) + " – " + teamName(snapshot.setup, Side.B),
                style = AdvantlyTheme.typography.label,
                color = AdvantlyTheme.colors.text,
                modifier = Modifier.weight(1f),
            )
        }
        Text(compactScore(snapshot), style = AdvantlyTheme.typography.scoreS, color = AdvantlyTheme.colors.text)
    }
}

/** Primäraktion „Neues Match“ (Mockup H1 Hero). */
@Composable
private fun NewMatchHero(onClick: () -> Unit) {
    val colors = AdvantlyTheme.colors
    Column(
        Modifier
            .testTag("home_new_match")
            .fillMaxWidth()
            .heightIn(min = 120.dp)
            .background(colors.brand, Radii.sheetShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(stringResource(Res.string.home_ready).uppercase(), style = AdvantlyTheme.typography.overline, color = colors.onBrand)
        Text(stringResource(Res.string.home_new_match), style = AdvantlyTheme.typography.headline, color = colors.onBrand)
        Text(stringResource(Res.string.home_new_match_sub), style = AdvantlyTheme.typography.body, color = colors.onBrand)
    }
}

/** Ein-Tap-Start: Sport, Gegner, Format. */
@Composable
private fun QuickStartCard(
    setup: MatchSetup,
    title: String,
    tag: String,
    onStart: () -> Unit,
    trailing: @Composable () -> Unit = {
        Icon(AdvantlyIcons.Replay, contentDescription = null, tint = AdvantlyTheme.colors.text2)
    },
) {
    val sport = setup.sport
    Row(
        Modifier
            .testTag(tag)
            .padding(bottom = Spacing.s)
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .background(sportColor(sport).copy(alpha = 0.10f), Radii.cardShape)
            .clickable(role = Role.Button, onClick = onStart)
            .padding(horizontal = Spacing.l, vertical = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        SportIcon(sport, size = 28.dp)
        Column(Modifier.weight(1f)) {
            Text(title, style = AdvantlyTheme.typography.label, color = AdvantlyTheme.colors.text)
            Text(
                stringResource(sportName(sport)) + " · " + formatLabel(setup.format) + " · " +
                    stringResource(Res.string.home_versus, teamName(setup, Side.B)),
                style = AdvantlyTheme.typography.caption,
                color = AdvantlyTheme.colors.text2,
            )
        }
        trailing()
    }
}

@Composable
private fun FavoriteCard(
    favorite: FavoriteLineup,
    index: Int,
    onStart: () -> Unit,
    onRemove: () -> Unit,
) {
    val setup = favorite.setup
    val removeLabel = stringResource(Res.string.home_remove_favorite)
    QuickStartCard(
        setup = setup,
        title = stringResource(sportName(setup.sport)),
        tag = "quick_fav_$index",
        onStart = onStart,
        trailing = {
            Box(
                Modifier
                    .testTag("quick_fav_remove_$index")
                    .size(Spacing.minTouchTarget)
                    .clickable(role = Role.Button, onClick = onRemove)
                    .semantics { contentDescription = removeLabel },
                contentAlignment = Alignment.Center,
            ) {
                Icon(AdvantlyIcons.Close, contentDescription = null, tint = AdvantlyTheme.colors.text2)
            }
        },
    )
}

/** Wochenbilanz (letzte 7 Tage): Matches, Siege, Spielzeit. */
@Composable
private fun WeekSummary(
    history: List<MatchSummary>,
    nowMs: Long,
) {
    val week = history.filter { nowMs - it.finishedAtMs <= WEEK_MS }
    if (week.isEmpty()) return
    SectionTitle(stringResource(Res.string.home_section_week))
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.testTag("week_summary")) {
        StatTile(week.size.toString(), stringResource(Res.string.home_week_matches), Modifier.weight(1f))
        StatTile(week.count { it.result.winner == Side.A }.toString(), stringResource(Res.string.home_week_wins), Modifier.weight(1f))
        StatTile(durationText(week.sumOf { it.durationMs }), stringResource(Res.string.home_week_time), Modifier.weight(1f))
    }
}
