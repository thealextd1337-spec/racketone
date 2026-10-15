package eu.advantly.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextToggleButton
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.NextSetKind
import eu.advantly.core.scoring.ServeOutcome
import eu.advantly.wear.R
import eu.advantly.wear.live.LiveModel
import eu.advantly.wear.live.LiveUi
import eu.advantly.wear.live.OperationRules
import eu.advantly.wear.live.ServeBarUi
import eu.advantly.wear.live.StripAction
import eu.advantly.wear.scoring.ActionStrip

/** Aktionen der W2-Bedienung (Punkt abziehen, Aufschlag-Leiste, Aufschlägerwahl, Satzende-Optionen). */
interface OperationActions {
    fun removePoint(side: Side): Boolean

    fun setServe(outcome: ServeOutcome): Boolean

    fun openServerChoice()

    fun chooseServer(player: PlayerId): Boolean

    fun startNextSet(kind: NextSetKind): Boolean
}

/**
 * Inhalt der Aktionsleiste ([ActionStrip]) über dem Mittelband des Scores. Die Taps verteilt der Gesten-Handler des
 * Score-Screens (Segmente über [ActionStrip.segment]); hier nur Darstellung und TalkBack-Aktionen.
 *
 * @param width Breite des Displays (die Leiste nimmt [ActionStrip.WIDTH] davon)
 * @param height Höhe des Displays (die Leiste nimmt [ActionStrip.HEIGHT] davon)
 */
@Composable
fun ActionStripContent(
    action: StripAction,
    live: LiveUi,
    width: Dp,
    height: Dp,
    actions: LiveActions,
    modifier: Modifier = Modifier,
) {
    val strip = modifier.size(width * ActionStrip.WIDTH, height * ActionStrip.HEIGHT)
    when (action) {
        is StripAction.Serve -> ServeBar(action.bar, actions, strip)
        StripAction.UndoRemoval -> UndoRemovalStrip(actions, strip)
        StripAction.ChooseServer -> ChooseServerStrip(live, actions, strip)
    }
}

/** „1. · 2. · DF“ – große Segmente, gewählter Wert hell hinterlegt. */
@Composable
private fun ServeBar(
    bar: ServeBarUi,
    actions: OperationActions,
    modifier: Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        bar.options.forEach { outcome ->
            val selected = outcome == bar.selected
            val description = serveText(outcome)
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(if (selected) Color.White else SegmentGray, PILL)
                        .semantics {
                            role = Role.Button
                            this.selected = selected
                            contentDescription = description
                            onClick { actions.setServe(outcome) }
                        },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    serveShortText(outcome),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color =
                        when {
                            selected -> Color.Black
                            outcome == ServeOutcome.DOUBLE_FAULT -> ErrorRed
                            else -> Color.White
                        },
                    maxLines = 1,
                )
            }
        }
    }
}

/** „Punkt abgezogen · RÜCKGÄNGIG“ – die ganze Leiste ist die Schaltfläche (Redo). */
@Composable
private fun UndoRemovalStrip(
    actions: LiveActions,
    modifier: Modifier,
) {
    val undo = stringResource(R.string.action_undo)
    val removed = stringResource(R.string.live_point_removed)
    Row(
        modifier =
            modifier
                .background(Color.Black, PILL)
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = "$removed. $undo"
                    onClick { actions.redo() }
                },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(removed, fontSize = 12.sp, color = Color.White, maxLines = 1)
        Text(
            undo.uppercase(),
            modifier = Modifier.padding(start = 6.dp).background(HintAmber, PILL).padding(horizontal = 8.dp, vertical = 3.dp),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black,
            maxLines = 1,
        )
    }
}

/** Satzstände + Hinweis „Aufschlag wählen“ (Doppel, Game 1/2); antippen öffnet die Auswahl. */
@Composable
private fun ChooseServerStrip(
    live: LiveUi,
    actions: OperationActions,
    modifier: Modifier,
) {
    val prompt = stringResource(R.string.server_choice_prompt)
    Row(
        modifier =
            modifier.semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = prompt
                onClick { actions.openServerChoice().let { true } }
            },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val sets = LiveModel.setsText(live.sets)
        if (sets.isNotEmpty()) Text(sets, fontSize = 12.sp, color = Color.White, maxLines = 1)
        Text(
            "$prompt ▸",
            modifier = Modifier.padding(start = 6.dp).border(1.dp, HintAmber, PILL).padding(horizontal = 8.dp, vertical = 3.dp),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = HintAmber,
            maxLines = 1,
        )
    }
}

/** Aufschlag-Leiste als Listeneintrag (Satzende-/Matchende-Seite): runde Umschalter „1.“, „2.“, „DF“. */
@Composable
fun ServeChips(
    bar: ServeBarUi,
    actions: OperationActions,
) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.serve_bar_title), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
            bar.options.forEach { outcome ->
                val description = serveText(outcome)
                TextToggleButton(
                    checked = outcome == bar.selected,
                    onCheckedChange = { actions.setServe(outcome) },
                    modifier = Modifier.size(CHIP).semantics { contentDescription = description },
                ) { Text(serveShortText(outcome), fontWeight = FontWeight.Bold) }
            }
        }
    }
}

/** Zahlen-Umschalter in Zeilen zu je [perRow] (z. B. Games pro Satz 1–6 als 2 × 3 – passt auf runde Displays). */
@Composable
fun NumberChips(
    values: List<Int>,
    selected: Int?,
    onSelect: (Int) -> Unit,
    perRow: Int = 3,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        values.chunked(perRow).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { value ->
                    TextToggleButton(
                        checked = value == selected,
                        onCheckedChange = { onSelect(value) },
                        modifier = Modifier.size(CHIP),
                    ) { Text(value.toString(), fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

private val PILL = RoundedCornerShape(50)
private val SegmentGray = Color(0xFF2A2F38)
private val CHIP = 44.dp

/** Tap auf die Aktionsleiste an [x] (px) bei Displaybreite [width]: Segment der Aufschlag-Leiste, Redo bzw. Auswahl öffnen. */
fun onStripTap(
    action: StripAction,
    x: Float,
    width: Float,
    actions: LiveActions,
) {
    when (action) {
        is StripAction.Serve -> {
            ActionStrip
                .segment(x, width, action.bar.options.size)
                ?.let { OperationRules.serveOption(action.bar, it) }
                ?.let { actions.setServe(it) }
        }

        StripAction.UndoRemoval -> {
            actions.redo()
        }

        StripAction.ChooseServer -> {
            actions.openServerChoice()
        }
    }
}
