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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import eu.advantly.presentation.auth.AuthStep
import eu.advantly.presentation.auth.AuthViewModel
import eu.advantly.presentation.auth.GoogleCredential
import eu.advantly.ui.phone.common.AdvantlyButton
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.designsystem.Spacing
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.app_name
import eu.advantly.ui.phone.resources.start_login_benefit
import eu.advantly.ui.phone.resources.start_login_create
import eu.advantly.ui.phone.resources.start_login_sign_in
import org.jetbrains.compose.resources.stringResource

/**
 * Pflichtkonto (0.3.0): Solange [eu.advantly.presentation.auth.AuthState.accountRequired] gilt, zeigt die Handy-App nur
 * diesen Fluss – ohne Anmeldung erst den Startbildschirm, dann Altersprüfung, Anmeldung, Zustimmung und Profil
 * ([AccountScreen]). Kein Überspringen; nach Abmelden oder Löschantrag erscheint er wieder.
 */
@Composable
internal fun AccountGate(
    model: AuthViewModel,
    google: (suspend () -> GoogleCredential)?,
    onSupport: (() -> Unit)?,
    onShareExport: (() -> Unit)?,
) {
    val state by model.state.collectAsState()
    var started by remember { mutableStateOf(false) }
    if (state.step == AuthStep.AGE && state.error == null && !started) {
        StartLoginScreen { started = true }
    } else {
        AccountScreen(model, google, onSupport, onShareExport)
    }
}

/** Schlanker Einstieg: beide Knöpfe führen in denselben Konto-Fluss ([AccountScreen]). */
@Composable
internal fun StartLoginScreen(onAccount: () -> Unit) {
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
    }
}
