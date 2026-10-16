package eu.advantly.wear.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.LocalTextStyle
import androidx.wear.compose.material3.Text
import eu.advantly.wear.R
import eu.advantly.wear.workout.WorkoutUiState

/** Welche Seite der Telemetrie: links Puls, rechts Schritte. */
internal enum class TelemetryKind { HEART_RATE, STEPS }

/**
 * Puls (links) bzw. Schritte (rechts) als kompakte Spalte „♥ / 168“ bzw. Schritt-Symbol / „12345“ am runden Rand auf
 * Höhe der Mitte (Feldtest 2026-10-16: unten verdeckte die Zeile „Gegner“). Feste Breite [TELEMETRY_WIDTH], die Zahl
 * schrumpft bei Bedarf statt abgeschnitten zu werden; die Spalte darf über das schmale Mittelband hinausragen (dort
 * liegen am Rand weder Ziffern noch Namen). Nicht klickbar; im Ambient-Modus gedimmt, Werte dort höchstens 1×/min.
 * TalkBack liest beide Werte einmal (an der Puls-Spalte).
 */
@Composable
internal fun TelemetrySide(
    kind: TelemetryKind,
    workout: WorkoutUiState,
    isAmbient: Boolean,
    modifier: Modifier,
) {
    val pulse = workout.heartRateBpm?.toString() ?: stringResource(R.string.telemetry_unknown)
    val steps = workout.steps?.toString() ?: stringResource(R.string.telemetry_unknown)
    val description = stringResource(R.string.live_workout_telemetry_description, pulse, steps)
    val color = if (isAmbient) AmbientDigits else Color.White
    val semantics =
        if (kind == TelemetryKind.HEART_RATE) {
            Modifier.clearAndSetSemantics { contentDescription = description }
        } else {
            Modifier.clearAndSetSemantics {}
        }
    Column(
        modifier =
            modifier
                .width(TELEMETRY_WIDTH)
                .wrapContentHeight(unbounded = true)
                .testTag(if (kind == TelemetryKind.HEART_RATE) LiveTags.HEART_RATE else LiveTags.STEPS)
                .then(semantics),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (kind == TelemetryKind.HEART_RATE) {
            Text("♥", fontSize = 10.sp, lineHeight = 11.sp, color = if (isAmbient) AmbientDigits else ErrorRed, maxLines = 1)
        } else {
            Icon(painterResource(R.drawable.ic_steps), contentDescription = null, tint = color, modifier = Modifier.size(11.dp))
        }
        BasicText(
            text = if (kind == TelemetryKind.HEART_RATE) pulse else steps,
            style =
                LocalTextStyle.current.merge(
                    TextStyle(color = color, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, lineHeight = 1.1.em),
                ),
            maxLines = 1,
            softWrap = false,
            autoSize = TextAutoSize.StepBased(minFontSize = 7.sp, maxFontSize = 10.sp, stepSize = 0.5.sp),
        )
    }
}

/** Breite einer Telemetrie-Spalte (fünfstellige Schrittzahl bei 9–10 sp). */
internal val TELEMETRY_WIDTH = 26.dp
