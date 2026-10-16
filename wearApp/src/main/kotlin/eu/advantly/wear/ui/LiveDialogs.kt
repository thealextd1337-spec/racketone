package eu.advantly.wear.ui

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.scoring.GameKind
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.core.scoring.NextSetKind
import eu.advantly.wear.R
import eu.advantly.wear.live.CorrectionsUi
import eu.advantly.wear.live.DecisionUi
import eu.advantly.wear.live.FinishedUi
import eu.advantly.wear.live.LiveModel
import eu.advantly.wear.live.LiveRole
import eu.advantly.wear.live.LiveUi
import eu.advantly.wear.live.ServerChoiceUi
import eu.advantly.wear.live.SyncUi
import eu.advantly.wear.live.WatchToast
import eu.advantly.wear.workout.WorkoutError
import eu.advantly.wear.workout.WorkoutPhase
import eu.advantly.wear.workout.WorkoutUiState
import kotlinx.coroutines.delay

/** Liste mit optionalem EdgeButton (rund: Primäraktion unten am Rand, 04 §1.2). */
@Composable
private fun ListPage(
    edgeLabel: String?,
    onEdge: () -> Unit,
    safeChoice: Boolean = false,
    content: ScalingLazyListScope.() -> Unit,
) {
    val listState = rememberScalingLazyListState(initialCenterItemIndex = if (safeChoice) 0 else 1)
    ScreenScaffold(
        scrollState = listState,
        edgeButton = {
            if (edgeLabel != null) EdgeButton(onClick = onEdge) { Text(edgeLabel) }
        },
    ) { padding ->
        ScalingLazyColumn(
            state = listState,
            contentPadding = if (safeChoice) PaddingValues(horizontal = 20.dp, vertical = 40.dp) else padding,
            modifier = Modifier.fillMaxWidth(),
            content = content,
        )
    }
}

private fun ScalingLazyListScope.textItem(
    secondary: Boolean = true,
    text: @Composable () -> String,
) {
    item {
        Text(
            text(),
            style = if (secondary) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun ScalingLazyListScope.actionItem(
    label: Int,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    item {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            colors = if (primary) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
            label = { Text(stringResource(label), maxLines = 2) },
        )
    }
}

/**
 * Steuerseite (04 W4, Pager links): Zähler – Rückgängig, Wiederholen, Pause, Sperre, Match beenden; Anzeige – Rolleninfo,
 * Staleness und „Zählen übernehmen“. Dazu Sync-Status, Fitness (Puls, kcal, Schritte) und Workout-Fehler.
 * Kehrt nach 8 s ohne Eingabe zum Score zurück (04 §2.2).
 */
@Composable
fun ControlsPage(
    live: LiveUi,
    workout: WorkoutUiState,
    heartRate: Int?,
    actions: LiveActions,
    onClose: () -> Unit,
) {
    BackToScore(onClose)
    var interaction by remember { mutableIntStateOf(0) }
    LaunchedEffect(interaction) {
        delay(AUTO_RETURN_MS)
        onClose()
    }

    fun touch(action: () -> Unit): () -> Unit =
        {
            interaction++
            action()
        }
    ListPage(edgeLabel = stringResource(R.string.action_back_to_score), onEdge = onClose) {
        if (live.role == LiveRole.SCORER) {
            item { ListHeader { Text(stringResource(R.string.controls_title)) } }
            item {
                Text(
                    "${stringResource(R.string.role_scorer_short)} · ${syncText(live.sync)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            actionItem(R.string.action_undo, enabled = live.canUndo, onClick = touch { actions.undo() })
            if (live.canRedo) actionItem(R.string.action_redo, onClick = touch { actions.redo() })
            if (live.status == MatchStatus.PAUSED) {
                actionItem(R.string.action_resume_play, primary = true, onClick = touch { actions.resumePlay() })
            } else {
                actionItem(R.string.action_pause, onClick = touch { actions.pause() })
            }
            actionItem(R.string.action_lock, onClick = {
                actions.lock()
                onClose()
            })
            if (live.serverChoice != null) actionItem(R.string.server_choice_prompt, onClick = actions::openServerChoice)
            // Alternative zum Long-Press (auffindbar, TalkBack): letzten Punkt einer Seite abziehen
            if (live.canRemovePoint.a) actionItem(R.string.live_remove_point_us, onClick = touch { actions.removePoint(Side.A) })
            if (live.canRemovePoint.b) actionItem(R.string.live_remove_point_them, onClick = touch { actions.removePoint(Side.B) })
            if (live.corrections != null) actionItem(R.string.action_correct_server, onClick = actions::requestCorrection)
            actionItem(R.string.action_end_match, onClick = actions::requestEnd)
        } else {
            item { ListHeader { Text(stringResource(R.string.role_view)) } }
            displayRoleInfo(live)
            if (live.status != MatchStatus.FINISHED) {
                actionItem(R.string.take_over_offer, primary = live.takeOverOffered, onClick = actions::requestTakeOver)
            }
        }
        if (live.rejectedInputs > 0) textItem { pluralStringResource(R.plurals.rejected_inputs, live.rejectedInputs, live.rejectedInputs) }
        live.updateRequired?.let { required ->
            item {
                Text(
                    updateText(required),
                    style = MaterialTheme.typography.bodySmall,
                    color = HintAmber,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        fitnessItems(workout, heartRate, actions)
    }
}

private fun ScalingLazyListScope.displayRoleInfo(live: LiveUi) {
    item {
        val scorer = stringResource(R.string.scoring_by, deviceName(live.scorerKind))
        val sync =
            if (live.sync ==
                SyncUi.Live
            ) {
                stringResource(R.string.sync_live)
            } else {
                stringResource(R.string.sync_last_update, syncText(live.sync))
            }
        Text("$scorer\n$sync", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

/** Fitness-Bereich (04 W5): Puls, Dauer, kcal/Schritte, Workout-Status und -Fehler (Workout läuft unabhängig von der Rolle). */
private fun ScalingLazyListScope.fitnessItems(
    workout: WorkoutUiState,
    heartRate: Int?,
    actions: LiveActions,
) {
    item {
        Text(
            heartRate?.let { stringResource(R.string.heart_rate_value, it) } ?: stringResource(R.string.heart_rate_unknown),
            style = MaterialTheme.typography.titleMedium,
        )
    }
    item { WorkoutStatusText(workout) }
    if (workout.phase == WorkoutPhase.ACTIVE) {
        textItem {
            val kcal = workout.caloriesKcal?.toInt()?.toString() ?: "--"
            "${workoutDuration(workout)} · ${stringResource(R.string.stats_line, kcal, workout.steps?.toString() ?: "--")}"
        }
    }
    if (workout.phase == WorkoutPhase.FAILED) {
        item {
            Text(
                "${stringResource(R.string.error_title)}: ${stringResource(errorText(workout.error))}",
                style = MaterialTheme.typography.bodySmall,
                color = ErrorRed,
                textAlign = TextAlign.Center,
            )
        }
        actionItem(R.string.action_retry, onClick = actions::retryWorkout)
        actionItem(R.string.action_without_workout, onClick = actions::skipWorkout)
    }
}

@Composable
private fun WorkoutStatusText(workout: WorkoutUiState) {
    val text =
        when {
            workout.countOnly -> stringResource(R.string.count_only_label)
            workout.phase == WorkoutPhase.STARTING -> stringResource(R.string.workout_starting)
            workout.phase == WorkoutPhase.ACTIVE -> stringResource(R.string.workout_running)
            else -> stringResource(R.string.workout_off)
        }
    Text(text, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
}

/** Laufende Workout-Dauer h:mm:ss aus dem letzten Checkpoint (Sekundentakt nur auf dieser Seite). */
@Composable
private fun workoutDuration(workout: WorkoutUiState): String {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = SystemClock.elapsedRealtime()
        }
    }
    val anchor = workout.durationAnchorElapsedMillis
    val active = workout.activeDurationMillis + if (anchor != null) (now - anchor).coerceAtLeast(0) else 0
    val total = active / 1000
    return "${total / 3600}:${(total / 60 % 60).toString().padStart(2, '0')}:${(total % 60).toString().padStart(2, '0')}"
}

private fun errorText(error: WorkoutError?): Int =
    when (error) {
        WorkoutError.PERMISSIONS -> R.string.error_permissions
        WorkoutError.CAPABILITIES -> R.string.error_capabilities
        WorkoutError.STATUS_QUERY -> R.string.error_status_query
        WorkoutError.START -> R.string.error_start
        WorkoutError.END -> R.string.error_end
        WorkoutError.CALLBACK -> R.string.error_callback
        WorkoutError.EXTERNAL_END -> R.string.error_external_end
        WorkoutError.FOREGROUND -> R.string.error_foreground
        WorkoutError.INTERNAL, null -> R.string.error_internal
    }

/**
 * Satzende-Dialog (04 W6, 02 §5.2, Modus Offen): nur der Zähler entscheidet – keine automatische Auswahl. Tennis/Padel
 * (W2, K18): „Normaler Satz“ (EdgeButton), „Tiebreak bis 7“, „Champions-Tiebreak bis 10“ (Padel: Super-Tiebreak),
 * „Match beenden“; andere Sportarten: „Ja“ / „Nein, beenden“. Die Frage ist nummeriert („2. Satz?“). Dazu ggf. die
 * Aufschlag-Leiste für den Satzball (oben, sie läuft ab) und Rückgängig. Die Anzeige sieht das Satzergebnis und
 * „Handy entscheidet“.
 */
@Composable
fun DecisionScreen(
    live: LiveUi,
    decision: DecisionUi,
    toast: WatchToast?,
    actions: LiveActions,
) {
    val scorer = live.role == LiveRole.SCORER
    val unit =
        if (live.sport == Sport.PICKLEBALL) {
            stringResource(R.string.unit_game, decision.setNumber)
        } else {
            stringResource(R.string.unit_set, decision.setNumber)
        }
    val outcome = stringResource(if (decision.winner == Side.A) R.string.decision_won else R.string.decision_lost)
    // nummeriert nach dem nächsten Satz (Feldtest 2026-10-16): „2. Satz?“ bzw. Pickleball „2. Spiel?“
    val question = nextSetQuestion(live.sport, decision.setNumber)
    // mehr als „normaler Satz“ im Angebot → Satzarten ausschreiben, sonst die kurze Ja/Nein-Frage wie bisher
    val withKinds = decision.options.size > 1
    ListPage(
        edgeLabel =
            when {
                !scorer -> null
                withKinds -> stringResource(R.string.set_end_regular)
                else -> stringResource(R.string.action_yes)
            },
        onEdge = { actions.startNextSet(NextSetKind.REGULAR) },
    ) {
        // Aufschlag für den Satzball zuerst (läuft ab); danach Satzstand direkt über der nummerierten Frage
        live.serveBar?.let { bar -> item { ServeChips(bar, actions) } }
        item { ListHeader { Text("$unit · ${decision.score.a}:${decision.score.b}") } }
        textItem(secondary = false) { outcome }
        if (toast != null) textItem { toastText(toast) }
        if (scorer) {
            item { Text(question, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center) }
            decision.options.filter { it != NextSetKind.REGULAR }.forEach { kind ->
                item {
                    Button(
                        onClick = { actions.startNextSet(kind) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.filledTonalButtonColors(),
                        label = { Text(nextSetText(kind, live.sport), maxLines = 2) },
                    )
                }
            }
            actionItem(if (withKinds) R.string.set_end_finish else R.string.action_no_end) {
                actions.continueDecision(anotherSet = false, matchTiebreak = false)
            }
            actionItem(R.string.action_undo) { actions.undo() }
        } else {
            textItem { stringResource(R.string.decision_waiting, deviceName(live.scorerKind)) }
            if (live.takeOverOffered) actionItem(R.string.take_over_offer, primary = true, onClick = actions::requestTakeOver)
        }
    }
}

/** Matchende (04 W7): Ergebnis, Sätze; Zähler: „Noch einer?“ (Best-of, regulär) und Rückgängig; „Fertig“ schließt. */
@Composable
fun FinishedScreen(
    live: LiveUi,
    finished: FinishedUi,
    actions: LiveActions,
) {
    ListPage(edgeLabel = stringResource(R.string.action_done), onEdge = actions::close) {
        item { ListHeader { Text(stringResource(R.string.finished_title)) } }
        item {
            Text(
                resultText(finished.winner, finished.reason),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = finished.winner?.let(::teamColor) ?: TextSecondary,
            )
        }
        textItem(secondary = false) { "${sportName(live.sport)} · ${LiveModel.setsText(finished.sets)}" }
        live.serveBar?.let { bar -> item { ServeChips(bar, actions) } }
        if (finished.canFollowUp) actionItem(R.string.action_follow_up, primary = true) { actions.followUp() }
        if (live.role == LiveRole.SCORER && live.canUndo) actionItem(R.string.action_undo) { actions.undo() }
    }
}

/** „Auf dieser Uhr zählen?“ – erst das replizierte `ScorerChanged` schaltet die Eingaben frei (04 §3.2). */
@Composable
fun ConfirmTakeOverScreen(
    live: LiveUi,
    actions: LiveActions,
) {
    BackToScore(actions::dismissOverlay)
    ListPage(edgeLabel = stringResource(R.string.action_take_over), onEdge = { actions.confirmTakeOver() }) {
        item { ListHeader { Text(stringResource(R.string.confirm_take_over_title), textAlign = TextAlign.Center) } }
        textItem { stringResource(R.string.confirm_take_over_text, deviceName(live.scorerKind)) }
        if (live.sync is SyncUi.Stale) textItem { stringResource(R.string.sync_last_update, syncText(live.sync)) }
        actionItem(R.string.action_cancel, onClick = actions::dismissOverlay)
    }
}

/**
 * Aufschläger korrigieren (02 §7.1/§9.4): Spieler wählen – im ersten Spiel bzw. Satz „ab Beginn“ (rückwirkend), sonst
 * „ab jetzt“. Im Doppel zusätzlich Positionen der Seite tauschen (Tennis/Padel: Rückschlagseite, Badminton: Feld,
 * Tischtennis: Rückschläger). Nur am Zähler.
 */
@Composable
fun CorrectServerScreen(
    live: LiveUi,
    corrections: CorrectionsUi,
    actions: LiveActions,
) {
    BackToScore(actions::dismissOverlay)
    ListPage(edgeLabel = stringResource(R.string.action_cancel), onEdge = actions::dismissOverlay) {
        item { ListHeader { Text(stringResource(R.string.action_correct_server), textAlign = TextAlign.Center) } }
        if (live.correctionRejected) textItem { stringResource(R.string.correct_rejected) }
        textItem { stringResource(if (corrections.retroactive) R.string.correct_from_start else R.string.correct_from_now) }
        corrections.players.forEach { player ->
            item {
                val team = stringResource(if (player.side == Side.A) R.string.label_us else R.string.label_them)
                Button(
                    onClick = { actions.correctServer(player.id) },
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        if (player.id ==
                            corrections.currentServer
                        ) {
                            ButtonDefaults.buttonColors()
                        } else {
                            ButtonDefaults.filledTonalButtonColors()
                        },
                    label = { Text("$team · ${playerName(player, live.doubles)}", maxLines = 2) },
                )
            }
        }
        corrections.swaps.forEach { swap ->
            item {
                val team = stringResource(if (swap.side == Side.A) R.string.label_us else R.string.label_them)
                val label = if (live.sport == Sport.TABLE_TENNIS) R.string.swap_receiver else R.string.swap_positions
                Button(
                    onClick = { actions.swapPositions(swap.side) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    label = { Text(stringResource(label, team), maxLines = 2) },
                )
            }
        }
    }
}

/**
 * „Wer schlägt auf?“ im Doppel (02 §6.5.5, W2-Vertrag §2): Game 1 alle vier Spieler (Team nach Regel zuerst), Game 2
 * die zwei Spieler des anderen Teams. Gewählter Spieler hervorgehoben (änderbar, solange das Fenster offen ist).
 * Nicht blockierend: „Später“ (EdgeButton) bzw. Zurück schließt, es gilt die bisherige Reihenfolge.
 */
@Composable
fun ServerChoiceScreen(
    live: LiveUi,
    choice: ServerChoiceUi,
    actions: LiveActions,
) {
    BackToScore(actions::dismissOverlay)
    val title =
        when {
            choice.game == 1 -> stringResource(R.string.server_choice_title_game1)
            choice.side == Side.A -> stringResource(R.string.server_choice_title_game2_us)
            else -> stringResource(R.string.server_choice_title_game2_them)
        }
    ListPage(edgeLabel = null, onEdge = actions::dismissOverlay, safeChoice = true) {
        item { ListHeader { Text(title, textAlign = TextAlign.Center) } }
        choice.context?.let { context ->
            textItem {
                if (context.gameKind == GameKind.STANDARD) {
                    stringResource(R.string.server_choice_context_game, context.setIndex + 1, choice.game)
                } else {
                    stringResource(
                        if (choice.game ==
                            1
                        ) {
                            R.string.server_choice_context_tiebreak_first
                        } else {
                            R.string.server_choice_context_tiebreak_second
                        },
                        context.setIndex + 1,
                    )
                }
            }
        }
        choice.players.forEach { player ->
            item {
                val team = stringResource(if (player.side == Side.A) R.string.label_us else R.string.label_them)
                Button(
                    onClick = {
                        val context = choice.context
                        if (context != null) actions.chooseServer(player.id, context) else actions.chooseServer(player.id)
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    colors = if (player.id == choice.chosen) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
                    border = BorderStroke(2.dp, teamColor(player.side)),
                    label = {
                        val name = playerName(player, live.doubles)
                        Text(
                            name,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.semantics { contentDescription = "$name · $team · ${player.index + 1}" },
                        )
                    },
                    secondaryLabel = { Text(team, maxLines = 1) },
                )
            }
        }
        val default = live.serve?.server
        textItem { stringResource(R.string.server_choice_current, default?.let { playerName(it, live.doubles) }.orEmpty()) }
        actionItem(R.string.action_later, onClick = actions::dismissOverlay)
    }
}

/** „Match beenden?“ – kein Matchende ohne bewusste Bestätigung (04 §4.3.3 Punkt 8). */
@Composable
fun ConfirmEndScreen(
    live: LiveUi,
    actions: LiveActions,
) {
    BackToScore(actions::dismissOverlay)
    ListPage(edgeLabel = stringResource(R.string.action_confirm_end), onEdge = { actions.confirmEnd() }) {
        item { ListHeader { Text(stringResource(R.string.confirm_end_title)) } }
        if (live.status != MatchStatus.AWAITING_DECISION) textItem { stringResource(R.string.confirm_end_text) }
        actionItem(R.string.action_cancel, onClick = actions::dismissOverlay)
    }
}

/** Fremdes Training läuft: ersetzen nur nach ausdrücklicher Entscheidung; sonst zählt das Match ohne Workout weiter. */
@Composable
fun WorkoutConflictScreen(actions: LiveActions) {
    ListPage(edgeLabel = stringResource(R.string.action_without_workout), onEdge = actions::skipWorkout) {
        item { ListHeader { Text(stringResource(R.string.conflict_title), textAlign = TextAlign.Center) } }
        textItem { stringResource(R.string.conflict_text) }
        actionItem(R.string.action_replace, onClick = actions::replaceOtherWorkout)
    }
}

private const val AUTO_RETURN_MS = 8_000L
