package eu.advantly.ui.phone.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.PlayerId
import eu.advantly.presentation.friends.FriendEntry
import eu.advantly.presentation.match.MatchHistoryPort
import eu.advantly.presentation.match.MatchSetup
import eu.advantly.presentation.match.PlayerAssignmentFailure
import eu.advantly.presentation.match.PlayersPort
import eu.advantly.ui.phone.designsystem.Spacing
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.assign_change
import eu.advantly.ui.phone.resources.assign_failed
import eu.advantly.ui.phone.resources.assign_failed_occupied
import eu.advantly.ui.phone.resources.assign_failed_owner
import eu.advantly.ui.phone.resources.assign_failed_person
import eu.advantly.ui.phone.resources.assign_failed_slot
import eu.advantly.ui.phone.resources.assign_new_player
import eu.advantly.ui.phone.resources.assign_player
import eu.advantly.ui.phone.resources.assign_player_name
import eu.advantly.ui.phone.resources.assign_remove
import eu.advantly.ui.phone.resources.wizard_friends_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Dieselbe Personenwahl in Live-Ansicht und Ergebnis; keine Zählrechte erforderlich. Neben Mitspielern und Gästen stehen
 * „Du“ und Freunde mit Konto ([friends], wie im Assistenten) zur Wahl (plan-0.6.3 §3.4); belegte Personen nie.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaceholderAssignments(
    matchId: MatchId,
    setup: MatchSetup,
    history: MatchHistoryPort,
    players: PlayersPort,
    friends: List<FriendEntry> = emptyList(),
) {
    if (!history.canAssignPlayer(matchId)) return
    val slots =
        (setup.lineup.a + setup.lineup.b).filter {
            setup.players.firstOrNull { person -> person.id == it }?.isPlaceholder == true ||
                it in setup.assignedPlayers
        }
    var choosing by remember(matchId) { mutableStateOf<PlayerId?>(null) }
    Column {
        slots.forEach { slot ->
            TextButton(onClick = { choosing = slot }, modifier = Modifier.fillMaxWidth().testTag("assign_${slot.value}")) {
                Text(
                    stringResource(
                        if (slot in
                            setup.assignedPlayers
                        ) {
                            Res.string.assign_change
                        } else {
                            Res.string.assign_player
                        },
                        playerName(setup, slot),
                    ),
                )
            }
        }
    }
    val slot = choosing ?: return
    val guests by players.guests.collectAsState()
    var name by remember(slot) { mutableStateOf("") }
    var failure by remember(slot) { mutableStateOf<StringResource?>(null) }
    val occupied = (setup.lineup.a + setup.lineup.b).map { setup.assignedPlayers[it]?.id ?: it }.toSet()
    val self = players.self.value.id
    // Der eigene Spieler sitzt auch über einen Alias (z. B. alter Uhr-Self); dann nicht noch einmal anbieten.
    val selfSeated = occupied.any { it == self || it in LocalSelfAliases.current }
    // Auch über Gast-Zuordnung: Sitzt Test1 (verknüpft mit Anja), ist Anja belegt.
    val seatedFriends = remember(occupied) { occupied.mapNotNull(players::linkedProfile).toSet() }
    val offeredFriends = friends.filter { !it.alias.isNullOrBlank() && it.profileId !in seatedFriends }
    val offeredIds = offeredFriends.mapTo(HashSet()) { it.profileId }
    val choices =
        (players.recentPlayers(setup.sport) + guests + players.self.value)
            .distinctBy { it.id }
            .filterNot { it.id in occupied || it.isPlaceholder || (it.id == self && selfSeated) }
            // „Du“ hat lokal keinen Namen; Freunde stehen unten mit ihrem aktuellen Alias.
            .filter { (it.displayName.isNotBlank() || it.id == self) && players.linkedProfile(it.id) !in offeredIds }

    fun assign(id: PlayerId?) {
        if (history.assignPlayer(matchId, slot, id)) {
            choosing = null
        } else {
            failure =
                when (history.assignmentFailure(matchId, slot, id)) {
                    PlayerAssignmentFailure.NOT_OWNER -> Res.string.assign_failed_owner
                    PlayerAssignmentFailure.INVALID_SLOT -> Res.string.assign_failed_slot
                    PlayerAssignmentFailure.INVALID_PLAYER -> Res.string.assign_failed_person
                    PlayerAssignmentFailure.OCCUPIED -> Res.string.assign_failed_occupied
                    else -> Res.string.assign_failed
                }
        }
    }
    ModalBottomSheet(onDismissRequest = { choosing = null }) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screenEdgeCompact, vertical = Spacing.s)
                .testTag("assign_player_sheet"),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            SectionTitle(
                stringResource(
                    if (slot in
                        setup.assignedPlayers
                    ) {
                        Res.string.assign_change
                    } else {
                        Res.string.assign_player
                    },
                    playerName(setup, slot),
                ),
            )
            if (slot in setup.assignedPlayers) {
                TextButton(onClick = { assign(null) }, modifier = Modifier.fillMaxWidth().testTag("assign_remove")) {
                    Text(stringResource(Res.string.assign_remove))
                }
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(Res.string.assign_player_name)) },
                modifier = Modifier.fillMaxWidth().testTag("assign_name"),
            )
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { players.tryAddGuest(name)?.let { assign(it.id) } },
                modifier = Modifier.fillMaxWidth().testTag("assign_create"),
            ) { Text(stringResource(Res.string.assign_new_player)) }
            failure?.let { Text(stringResource(it)) }
            choices.map { it to playerName(it) }.filter { it.second.contains(name, ignoreCase = true) }.forEach { (person, label) ->
                TextButton(
                    onClick = { assign(person.id) },
                    modifier = Modifier.fillMaxWidth().testTag("assign_person_${person.id.value}"),
                ) {
                    Text(label)
                }
            }
            val matching = offeredFriends.filter { it.alias.orEmpty().contains(name, ignoreCase = true) }
            if (matching.isNotEmpty()) SectionTitle(stringResource(Res.string.wizard_friends_title))
            matching.forEach { friend ->
                TextButton(
                    // Lokale Spieler-ID wie im Assistenten; der Upload verknüpft den Platz über die Kontozuordnung.
                    onClick = { players.addFriend(friend.profileId, friend.alias.orEmpty())?.let { assign(it.id) } },
                    modifier = Modifier.fillMaxWidth().testTag("assign_friend_${friend.profileId}"),
                ) {
                    Text(friend.alias.orEmpty())
                }
            }
        }
    }
}
