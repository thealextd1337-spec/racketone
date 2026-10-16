package eu.advantly.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.LocalTextStyle
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
import eu.advantly.wear.scoring.StripGeometry

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
 * Score-Screens (Segmente über [ActionStrip.segment]); hier nur Darstellung und TalkBack-Aktionen. Die sichtbare Leiste
 * ist [StripGeometry.visualHeight] hoch und [StripGeometry.width] breit (die Trefferzone ist etwas höher).
 */
@Composable
fun ActionStripContent(
    action: StripAction,
    live: LiveUi,
    geometry: StripGeometry,
    actions: LiveActions,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val strip = with(density) { modifier.size(geometry.width.toDp(), geometry.visualHeight.toDp()) }
    when (action) {
        is StripAction.Serve -> ServeBar(action.bar, geometry, actions, strip)
        StripAction.UndoRemoval -> UndoRemovalStrip(actions, strip)
        StripAction.ChooseServer -> ChooseServerStrip(live, actions, strip)
    }
}

/**
 * „1. Aufschlag · 2. Aufschlag · Doppelfehler“ – ausgeschriebene Labels in großen Segmenten, gewählter Wert hell
 * hinterlegt. Lage und Breite jedes Segments kommen aus [ActionStrip.segmentRanges] – dieselben Bereiche nutzt die
 * Trefferprüfung ([onStripTap]), Darstellung und Tap passen also exakt zusammen.
 */
@Composable
private fun ServeBar(
    bar: ServeBarUi,
    geometry: StripGeometry,
    actions: OperationActions,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val ranges = ActionStrip.segmentRanges(geometry, OperationRules.serveWeights(bar))
    Box(modifier = modifier) {
        bar.options.zip(ranges).forEach { (outcome, range) ->
            ServeSegment(
                outcome = outcome,
                selected = outcome == bar.selected,
                modifier =
                    with(density) {
                        Modifier
                            .offset(x = (range.start - geometry.left).toDp())
                            .width((range.endInclusive - range.start).toDp())
                            .fillMaxHeight()
                    }.semantics(mergeDescendants = true) {
                        role = Role.Button
                        onClick { actions.setServe(outcome) }
                    },
            )
        }
    }
}

/**
 * Ein Segment der Aufschlag-Leiste: Langlabel („1. Aufschlag“, „Doppelfehler“) höchstens zweizeilig, die Schrift passt
 * sich der Segmentgröße an ([ServeLabel]) – auf kleinen runden Uhren und bei großer Systemschrift wird nichts abgeschnitten.
 */
@Composable
private fun ServeSegment(
    outcome: ServeOutcome,
    selected: Boolean,
    modifier: Modifier,
) {
    Box(
        modifier =
            modifier
                .semantics { this.selected = selected }
                .background(if (selected) Color.White else SegmentGray, SEGMENT_SHAPE)
                .padding(horizontal = 3.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        ServeLabel(
            outcome = outcome,
            color =
                when {
                    selected -> Color.Black
                    outcome == ServeOutcome.DOUBLE_FAULT -> ErrorRed
                    else -> Color.White
                },
        )
    }
}

/** Langlabel eines Aufschlag-Werts, zweizeilig an Leerzeichen, Schriftgröße automatisch zwischen [ServeLabel.MIN_SP] und [ServeLabel.MAX_SP]. */
@Composable
private fun ServeLabel(
    outcome: ServeOutcome,
    color: Color,
) {
    val text = serveText(outcome)
    // Untergrenze physisch (dp): bei großer Systemschrift darf das Label so klein werden wie bei normaler, statt
    // abgeschnitten zu werden; nach oben wächst es mit der Systemschrift bis MAX_SP
    val minSize = with(LocalDensity.current) { ServeLabel.MIN_SP.dp.toSp() }
    BasicText(
        text = text,
        style =
            LocalTextStyle.current.merge(
                TextStyle(
                    color = color,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    lineHeight = 1.1.em,
                ),
            ),
        // nur an Leerzeichen umbrechen: ein Wort („Doppelfehler“) bleibt einzeilig und wird notfalls kleiner, nie getrennt
        maxLines = ServeLabel.maxLines(text),
        autoSize = TextAutoSize.StepBased(minFontSize = minSize, maxFontSize = ServeLabel.MAX_SP.sp, stepSize = 0.25.sp),
    )
}

/** Schriftgrenzen und Zeilen der Aufschlag-Labels: [MIN_SP] als dp (unabhängig von der Systemschrift), [MAX_SP] in sp. */
object ServeLabel {
    const val MIN_SP = 8f
    const val MAX_SP = 13f

    /** Höchstens zwei Zeilen, aber nie mehr als Wörter – sonst würde ein einzelnes Wort mitten im Wort umbrechen. */
    fun maxLines(text: String): Int =
        text
            .trim()
            .split(' ')
            .count { it.isNotEmpty() }
            .coerceIn(1, 2)
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

/**
 * Aufschlag-Leiste als Listeneintrag (Satzende-/Matchende-Seite): dieselben Segmente mit Langlabels wie im Score, hier
 * als normale Schaltflächen in zwei Zeilen – „1. Aufschlag · 2. Aufschlag“, darunter „Doppelfehler“ über die volle
 * Breite. So bleibt jedes Label einzeilig groß lesbar, auch wenn die Liste am runden Rand schmaler wird.
 */
@Composable
fun ServeChips(
    bar: ServeBarUi,
    actions: OperationActions,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ActionStrip.SEGMENT_GAP_DP.dp),
    ) {
        Text(stringResource(R.string.serve_bar_title), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        bar.options.chunked(2).forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(ActionStrip.SEGMENT_GAP_DP.dp),
                modifier = Modifier.fillMaxWidth().height(CHIP_HEIGHT),
            ) {
                row.forEach { outcome ->
                    ServeSegment(
                        outcome = outcome,
                        selected = outcome == bar.selected,
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(SEGMENT_SHAPE)
                                .clickable(role = Role.Button) { actions.setServe(outcome) }
                                .semantics(mergeDescendants = true) {},
                    )
                }
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

/** Segmente mit weniger stark gerundeten Ecken als [PILL], damit zweizeilige Labels nicht an der Rundung kleben. */
private val SEGMENT_SHAPE = RoundedCornerShape(12.dp)
private val SegmentGray = Color(0xFF2A2F38)
private val CHIP = 44.dp
private val CHIP_HEIGHT = 44.dp

/** Tap auf die Aktionsleiste an [x] (px): Segment der Aufschlag-Leiste, Redo bzw. Auswahl öffnen. */
fun onStripTap(
    action: StripAction,
    x: Float,
    geometry: StripGeometry,
    actions: LiveActions,
) {
    when (action) {
        is StripAction.Serve -> {
            ActionStrip
                .segment(x, geometry, OperationRules.serveWeights(action.bar))
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
