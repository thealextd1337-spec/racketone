package eu.advantly.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import eu.advantly.core.model.TelemetryVisibility
import eu.advantly.wear.R
import eu.advantly.wear.diagnostics.FieldTestRecorder
import eu.advantly.wear.diagnostics.FieldTestToggle
import eu.advantly.wear.live.WatchScreen
import eu.advantly.wear.workout.WorkoutPermissions

/** Aktionen der Startliste. */
interface HomeActions {
    fun newMatch()

    fun repeatLast(): Boolean

    fun resume(matchId: MatchId): Boolean

    fun setWorkoutEnabled(enabled: Boolean)

    /** Telemetrie-Sichtbarkeit (P2-J); wird mit dem Handy abgeglichen. */
    fun setTelemetryVisibility(visibility: TelemetryVisibility)
}

/**
 * Startliste (04 W1): fortsetzbares Match, „Wie letztes Mal“, Workout-Schalter, Telemetrie-Sichtbarkeit, letzte Ergebnisse;
 * EdgeButton „Neues Match“ (04 §1.2: im `edgeButton`-Slot des `ScreenScaffold`).
 *
 * @param onRestorePrevious Recovery-Paket: späterer Übernahmeweg; nur gesetzt, solange der Host ihn anbietet
 */
@Composable
fun HomeScreen(
    home: WatchScreen.Home,
    permissions: WorkoutPermissions,
    powerSave: Boolean,
    actions: HomeActions,
    onRestorePrevious: (() -> Unit)? = null,
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
            if (powerSave) {
                item {
                    Text(
                        stringResource(R.string.power_save_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = HintAmber,
                        textAlign = TextAlign.Center,
                    )
                }
            }
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
                    val games = last.gamesPerSet?.let { " · ${pluralStringResource(R.plurals.games_per_set_value, it, it)}" }.orEmpty()
                    val format = (last.choice?.let { formatLabel(it) } ?: genericFormatLabel(last.format)) + games
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
            item { TelemetryVisibilityButton(home.telemetryVisibility, actions::setTelemetryVisibility) }
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
            if (onRestorePrevious != null) {
                item {
                    Button(
                        onClick = onRestorePrevious,
                        modifier = Modifier.fillMaxWidth().testTag(RESTORE_PREVIOUS_TAG),
                        colors = ButtonDefaults.filledTonalButtonColors(),
                        label = { Text(stringResource(R.string.recovery_later_title), maxLines = 2) },
                    )
                }
            }
            // P1-Gate: versteckter Feldtest-Schalter, nur Debug-Build
            if (FieldTestRecorder.AVAILABLE) item { FieldTestToggle() }
            item { PhoneStatusLabel(home.phoneLight, home.updateRequired, home.developmentBuild) }
            item { BuildVersionLabel() }
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

/**
 * „Puls & Schritte“ (P2-J): Tippen wechselt reihum Teilen → Nur für mich → Gar nicht; die Zeile darunter erklärt die
 * aktuelle Wahl. Von „Nur für mich“ (Standard) ist „Gar nicht“ einen Tipp entfernt.
 */
@Composable
internal fun TelemetryVisibilityButton(
    visibility: TelemetryVisibility,
    onChange: (TelemetryVisibility) -> Unit,
) {
    val name = stringResource(telemetryVisibilityName(visibility))
    Button(
        onClick = { onChange(nextTelemetryVisibility(visibility)) },
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(TELEMETRY_VISIBILITY_TAG)
                .semantics { stateDescription = name },
        colors = ButtonDefaults.filledTonalButtonColors(),
        label = { Text(stringResource(R.string.telemetry_visibility_title)) },
        secondaryLabel = {
            Text("$name · ${stringResource(telemetryVisibilityHint(visibility))}", maxLines = 3)
        },
    )
}

/** Reihum in der Reihenfolge der Optionen. */
fun nextTelemetryVisibility(current: TelemetryVisibility): TelemetryVisibility =
    TelemetryVisibility.entries[(current.ordinal + 1) % TelemetryVisibility.entries.size]

fun telemetryVisibilityName(visibility: TelemetryVisibility): Int =
    when (visibility) {
        TelemetryVisibility.SHARE -> R.string.telemetry_visibility_share
        TelemetryVisibility.PRIVATE -> R.string.telemetry_visibility_private
        TelemetryVisibility.OFF -> R.string.telemetry_visibility_off
    }

fun telemetryVisibilityHint(visibility: TelemetryVisibility): Int =
    when (visibility) {
        TelemetryVisibility.SHARE -> R.string.telemetry_visibility_share_hint
        TelemetryVisibility.PRIVATE -> R.string.telemetry_visibility_private_hint
        TelemetryVisibility.OFF -> R.string.telemetry_visibility_off_hint
    }

const val TELEMETRY_VISIBILITY_TAG = "home_telemetry_visibility"

/** Recovery-Paket: späterer Übernahmeweg auf der Startliste. */
const val RESTORE_PREVIOUS_TAG = "home_restore_previous"
