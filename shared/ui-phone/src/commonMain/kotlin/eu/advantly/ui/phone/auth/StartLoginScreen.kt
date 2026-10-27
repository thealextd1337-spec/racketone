package eu.advantly.ui.phone.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import eu.advantly.ui.phone.common.AdvantlyButton
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.designsystem.Spacing
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.app_name
import eu.advantly.ui.phone.resources.start_login_benefit
import eu.advantly.ui.phone.resources.start_login_create
import eu.advantly.ui.phone.resources.start_login_later
import eu.advantly.ui.phone.resources.start_login_local
import eu.advantly.ui.phone.resources.start_login_sign_in
import eu.advantly.ui.phone.resources.start_login_skip
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.stringResource

/**
 * Gespeicherte Entscheidung zum Startbildschirm „Konto oder ohne Konto“ (lokal, kein Kontodatum). Nur Android liefert
 * eine Umsetzung; iOS/Desktop übergeben `null`, der Startbildschirm erscheint dort nie.
 */
interface StartLoginHost {
    /** `true`, sobald der Nutzer gewählt hat oder angemeldet war; bleibt über App-Starts, Abmelden und Löschen erhalten. */
    val decided: StateFlow<Boolean>

    fun decide()
}

/**
 * Startbildschirm zeigen? Nur mit Backend und Host, solange nichts entschieden und niemand angemeldet ist. Während der
 * Recovery-Hold bzw. sein Dialog offen ist ([recoveryHold]) und solange die erste Kontoprüfung läuft ([checking]) oder
 * gescheitert ist ([checkFailed]), nicht: Ohne Sitzung prüft das SDK rein lokal und fehlerfrei; ein Fehler heißt, eine
 * gespeicherte Sitzung ließ sich (offline/Serverfehler) nicht bestätigen – der Nutzer ist womöglich angemeldet.
 */
fun shouldShowStartLogin(
    available: Boolean,
    decided: Boolean,
    signedIn: Boolean,
    checking: Boolean,
    checkFailed: Boolean,
    recoveryHold: Boolean,
): Boolean = available && !decided && !signedIn && !checking && !checkFailed && !recoveryHold

/** Schlanker Einstieg: beide Kontoknöpfe führen in denselben bestehenden Konto-Fluss ([AccountScreen]). */
@Composable
internal fun StartLoginScreen(
    onAccount: () -> Unit,
    onSkip: () -> Unit,
) {
    val colors = AdvantlyTheme.colors
    val typography = AdvantlyTheme.typography
    // Scrollbar statt fester Höhe: bei großer Schrift auf kleinen Handys wird nichts abgeschnitten.
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.screenEdgeCompact, vertical = Spacing.xl)
            .testTag("start_login"),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Text(
            stringResource(Res.string.app_name),
            style = typography.headline,
            color = colors.text,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(Res.string.start_login_benefit), style = typography.body, color = colors.text)
        Text(stringResource(Res.string.start_login_local), style = typography.body, color = colors.text2)
        Spacer(Modifier.height(Spacing.s))
        AdvantlyButton(
            stringResource(Res.string.start_login_create),
            primary = true,
            onClick = onAccount,
            modifier = Modifier.fillMaxWidth().testTag("start_login_create"),
        )
        AdvantlyButton(
            stringResource(Res.string.start_login_sign_in),
            primary = false,
            onClick = onAccount,
            modifier = Modifier.fillMaxWidth().testTag("start_login_sign_in"),
        )
        AdvantlyButton(
            stringResource(Res.string.start_login_skip),
            primary = false,
            onClick = onSkip,
            modifier = Modifier.fillMaxWidth().testTag("start_login_skip"),
        )
        Text(stringResource(Res.string.start_login_later), style = typography.caption, color = colors.text2)
    }
}
