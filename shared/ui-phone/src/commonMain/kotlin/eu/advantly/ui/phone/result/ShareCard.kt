package eu.advantly.ui.phone.result

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.testTag
import eu.advantly.core.model.Discipline
import eu.advantly.presentation.match.MatchSetup
import eu.advantly.presentation.match.MatchSummary
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.designsystem.Spacing
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.action_cancel
import eu.advantly.ui.phone.resources.result_share
import eu.advantly.ui.phone.resources.result_share_confirm
import eu.advantly.ui.phone.resources.result_share_failed
import eu.advantly.ui.phone.resources.result_share_text
import eu.advantly.ui.phone.resources.wizard_doubles
import eu.advantly.ui.phone.resources.wizard_mixed
import eu.advantly.ui.phone.resources.wizard_singles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Link im geteilten Text und auf der Karte; solange die App nur im internen Play-Test ist, die Website statt des Stores. */
const val SHARE_LINK = "https://advantly.eu"

internal fun disciplineName(setup: MatchSetup): StringResource =
    when {
        setup.format.discipline == Discipline.SINGLES -> Res.string.wizard_singles
        setup.meta.mixed -> Res.string.wizard_mixed
        else -> Res.string.wizard_doubles
    }

/**
 * Ergebniskarte zum Teilen: nur Spieldaten (Sport, Disziplin, Namen, Sätze, Ergebnis, Datum) und der Link –
 * kein Ort, keine Telemetrie, keine IDs. Deckender Hintergrund, damit das PNG keine transparenten Ecken hat.
 */
@Composable
internal fun MatchShareCard(
    summary: MatchSummary,
    modifier: Modifier = Modifier,
) {
    Column(modifier.background(AdvantlyTheme.colors.bg).padding(Spacing.m).testTag("share_card")) {
        Hero(summary, forShare = true)
    }
}

/** Vorschau der Karte; „Bild teilen“ rendert genau diese Vorschau als Bitmap und übergibt sie mit Text an die Plattform. */
@Composable
internal fun ShareDialog(
    summary: MatchSummary,
    onShareImage: suspend (ImageBitmap, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val layer = rememberGraphicsLayer()
    val scope = rememberCoroutineScope()
    val text = stringResource(Res.string.result_share_text, SHARE_LINK)
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.result_share)) },
        text = {
            // Scrollbar für große Systemschrift; der Layer zeichnet trotzdem die ganze Karte auf.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                MatchShareCard(
                    summary,
                    Modifier.drawWithContent {
                        layer.record { this@drawWithContent.drawContent() }
                        drawLayer(layer)
                    },
                )
                if (failed) {
                    Text(
                        stringResource(Res.string.result_share_failed),
                        color = AdvantlyTheme.colors.danger,
                        modifier = Modifier.padding(top = Spacing.s).testTag("share_failed"),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            onShareImage(layer.toImageBitmap(), text)
                            onDismiss()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            failed = true
                        } finally {
                            busy = false
                        }
                    }
                },
                modifier = Modifier.testTag("share_confirm"),
            ) { Text(stringResource(Res.string.result_share_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("share_cancel")) {
                Text(stringResource(Res.string.action_cancel))
            }
        },
        modifier = Modifier.testTag("share_dialog"),
    )
}
