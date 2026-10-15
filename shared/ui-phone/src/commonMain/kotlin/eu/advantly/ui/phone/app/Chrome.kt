package eu.advantly.ui.phone.app

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.advantly.core.model.Side
import eu.advantly.presentation.match.LiveMatchSnapshot
import eu.advantly.ui.phone.common.SportIcon
import eu.advantly.ui.phone.common.teamName
import eu.advantly.ui.phone.designsystem.AdvantlyIcons
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.designsystem.Radii
import eu.advantly.ui.phone.designsystem.Spacing
import eu.advantly.ui.phone.live.compactScore
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.app_version
import eu.advantly.ui.phone.resources.coming_soon
import eu.advantly.ui.phone.resources.coming_soon_friends
import eu.advantly.ui.phone.resources.coming_soon_profile
import eu.advantly.ui.phone.resources.mini_scorebar_a11y
import eu.advantly.ui.phone.resources.tab_friends
import eu.advantly.ui.phone.resources.tab_history
import eu.advantly.ui.phone.resources.tab_home
import eu.advantly.ui.phone.resources.tab_play
import eu.advantly.ui.phone.resources.tab_profile
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Bottom-Navigation mit fünf Zielen; die Mitte „Spielen (+)“ ist die Primäraktion (04 §2.1). */
@Composable
fun TabBar(
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AdvantlyTheme.colors
    Column(modifier.fillMaxWidth().background(colors.surface)) {
        HorizontalDivider(color = colors.line)
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
            AppTab.entries.forEach { tab ->
                val isSelected = tab == selected && tab != AppTab.Play
                Column(
                    Modifier
                        .weight(1f)
                        .testTag("tab_${tab.name.lowercase()}")
                        .heightIn(min = 64.dp)
                        .clickable(role = Role.Tab) { onSelect(tab) }
                        .semantics { this.selected = isSelected }
                        .padding(vertical = Spacing.xs),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (tab == AppTab.Play) {
                        Box(Modifier.size(40.dp).background(colors.brand, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(AdvantlyIcons.Add, contentDescription = null, tint = colors.onBrand)
                        }
                    } else {
                        Icon(tabIcon(tab), contentDescription = null, tint = if (isSelected) colors.brand else colors.text2)
                    }
                    Text(
                        stringResource(tabLabel(tab)),
                        style = AdvantlyTheme.typography.caption.copy(fontSize = 11.sp),
                        color = if (isSelected) colors.text else colors.text2,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private fun tabIcon(tab: AppTab): ImageVector =
    when (tab) {
        AppTab.Home -> AdvantlyIcons.Home
        AppTab.History -> AdvantlyIcons.History
        AppTab.Play -> AdvantlyIcons.Add
        AppTab.Friends -> AdvantlyIcons.Group
        AppTab.Profile -> AdvantlyIcons.Person
    }

private fun tabLabel(tab: AppTab): StringResource =
    when (tab) {
        AppTab.Home -> Res.string.tab_home
        AppTab.History -> Res.string.tab_history
        AppTab.Play -> Res.string.tab_play
        AppTab.Friends -> Res.string.tab_friends
        AppTab.Profile -> Res.string.tab_profile
    }

/** Mini-Scorebar über der Tab-Leiste („Now Playing“, 04 §2.1): Tap → Live-Score. */
@Composable
fun MiniScoreBar(
    snapshot: LiveMatchSnapshot,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AdvantlyTheme.colors
    val teams = teamName(snapshot.setup, Side.A) + " – " + teamName(snapshot.setup, Side.B)
    val score = compactScore(snapshot)
    val a11y = stringResource(Res.string.mini_scorebar_a11y, teams, score)
    Row(
        modifier
            .testTag("mini_scorebar")
            .fillMaxWidth()
            .padding(horizontal = Spacing.s, vertical = Spacing.xs)
            .heightIn(min = 56.dp)
            .background(colors.surface2, Radii.cardShape)
            .clickable(role = Role.Button, onClickLabel = a11y, onClick = onClick)
            .padding(horizontal = Spacing.m, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        SportIcon(snapshot.setup.sport, size = 22.dp)
        Column(Modifier.weight(1f)) {
            Text(teams, style = AdvantlyTheme.typography.label, color = colors.text, maxLines = 1)
            Text(score, style = AdvantlyTheme.typography.scoreS, color = colors.text, modifier = Modifier.testTag("mini_scorebar_score"))
        }
        Icon(AdvantlyIcons.ChevronRight, contentDescription = null, tint = colors.text2)
    }
}

/** Platzhalter für Freunde/Profil in P1 („kommt bald“). */
@Composable
fun ComingSoonScreen(
    tab: AppTab,
    versionName: String,
    modifier: Modifier = Modifier,
) {
    val colors = AdvantlyTheme.colors
    Column(
        modifier.fillMaxSize().padding(Spacing.xl).testTag("coming_soon_${tab.name.lowercase()}"),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Text(
            stringResource(tabLabel(tab)),
            style = AdvantlyTheme.typography.headline,
            color = colors.text,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(Res.string.coming_soon).uppercase(), style = AdvantlyTheme.typography.overline, color = colors.brand)
        Text(
            stringResource(if (tab == AppTab.Friends) Res.string.coming_soon_friends else Res.string.coming_soon_profile),
            style = AdvantlyTheme.typography.body,
            color = colors.text2,
        )
        if (tab == AppTab.Profile && versionName.isNotBlank()) {
            Text(stringResource(Res.string.app_version, versionName), style = AdvantlyTheme.typography.caption, color = colors.text2)
        }
    }
}
