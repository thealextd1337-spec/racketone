package eu.advantly.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import eu.advantly.core.model.Discipline
import eu.advantly.core.model.MatchId
import eu.advantly.wear.BuildConfig
import eu.advantly.wear.R
import eu.advantly.wear.live.WatchScreen
import eu.advantly.wear.workout.WorkoutPermissions

/** Aktionen der Startliste. */
interface HomeActions {
    fun newMatch()

    fun repeatLast(): Boolean

    fun resume(matchId: MatchId): Boolean

    fun setWorkoutEnabled(enabled: Boolean)
}

/**
 * Startliste (04 W1): fortsetzbares Match, „Wie letztes Mal“, Workout-Schalter, letzte Ergebnisse;
 * EdgeButton „Neues Match“ (04 §1.2: im `edgeButton`-Slot des `ScreenScaffold`).
 */
@Composable
fun HomeScreen(
    home: WatchScreen.Home,
    permissions: WorkoutPermissions,
    actions: HomeActions,
) {
    val listState = rememberScalingLazyListState()
    ScreenScaffold(
        scrollState = listState,
        edgeButton = {
            EdgeButton(onClick = actions::newMatch) { Text(stringResource(R.string.home_new_match)) }
        },
    ) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxWidth()) {
            item { ListHeader { Text(stringResource(R.string.app_name)) } }
            home.resume?.let { resume ->
                item {
                    Button(
                        onClick = { actions.resume(resume.matchId) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.home_resume)) },
                        secondaryLabel = { Text("${sportName(resume.sport)} · ${resume.score}") },
                    )
                }
            }
            home.last?.let { last ->
                item {
                    val discipline =
                        stringResource(
                            if (last.discipline ==
                                Discipline.DOUBLES
                            ) {
                                R.string.discipline_doubles
                            } else {
                                R.string.discipline_singles
                            },
                        )
                    val format = last.choice?.let { formatLabel(it) } ?: genericFormatLabel(last.format)
                    Button(
                        onClick = { actions.repeatLast() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.filledTonalButtonColors(),
                        label = { Text(stringResource(R.string.home_repeat_last)) },
                        secondaryLabel = { Text("${sportName(last.sport)} · $format · $discipline", maxLines = 2) },
                    )
                }
            }
            item {
                SwitchButton(
                    checked = home.workoutEnabled,
                    onCheckedChange = actions::setWorkoutEnabled,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.workout_toggle)) },
                    secondaryLabel = { Text(stringResource(R.string.workout_toggle_hint)) },
                )
            }
            if (home.workoutEnabled) {
                item {
                    Text(
                        stringResource(R.string.permission_rationale),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
                permissionHints(permissions).forEach { hint ->
                    item {
                        Text(
                            stringResource(hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            item { ListHeader { Text(stringResource(R.string.home_recent)) } }
            if (home.recent.isEmpty()) {
                item { Text(stringResource(R.string.home_no_matches), style = MaterialTheme.typography.bodySmall) }
            }
            home.recent.forEach { result ->
                item {
                    Text(
                        "${sportName(result.sport)} · ${result.sets} · ${resultText(result.winner, result.reason)}",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            item {
                Text(
                    stringResource(R.string.wear_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Hinweise zu fehlenden Rechten, damit klar ist, was ohne sie fehlt. */
fun permissionHints(permissions: WorkoutPermissions): List<Int> =
    buildList {
        if (!permissions.canRunHealthService) {
            add(R.string.hint_no_health)
        } else {
            if (!permissions.heartRate) add(R.string.hint_no_heart_rate)
            if (!permissions.activityRecognition) add(R.string.hint_no_activity)
        }
        if (!permissions.notifications) add(R.string.hint_no_notifications)
    }
