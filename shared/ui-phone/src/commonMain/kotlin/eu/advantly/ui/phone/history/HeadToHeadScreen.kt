package eu.advantly.ui.phone.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.PlayerId
import eu.advantly.presentation.match.MatchHistoryPort
import eu.advantly.presentation.match.MatchSetup
import eu.advantly.presentation.stats.HeadToHead
import eu.advantly.presentation.stats.HistoryPerson
import eu.advantly.presentation.stats.HistoryRole
import eu.advantly.presentation.stats.headToHead
import eu.advantly.presentation.stats.historyPeople
import eu.advantly.ui.phone.common.AdvantlyCard
import eu.advantly.ui.phone.common.LocalFriendAliases
import eu.advantly.ui.phone.common.LocalSelfAliases
import eu.advantly.ui.phone.common.LocalSelfId
import eu.advantly.ui.phone.common.SectionTitle
import eu.advantly.ui.phone.common.TopBar
import eu.advantly.ui.phone.common.safeContentPadding
import eu.advantly.ui.phone.common.sportName
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.designsystem.Spacing
import eu.advantly.ui.phone.platform.formatDateTime
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.action_back
import eu.advantly.ui.phone.resources.h2h_against
import eu.advantly.ui.phone.resources.h2h_empty
import eu.advantly.ui.phone.resources.h2h_friend_note
import eu.advantly.ui.phone.resources.h2h_local_note
import eu.advantly.ui.phone.resources.h2h_recent
import eu.advantly.ui.phone.resources.h2h_record
import eu.advantly.ui.phone.resources.h2h_title
import eu.advantly.ui.phone.resources.h2h_with
import org.jetbrains.compose.resources.stringResource

/**
 * Personen-Links im Detail verwenden dieselben aufgelösten Identitäten wie Filter und H2H; bei Freunden (Konto bekannt)
 * öffnet der Aufrufer die Bilanz über das Konto, damit sie in jedem Match dieselbe ist.
 */
@Composable
internal fun HeadToHeadLinks(
    setup: MatchSetup,
    onOpen: (HistoryPerson) -> Unit,
) {
    val selfIds = LocalSelfAliases.current + listOfNotNull(LocalSelfId.current)
    historyPeople(setup, selfIds).forEach { person ->
        TextButton(
            onClick = { onOpen(person) },
            modifier = Modifier.fillMaxWidth().testTag("h2h_person_${person.player.id.value}"),
        ) {
            Text(
                stringResource(
                    if (person.role ==
                        HistoryRole.OPPONENT
                    ) {
                        Res.string.h2h_against
                    } else {
                        Res.string.h2h_with
                    },
                    person.player.displayName,
                ),
            )
        }
    }
}

/** Bilanz einer Person ([player]) oder eines Freundes über sein Konto ([profile], dann darf [player] fehlen). */
@Composable
internal fun HeadToHeadScreen(
    player: PlayerId?,
    history: MatchHistoryPort,
    onBack: () -> Unit,
    onOpenMatch: (MatchId) -> Unit,
    fallbackName: String = "",
    profile: String? = null,
) {
    val matches by history.matches.collectAsState()
    val selfIds = LocalSelfAliases.current + listOfNotNull(LocalSelfId.current)
    val friendAlias = profile?.let { LocalFriendAliases.current[it] }
    val name =
        remember(matches, player, profile, selfIds, fallbackName, friendAlias) {
            friendAlias ?: matches
                .asSequence()
                .flatMap { historyPeople(it.setup, selfIds).asSequence() }
                .firstOrNull { if (profile != null) it.profile == profile else it.player.id == player }
                ?.player
                ?.displayName
                .orEmpty()
                .ifBlank { fallbackName }
        }
    Column(Modifier.fillMaxSize().safeContentPadding().testTag("h2h")) {
        TopBar(stringResource(Res.string.h2h_title), onBack, stringResource(Res.string.action_back))
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(Spacing.screenEdgeCompact),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            Text(name, style = AdvantlyTheme.typography.headline, color = AdvantlyTheme.colors.text)
            Text(
                stringResource(
                    if (profile !=
                        null
                    ) {
                        Res.string.h2h_friend_note
                    } else {
                        Res.string.h2h_local_note
                    },
                ),
                style = AdvantlyTheme.typography.caption,
                color = AdvantlyTheme.colors.text2,
            )
            HistoryRole.entries.forEach { role ->
                val stats =
                    remember(matches, player, profile, role, selfIds) {
                        if (profile != null) {
                            headToHead(matches, profile, role, selfIds)
                        } else if (player != null) {
                            headToHead(matches, player, role, selfIds)
                        } else {
                            HeadToHead(0, 0, 0, emptyList())
                        }
                    }
                SectionTitle(stringResource(if (role == HistoryRole.OPPONENT) Res.string.h2h_against else Res.string.h2h_with, name))
                Text(
                    stringResource(Res.string.h2h_record, stats.wins, stats.losses, stats.draws),
                    style = AdvantlyTheme.typography.title,
                    color = AdvantlyTheme.colors.text,
                    modifier = Modifier.testTag("h2h_record_${role.name}"),
                )
                if (stats.total == 0) {
                    Text(stringResource(Res.string.h2h_empty), color = AdvantlyTheme.colors.text2)
                } else {
                    Text(stringResource(Res.string.h2h_recent), style = AdvantlyTheme.typography.label, color = AdvantlyTheme.colors.text2)
                    stats.recent.forEach { match ->
                        AdvantlyCard(
                            modifier = Modifier.fillMaxWidth().testTag("h2h_match_${match.matchId.value}"),
                            onClick = { onOpenMatch(match.matchId) },
                        ) {
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                ResultBadge(match)
                                Text(
                                    stringResource(sportName(match.setup.sport)) + " · " + setsLine(match),
                                    color = AdvantlyTheme.colors.text,
                                )
                                Text(formatDateTime(match.finishedAtMs), color = AdvantlyTheme.colors.text2)
                            }
                        }
                    }
                }
            }
        }
    }
}
