package eu.advantly.wear.ui

import android.os.SystemClock
import android.provider.Settings
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
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
import eu.advantly.match.session.OperationTimings
import eu.advantly.wear.R
import eu.advantly.wear.live.LiveModel
import eu.advantly.wear.live.LiveUi
import eu.advantly.wear.live.OperationRules
import eu.advantly.wear.live.ServeBarUi
import eu.advantly.wear.live.StripAction
import eu.advantly.wear.scoring.ActionStrip
import eu.advantly.wear.scoring.ServePanel
import eu.advantly.wear.scoring.ServePanelGeometry
import eu.advantly.wear.scoring.StripGeometry

/** Aktionen der W2-Bedienung (Punkt abziehen, Aufschlag-Leiste, Aufschlägerwahl, Satzende-Optionen). */
interface OperationActions {
    fun removePoint(side: Side): Boolean

    fun setServe(outcome: ServeOutcome): Boolean

    fun openServerChoice()

    fun chooseServer(
        player: PlayerId,
        expected: eu.advantly.match.session.ServerChoiceContext,
    ): Boolean

    fun startNextSet(kind: NextSetKind): Boolean
}

/**
 * Inhalt der Aktionsleiste ([ActionStrip]) über dem Mittelband des Scores: „Rückgängig“ nach „Punkt abziehen“ und
 * „Aufschläger wählen“. Die Aufschlag-Auswahl hat ihre eigene Darstellung ([ServeButtons]). Die Taps verteilt der
 * Gesten-Handler des Score-Screens; hier nur Darstellung und TalkBack-Aktionen. Die sichtbare Leiste ist
 * [StripGeometry.visualHeight] hoch und [StripGeometry.width] breit (die Trefferzone ist etwas höher).
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
        is StripAction.Serve -> Unit
        StripAction.UndoRemoval -> UndoRemovalStrip(actions, strip)
        StripAction.ChooseServer -> ChooseServerStrip(live, actions, strip)
    }
}

/**
 * Aufschlag-Auswahl nach dem Punkt (Plan 0.5.1 §4a): rechts große Tasten übereinander – „2. Aufschlag“ und
 * „Doppelfehler“ (Modus „Ja“) bzw. nur „Doppelfehler“ –, gewählter Wert hell hinterlegt, unten die Restzeit. Lage und
 * Größe jeder Taste kommen aus [ServePanel.geometry] – dieselben Flächen nutzt die Trefferprüfung ([onStripTap]).
 * Der Spielstand steht währenddessen links (Score-Seite). Erwartet einen Container über den ganzen Screen.
 */
@Composable
fun ServeButtons(
    bar: ServeBarUi,
    geometry: ServePanelGeometry,
    actions: OperationActions,
) {
    val density = LocalDensity.current
    val countdown = rememberServeCountdown(bar)
    bar.options.zip(geometry.buttons).forEach { (outcome, rect) ->
        val description = serveText(outcome)
        ServeSegment(
            outcome = outcome,
            selected = outcome == bar.selected,
            countdown = countdown,
            text = servePanelText(outcome),
            maxSp = ServeLabel.PANEL_MAX_SP,
            modifier =
                with(density) {
                    Modifier
                        .offset(x = rect.left.toDp(), y = rect.top.toDp())
                        .size(rect.width.toDp(), rect.height.toDp())
                }.semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = description
                    onClick { actions.setServe(outcome) }
                },
        )
    }
}

/**
 * Eine Aufschlag-Taste: Label [text] höchstens zweizeilig, die Schrift passt sich der Tastengröße an ([ServeLabel]) –
 * auf kleinen runden Uhren und bei großer Systemschrift wird nichts abgeschnitten. Unten läuft die Restzeit.
 */
@Composable
private fun ServeSegment(
    outcome: ServeOutcome,
    selected: Boolean,
    countdown: State<Float>,
    modifier: Modifier,
    text: String = serveText(outcome),
    maxSp: Float = ServeLabel.MAX_SP,
) {
    val barColor = if (selected) Color.Black.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.45f)
    Box(
        modifier =
            modifier
                .semantics {
                    this.selected = selected
                    serveCountdown = countdown.value
                }.background(if (selected) Color.White else SegmentGray, SEGMENT_SHAPE)
                .drawWithContent {
                    drawContent()
                    drawCountdown(countdown.value, barColor)
                    // unten Platz für den Restzeit-Balken: das Label bleibt frei lesbar
                }.padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = COUNTDOWN_HEIGHT + COUNTDOWN_BOTTOM + 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        ServeLabel(
            text = text,
            maxSp = maxSp,
            color =
                when {
                    selected -> Color.Black
                    outcome == ServeOutcome.DOUBLE_FAULT -> ErrorRed
                    else -> Color.White
                },
        )
    }
}

/**
 * Label eines Aufschlag-Werts, Schriftgröße automatisch zwischen [ServeLabel.MIN_SP] und [maxSp]. Mit Zeilenumbruch im
 * Text („Doppel-“ / „fehler“) stehen genau diese Zeilen da, sonst bricht es höchstens zweizeilig an Leerzeichen um.
 */
@Composable
private fun ServeLabel(
    text: String,
    maxSp: Float,
    color: Color,
) {
    // Untergrenze physisch (dp): bei großer Systemschrift darf das Label so klein werden wie bei normaler, statt
    // abgeschnitten zu werden; nach oben wächst es mit der Systemschrift bis maxSp
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
        // ein Wort („Doppelfehler“) bleibt einzeilig und wird notfalls kleiner, nie mitten im Wort getrennt
        softWrap = !ServeLabel.hasFixedLines(text),
        maxLines = ServeLabel.maxLines(text),
        autoSize = TextAutoSize.StepBased(minFontSize = minSize, maxFontSize = maxSp.sp, stepSize = 0.25.sp),
    )
}

/**
 * Monotone Uhr derselben Zeitbasis wie der Controller (`SystemClock.elapsedRealtime`, siehe MainActivity), in ms. Die
 * Restzeit-Anzeige rechnet damit aus [ServeBarUi.hideAtMillis]; Tests setzen eine eigene Uhr.
 */
val LocalElapsedClock = staticCompositionLocalOf<() -> Long> { SystemClock::elapsedRealtime }

/**
 * Restzeit-Anzeige der Aufschlag-Leiste (Feldtest 2026-10-16): 1 → 0, immer aus Ausblendzeitpunkt − jetzt
 * ([ServeBarUi.hideAtMillis], [LocalElapsedClock]) – auch beim Wiedereinstieg (z. B. zurück von der Steuerseite) und
 * zwischen den Controller-Ticks stimmt sie also. Neuer Punkt bzw. Auswahl (neuer Ausblendzeitpunkt) starten sie neu.
 * Läuft je Frame, nur solange die Leiste komponiert ist; bei „Animationen entfernen“ (Skala 0) springt sie nur mit den
 * Zustands-Ticks (≈ 1×/s).
 */
@Composable
internal fun rememberServeCountdown(bar: ServeBarUi): State<Float> {
    val clock = LocalElapsedClock.current
    val now = OperationTimings.remainingFraction(bar.hideAtMillis - clock())
    val context = LocalContext.current
    val reduceMotion =
        remember(context) { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    val ticked = rememberUpdatedState(now)
    if (reduceMotion) return ticked
    val progress = remember(bar.pointId, bar.hideAtMillis) { mutableFloatStateOf(now) }
    LaunchedEffect(bar.pointId, bar.hideAtMillis, clock) {
        while (true) {
            withFrameMillis { }
            val left = bar.hideAtMillis - clock()
            progress.floatValue = OperationTimings.remainingFraction(left)
            if (left <= 0) break
        }
    }
    return progress
}

/** Dünner Restzeit-Balken unten im Segment, links verankert: das rechte Ende läuft nach links (wie das Halten). */
private fun DrawScope.drawCountdown(
    fraction: Float,
    color: Color,
) {
    if (fraction <= 0f) return
    val inset = COUNTDOWN_INSET.toPx()
    val height = COUNTDOWN_HEIGHT.toPx()
    val width = (size.width - 2 * inset) * fraction
    drawRoundRect(
        color = color,
        topLeft = Offset(inset, size.height - COUNTDOWN_BOTTOM.toPx() - height),
        size = Size(width, height),
        cornerRadius = CornerRadius(height / 2),
    )
}

/** Restzeit-Anteil eines Aufschlag-Segments (für Tests und Barrierefreiheits-Werkzeuge). */
val ServeCountdownKey = SemanticsPropertyKey<Float>("ServeCountdown")
private var SemanticsPropertyReceiver.serveCountdown by ServeCountdownKey

private val COUNTDOWN_HEIGHT = 2.dp
private val COUNTDOWN_INSET = 8.dp
private val COUNTDOWN_BOTTOM = 3.dp

/**
 * Schriftgrenzen und Zeilen der Aufschlag-Labels: [MIN_SP] als dp (unabhängig von der Systemschrift), [MAX_SP]
 * (Satzende-Liste) und [PANEL_MAX_SP] (große Tasten neben dem Spielstand) in sp.
 */
object ServeLabel {
    const val MIN_SP = 8f
    const val MAX_SP = 18f
    const val PANEL_MAX_SP = 22f

    /** Der Text gibt seine Zeilen selbst vor („Doppel-“ / „fehler“): dann nur dort umbrechen. */
    fun hasFixedLines(text: String): Boolean = '\n' in text

    /**
     * Feste Zeilen: genau so viele; sonst höchstens zwei, aber nie mehr als Wörter – sonst würde ein einzelnes Wort
     * mitten im Wort umbrechen.
     */
    fun maxLines(text: String): Int =
        if (hasFixedLines(text)) {
            text.trim().lines().size
        } else {
            text
                .trim()
                .split(' ')
                .count { it.isNotEmpty() }
                .coerceIn(1, 2)
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

/**
 * Aufschlag-Auswahl als Listeneintrag (Satzende-/Matchende-Seite): je Wert eine Taste über die volle Breite, mindestens
 * [CHIP_HEIGHT] hoch, Label einzeilig groß ([ServeLabel.MAX_SP]) – auch wenn die Liste am runden Rand schmaler wird.
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
        val countdown = rememberServeCountdown(bar)
        bar.options.forEach { outcome ->
            ServeSegment(
                outcome = outcome,
                selected = outcome == bar.selected,
                countdown = countdown,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(CHIP_HEIGHT)
                        .clip(SEGMENT_SHAPE)
                        .clickable(role = Role.Button) { actions.setServe(outcome) }
                        .semantics(mergeDescendants = true) {},
            )
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
private val CHIP_HEIGHT = 56.dp

/**
 * Tap auf die Aktionsleiste bzw. Aufschlag-Auswahl an [x]/[y] (px): Taste der Aufschlag-Auswahl ([serve], dieselbe
 * Geometrie wie [ServeButtons]), Redo bzw. Auswahl öffnen.
 */
fun onStripTap(
    action: StripAction,
    x: Float,
    y: Float,
    serve: ServePanelGeometry?,
    actions: LiveActions,
) {
    when (action) {
        is StripAction.Serve -> {
            serve
                ?.let { ServePanel.button(x, y, it) }
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
