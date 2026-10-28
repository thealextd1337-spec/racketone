package eu.advantly.wear.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwipeToDismissBox
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import eu.advantly.core.model.Discipline
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.ServeTracking
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.wear.R
import eu.advantly.wear.live.SetupStep
import eu.advantly.wear.live.WatchScreen
import eu.advantly.wear.match.FormatChoice
import eu.advantly.wear.match.PlayerSlot
import eu.advantly.wear.match.SetupCatalog

/** Aktionen des Kurz-Wizards. */
interface SetupActions {
    fun selectPlayer(
        slot: PlayerSlot,
        player: PlayerId?,
    )

    fun selectSport(sport: Sport)

    fun selectDiscipline(discipline: Discipline)

    fun selectFormat(choice: FormatChoice)

    fun selectServer(
        server: Side,
        serverIndex: Int,
    ): Boolean

    fun startMatch(): Boolean = selectServer(Side.A, 0)

    fun back()

    fun setWorkoutEnabled(enabled: Boolean)

    /** „Games pro Satz“ (Tennis/Padel 1–6). */
    fun selectGamesPerSet(games: Int)

    /** „Aufschläge“: Ja / Nur Doppelfehler / Nein (Tennis/Padel). */
    fun setServeTracking(tracking: ServeTracking)
}

/**
 * Kurz-Wizard (04 W2, §3.1): Sport → Einzel/Doppel → Format-Vorschlag → Erstaufschläger (Tippen startet).
 * Wischen nach rechts = einen Schritt zurück (Swipe-to-dismiss der Activity ist für den Score-Screen aus, deshalb hier
 * in Compose).
 */
@Composable
fun SetupScreen(
    setup: WatchScreen.Setup,
    actions: SetupActions,
) {
    var choosing by remember(setup.step) { mutableStateOf<PlayerSlot?>(null) }
    val goBack = { if (choosing != null) choosing = null else actions.back() }
    BackHandler(onBack = goBack)
    // je Schritt ein eigener Swipe-Zustand, sonst bliebe die Box nach dem ersten Zurück-Wischen „weggewischt“
    key(setup.step, choosing) {
        SwipeToDismissBox(onDismissed = goBack) { isBackground ->
            if (!isBackground) SetupStepContent(setup, actions, choosing) { choosing = it }
        }
    }
}

@Composable
private fun SetupStepContent(
    setup: WatchScreen.Setup,
    actions: SetupActions,
    choosing: PlayerSlot?,
    choose: (PlayerSlot?) -> Unit,
) {
    val listState = rememberScalingLazyListState()
    ScreenScaffold(scrollState = listState) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxWidth()) {
            val slot = choosing
            if (slot != null) {
                playerPicker(setup, actions, slot) { choose(null) }
            } else {
                when (setup.step) {
                    SetupStep.SPORT -> sportStep(actions)
                    SetupStep.DISCIPLINE -> disciplineStep(setup, actions)
                    SetupStep.FORMAT -> formatStep(setup, actions)
                    SetupStep.SERVER -> serverStep(setup, actions, choose)
                }
            }
        }
    }
}

private fun ScalingLazyListScope.sportStep(actions: SetupActions) {
    item { ListHeader { Text(stringResource(R.string.setup_sport_title)) } }
    SetupCatalog.sports.forEach { sport ->
        item {
            Button(
                onClick = { actions.selectSport(sport) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.filledTonalButtonColors(),
                border = BorderStroke(2.dp, sportColor(sport)),
                label = { Text(sportName(sport)) },
            )
        }
    }
}

private fun ScalingLazyListScope.disciplineStep(
    setup: WatchScreen.Setup,
    actions: SetupActions,
) {
    item { ListHeader { Text(stringResource(R.string.setup_discipline_title), textAlign = TextAlign.Center) } }
    val preferred = setup.sport?.let(SetupCatalog::defaultDiscipline)
    setup.disciplines.forEach { discipline ->
        item {
            Button(
                onClick = { actions.selectDiscipline(discipline) },
                modifier = Modifier.fillMaxWidth(),
                colors = if (discipline == preferred) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
                label = {
                    Text(stringResource(if (discipline == Discipline.DOUBLES) R.string.discipline_doubles else R.string.discipline_singles))
                },
            )
        }
    }
}

private fun ScalingLazyListScope.formatStep(
    setup: WatchScreen.Setup,
    actions: SetupActions,
) {
    item { ListHeader { Text(stringResource(R.string.setup_format_title)) } }
    if (setup.defaultFormat == null) {
        // Tischtennis: keine Vorauswahl, die Satzanzahl ist Pflicht (02 §5.1.1, U14)
        item {
            Text(stringResource(R.string.setup_format_required), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
    }
    setup.formats.forEach { choice ->
        item {
            val isDefault = choice == setup.defaultFormat
            Button(
                onClick = { actions.selectFormat(choice) },
                modifier = Modifier.fillMaxWidth(),
                colors = if (isDefault) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
                label = { Text(formatLabel(choice), maxLines = 2) },
                secondaryLabel = if (isDefault) ({ Text(stringResource(R.string.setup_default)) }) else null,
            )
        }
    }
}

private fun ScalingLazyListScope.serverStep(
    setup: WatchScreen.Setup,
    actions: SetupActions,
    choosePlayer: (PlayerSlot) -> Unit,
) {
    playerChoices(setup, choosePlayer)
    val liveChoice = setup.discipline == Discipline.DOUBLES && setup.sport?.isTennisLike == true
    if (liveChoice) {
        item {
            Button(
                onClick = { actions.startMatch() },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.setup_start_match)) },
            )
        }
    } else {
        item { ListHeader { Text(stringResource(R.string.setup_server_title), textAlign = TextAlign.Center) } }
        // Doppel: konkreter Erstaufschläger (bestimmt Rotation bzw. Badminton-Feld, 02 §3.3); Einzel: Wir/Gegner
        val doubles = setup.discipline == Discipline.DOUBLES
        val choices = listOf(Side.A, Side.B).flatMap { side -> (0 until if (doubles) 2 else 1).map { side to it } }
        choices.forEach { (side, index) ->
            item {
                Button(
                    onClick = { actions.selectServer(side, index) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    border = BorderStroke(2.dp, teamColor(side)),
                    label = {
                        val team = stringResource(if (side == Side.A) R.string.label_us else R.string.label_them)
                        val slot =
                            when {
                                side == Side.A && index == 1 -> PlayerSlot.PARTNER
                                side == Side.B && index == 1 -> PlayerSlot.OPPONENT_2
                                side == Side.B -> PlayerSlot.OPPONENT_1
                                else -> null
                            }
                        val name =
                            setup.recentPlayers.firstOrNull { it.id == setup.selectedPlayers[slot] }?.displayName
                                ?: defaultPlayerName(side, index, doubles)
                        Text("$team · $name", maxLines = 2)
                    },
                )
            }
        }
        item {
            Text(stringResource(R.string.setup_server_hint), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
    }
    matchOptions(setup, actions)
    item {
        SwitchButton(
            checked = setup.workoutEnabled,
            onCheckedChange = actions::setWorkoutEnabled,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.workout_toggle)) },
        )
    }
}

/**
 * Match-Optionen im letzten Schritt (W2-Vertrag §5, unter den Startknöpfen – ohne Änderung startet der Standard mit
 * einem Tap): Tennis/Padel „Games pro Satz“ 1–6 als 2 × 3 runde Umschalter (nicht bei „Nur Tiebreak“) und die Wahl
 * „Aufschläge“ (Ja / Nur Doppelfehler / Nein, Default „Ja“).
 */
private fun ScalingLazyListScope.matchOptions(
    setup: WatchScreen.Setup,
    actions: SetupActions,
) {
    if (setup.gamesOptions.isNotEmpty()) {
        item { ListHeader { Text(stringResource(R.string.setup_games_per_set)) } }
        item { NumberChips(setup.gamesOptions, setup.gamesPerSet, actions::selectGamesPerSet) }
        if (setup.gamesPerSet == 1) {
            item {
                Text(
                    stringResource(R.string.setup_games_one_hint),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
    if (setup.serveStatsAvailable) {
        item { ListHeader { Text(stringResource(R.string.setup_serve_tracking)) } }
        SERVE_TRACKING_LABELS.forEach { (tracking, label) ->
            item {
                RadioButton(
                    selected = setup.serveTracking == tracking,
                    onSelect = { actions.setServeTracking(tracking) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(label), maxLines = 2) },
                )
            }
        }
        item {
            Text(
                stringResource(R.string.setup_serve_tracking_hint),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Reihenfolge und Labels der Wahl „Aufschläge“ (wie im Handy-Assistenten). */
private val SERVE_TRACKING_LABELS =
    listOf(
        ServeTracking.FULL to R.string.setup_serve_full,
        ServeTracking.DOUBLE_FAULTS_ONLY to R.string.setup_serve_double_faults_only,
        ServeTracking.OFF to R.string.setup_serve_off,
    )

/** Optionale Spielerwahl vor den Startknöpfen. Jede Person ist ein eigenes Element der Rotary-Liste. */
private fun ScalingLazyListScope.playerChoices(
    setup: WatchScreen.Setup,
    choose: (PlayerSlot) -> Unit,
) {
    val slots = if (setup.discipline == Discipline.DOUBLES) PlayerSlot.entries else listOf(PlayerSlot.OPPONENT_1)
    slots.forEach { slot ->
        item {
            Button(
                onClick = { choose(slot) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(slotName(slot, setup.discipline == Discipline.DOUBLES)) },
                secondaryLabel = {
                    Text(
                        setup.recentPlayers.firstOrNull { it.id == setup.selectedPlayers[slot] }?.displayName
                            ?: stringResource(R.string.setup_unassigned),
                    )
                },
            )
        }
    }
}

private fun ScalingLazyListScope.playerPicker(
    setup: WatchScreen.Setup,
    actions: SetupActions,
    slot: PlayerSlot,
    close: () -> Unit,
) {
    item { ListHeader { Text(slotName(slot, setup.discipline == Discipline.DOUBLES)) } }
    item {
        Button(
            onClick = {
                actions.selectPlayer(slot, null)
                close()
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.setup_unassigned)) },
        )
    }
    setup.recentPlayers.filter { it.id !in setup.selectedPlayers.values || it.id == setup.selectedPlayers[slot] }.forEach { player ->
        item {
            Button(
                onClick = {
                    actions.selectPlayer(slot, player.id)
                    close()
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(player.displayName, maxLines = 2) },
            )
        }
    }
    if (setup.recentPlayers.isEmpty()) item { Text(stringResource(R.string.setup_no_players), textAlign = TextAlign.Center) }
}

@Composable
private fun slotName(
    slot: PlayerSlot,
    doubles: Boolean,
): String =
    stringResource(
        when (slot) {
            PlayerSlot.PARTNER -> R.string.player_partner
            PlayerSlot.OPPONENT_1 -> if (doubles) R.string.player_opponent_1 else R.string.player_opponent
            PlayerSlot.OPPONENT_2 -> R.string.player_opponent_2
        },
    )
