package eu.advantly.wear.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
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
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwipeToDismissBox
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import eu.advantly.core.model.Discipline
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.wear.R
import eu.advantly.wear.live.SetupStep
import eu.advantly.wear.live.WatchScreen
import eu.advantly.wear.match.FormatChoice
import eu.advantly.wear.match.SetupCatalog

/** Aktionen des Kurz-Wizards. */
interface SetupActions {
    fun selectSport(sport: Sport)

    fun selectDiscipline(discipline: Discipline)

    fun selectFormat(choice: FormatChoice)

    fun selectServer(
        server: Side,
        serverIndex: Int,
    ): Boolean

    fun back()

    fun setWorkoutEnabled(enabled: Boolean)

    /** „Games pro Satz“ (Tennis/Padel 1–6). */
    fun selectGamesPerSet(games: Int)

    /** „Aufschlag-Statistik erfassen“ (Tennis/Padel). */
    fun setServeStats(enabled: Boolean)
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
    BackHandler(onBack = actions::back)
    // je Schritt ein eigener Swipe-Zustand, sonst bliebe die Box nach dem ersten Zurück-Wischen „weggewischt“
    key(setup.step) {
        SwipeToDismissBox(onDismissed = actions::back) { isBackground ->
            if (!isBackground) SetupStepContent(setup, actions)
        }
    }
}

@Composable
private fun SetupStepContent(
    setup: WatchScreen.Setup,
    actions: SetupActions,
) {
    val listState = rememberScalingLazyListState()
    ScreenScaffold(scrollState = listState) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxWidth()) {
            when (setup.step) {
                SetupStep.SPORT -> sportStep(actions)
                SetupStep.DISCIPLINE -> disciplineStep(setup, actions)
                SetupStep.FORMAT -> formatStep(setup, actions)
                SetupStep.SERVER -> serverStep(setup, actions)
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
) {
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
                    Text(if (doubles) "$team · ${defaultPlayerName(side, index, doubles = true)}" else team)
                },
            )
        }
    }
    item {
        Text(stringResource(R.string.setup_server_hint), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
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
 * einem Tap): Tennis/Padel „Games pro Satz“ 1–6 als 2 × 3 runde Umschalter (nicht bei „Nur Tiebreak“) und der Schalter
 * „Aufschlag-Statistik erfassen“ (Default an).
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
        item {
            SwitchButton(
                checked = setup.serveStats,
                onCheckedChange = actions::setServeStats,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.setup_serve_stats), maxLines = 2) },
                secondaryLabel = { Text(stringResource(R.string.setup_serve_stats_hint), maxLines = 2) },
            )
        }
    }
}
