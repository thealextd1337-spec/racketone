package eu.advantly.wear.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import eu.advantly.core.model.EventId
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.model.Uuid7
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.core.scoring.ServeOutcome
import eu.advantly.core.scoring.SidePair
import eu.advantly.presentation.live.PointLabel
import eu.advantly.presentation.live.SetColumn
import eu.advantly.wear.live.DecisionUi
import eu.advantly.wear.live.LiveRole
import eu.advantly.wear.live.LiveUi
import eu.advantly.wear.live.PlayerUi
import eu.advantly.wear.live.ServeBarUi
import eu.advantly.wear.live.ServeUi
import eu.advantly.wear.live.SyncUi
import eu.advantly.wear.workout.ExerciseSelection
import eu.advantly.wear.workout.WatchExerciseType
import eu.advantly.wear.workout.WorkoutMetric
import eu.advantly.wear.workout.WorkoutPhase
import eu.advantly.wear.workout.WorkoutSport
import eu.advantly.wear.workout.WorkoutUiState
import java.io.File
import java.lang.reflect.Proxy

// Testdaten für die Compose-UI-Tests der Wear-App (Robolectric): Live-Zustand ohne Session, Aktionen als Aufzeichnung.

private fun id(n: Int): String = Uuid7.fromParts(1_000L + n, n, n.toLong()).value

fun player(
    side: Side,
    index: Int,
    name: String?,
    isSelf: Boolean = false,
): PlayerUi = PlayerUi(PlayerId(id(10 + (if (side == Side.A) 0 else 5) + index)), side, index, name, isSelf)

/** Laufendes Tennis-Match am Zähler: 2. Satz 3:2, Stand 30:15, wir schlagen auf. */
fun liveUi(
    doubles: Boolean = false,
    names: Boolean = true,
    serveBar: ServeBarUi? = null,
    sport: Sport = Sport.TENNIS,
    decision: DecisionUi? = null,
): LiveUi {
    val teams =
        if (doubles) {
            SidePair(
                listOf(player(Side.A, 0, null, isSelf = true), player(Side.A, 1, if (names) "Maximilian" else null)),
                listOf(player(Side.B, 0, if (names) "Konstantin" else null), player(Side.B, 1, if (names) "Bartholomäus" else null)),
            )
        } else {
            SidePair(
                listOf(player(Side.A, 0, null, isSelf = true)),
                listOf(player(Side.B, 0, if (names) "Maximilian Mustermann" else null)),
            )
        }
    return LiveUi(
        matchId = MatchId(id(1)),
        sport = sport,
        doubles = doubles,
        role = LiveRole.SCORER,
        scorerKind = null,
        teams = teams,
        points = SidePair(PointLabel.Number("30"), PointLabel.Number("15")),
        sets =
            listOf(
                SetColumn(SidePair(6, 4), tiebreakLoserPoints = null, winner = Side.A, isCurrent = false),
                SetColumn(SidePair(3, 2), tiebreakLoserPoints = null, winner = null, isCurrent = true),
            ),
        scoreCall = null,
        hint = null,
        serve =
            ServeUi(
                side = Side.A,
                court = null,
                servesRemaining = null,
                serverNumber = null,
                server = if (doubles) teams.a[0] else null,
                receiver = if (doubles) teams.b[1] else null,
            ),
        status = MatchStatus.IN_PROGRESS,
        sync = SyncUi.Pending(3),
        takeOverOffered = false,
        rejectedInputs = 0,
        canUndo = true,
        canRedo = false,
        decision = decision,
        finished = null,
        locked = false,
        overlay = null,
        corrections = null,
        serveBar = serveBar,
    )
}

/** Aufschlag-Leiste für einen Punkt der Rückschlagseite (alle drei Werte) bzw. nur 1./2. Aufschlag. */
fun serveBar(
    withDoubleFault: Boolean = true,
    selected: ServeOutcome? = null,
    remainingMillis: Long = 0,
    hideAtMillis: Long = 0,
    point: Int = 99,
): ServeBarUi =
    ServeBarUi(
        pointId = EventId(id(point)),
        remainingMillis = remainingMillis,
        hideAtMillis = hideAtMillis,
        options =
            listOfNotNull(
                ServeOutcome.FIRST_SERVE_IN,
                ServeOutcome.SECOND_SERVE_IN,
                ServeOutcome.DOUBLE_FAULT.takeIf { withDoubleFault },
            ),
        selected = selected,
    )

/** Laufendes Workout mit Puls und Schritten (Telemetrie-Zeile sichtbar). */
fun activeWorkout(): WorkoutUiState =
    WorkoutUiState(
        phase = WorkoutPhase.ACTIVE,
        selection =
            ExerciseSelection(
                WorkoutSport.TENNIS,
                WatchExerciseType.TENNIS,
                isFallback = false,
                metrics = setOf(WorkoutMetric.STEPS),
            ),
        heartRateBpm = 168,
        steps = 12_345,
    )

/** Beliebiges Aktions-Interface als Aufzeichnung (`setServe(FIRST_SERVE_IN)` …); Boolean-Rückgaben `true`. */
inline fun <reified T : Any> recording(calls: MutableList<String> = mutableListOf()): T = recording(T::class.java, calls)

fun <T : Any> recording(
    type: Class<T>,
    calls: MutableList<String>,
): T =
    type.cast(
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "toString" -> return@newProxyInstance "Recording(${type.simpleName})"
                "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
                "equals" -> return@newProxyInstance proxy === args?.firstOrNull()
            }
            calls += "${method.name}(${args?.joinToString().orEmpty()})"
            if (method.returnType == java.lang.Boolean.TYPE) true else Unit
        },
    )

/** [LiveActions], die jeden Aufruf aufzeichnet. */
class RecordingActions {
    val calls = mutableListOf<String>()

    val actions: LiveActions = recording(LiveActions::class.java, calls)
}

/** Wie in [WearApp]: Material-Theme und AppScaffold (Uhrzeit oben). */
@Composable
fun WearTestFrame(content: @Composable () -> Unit) {
    MaterialTheme { AppScaffold { content() } }
}

/** Textlayout eines (ungemergten) Text-Knotens. */
fun SemanticsNodeInteraction.textLayout(): TextLayoutResult = fetchSemanticsNode().textLayout()

fun SemanticsNode.textLayout(): TextLayoutResult {
    val results = mutableListOf<TextLayoutResult>()
    checkNotNull(config.getOrNull(SemanticsActions.GetTextLayoutResult)) { "kein Text-Knoten" }.action?.invoke(results)
    return results.single()
}

/** Schneiden sich zwei Rechtecke (Berühren zählt nicht)? */
fun Rect.overlaps(other: Rect): Boolean = left < other.right && other.left < right && top < other.bottom && other.top < bottom

/**
 * Screenshot des ganzen Screens als PNG (rund maskiert wie auf der Uhr) – nur wenn die Umgebungsvariable
 * `ADVANTLY_SCREENSHOT_DIR` gesetzt ist (lokale Sichtprüfung; CI schreibt nichts).
 */
fun AndroidComposeTestRule<*, out ComponentActivity>.screenshot(name: String) {
    val dir = System.getenv("ADVANTLY_SCREENSHOT_DIR")?.takeIf { it.isNotBlank() } ?: return
    waitForIdle()
    val view = activity.window.decorView
    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    view.draw(canvas)
    val mask = Path().apply { addCircle(bitmap.width / 2f, bitmap.height / 2f, bitmap.width / 2f, Path.Direction.CW) }
    canvas.clipOutPath(mask)
    canvas.drawColor(android.graphics.Color.DKGRAY)
    File(dir).mkdirs()
    File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
}
