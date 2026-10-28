package eu.advantly.ui.phone.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import eu.advantly.cloud.sync.ConsentDocumentType
import eu.advantly.cloud.sync.ConsentStatus
import eu.advantly.presentation.auth.AuthStep
import eu.advantly.presentation.auth.AuthViewModel
import eu.advantly.presentation.auth.GoogleCredential
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.account_deletion_pending
import eu.advantly.ui.phone.resources.account_recover
import eu.advantly.ui.phone.resources.account_suspended
import eu.advantly.ui.phone.resources.auth_alias
import eu.advantly.ui.phone.resources.auth_birth
import eu.advantly.ui.phone.resources.auth_birth_privacy
import eu.advantly.ui.phone.resources.auth_code
import eu.advantly.ui.phone.resources.auth_connection_failed
import eu.advantly.ui.phone.resources.auth_continue
import eu.advantly.ui.phone.resources.auth_country
import eu.advantly.ui.phone.resources.auth_deletion
import eu.advantly.ui.phone.resources.auth_email
import eu.advantly.ui.phone.resources.auth_expired
import eu.advantly.ui.phone.resources.auth_finish
import eu.advantly.ui.phone.resources.auth_google
import eu.advantly.ui.phone.resources.auth_invalid_alias
import eu.advantly.ui.phone.resources.auth_invalid_birth
import eu.advantly.ui.phone.resources.auth_invalid_code
import eu.advantly.ui.phone.resources.auth_invalid_username
import eu.advantly.ui.phone.resources.auth_profile_missing
import eu.advantly.ui.phone.resources.auth_rate_limit
import eu.advantly.ui.phone.resources.auth_refresh
import eu.advantly.ui.phone.resources.auth_restart
import eu.advantly.ui.phone.resources.auth_send
import eu.advantly.ui.phone.resources.auth_sign_out
import eu.advantly.ui.phone.resources.auth_title
import eu.advantly.ui.phone.resources.auth_too_young
import eu.advantly.ui.phone.resources.auth_unavailable
import eu.advantly.ui.phone.resources.auth_username
import eu.advantly.ui.phone.resources.auth_username_taken
import eu.advantly.ui.phone.resources.consent_accept
import eu.advantly.ui.phone.resources.consent_changed
import eu.advantly.ui.phone.resources.consent_confirm
import eu.advantly.ui.phone.resources.consent_confirm_privacy
import eu.advantly.ui.phone.resources.consent_decline
import eu.advantly.ui.phone.resources.consent_declined
import eu.advantly.ui.phone.resources.consent_language
import eu.advantly.ui.phone.resources.consent_privacy
import eu.advantly.ui.phone.resources.consent_required
import eu.advantly.ui.phone.resources.consent_terms
import eu.advantly.ui.phone.resources.consent_title
import eu.advantly.ui.phone.resources.consent_unavailable
import eu.advantly.ui.phone.resources.consent_version
import eu.advantly.ui.phone.resources.support_open
import org.jetbrains.compose.resources.stringResource

/**
 * Konto-Fluss des Pflichtkontos ([AccountGate]): Altersprüfung, Anmeldung, Zustimmung zu neuen Rechtstexten, Profil und
 * Löschantrag. Kein lokaler Ausgang; Support, Abmelden und die Kontorechte bleiben erreichbar. Kein Login auf der Uhr.
 */
@Composable
fun AccountScreen(
    model: AuthViewModel,
    google: (suspend () -> GoogleCredential)?,
    onSupport: (() -> Unit)? = null,
    onShareExport: (() -> Unit)? = null,
) {
    val state by model.state.collectAsState()
    val uriHandler = LocalUriHandler.current
    val language = stringResource(Res.string.consent_language)
    LaunchedEffect(language, state.busy) { model.setConsentLanguage(language) }
    var confirmed by remember(state.consent) { mutableStateOf(emptySet<ConsentDocumentType>()) }
    // Absichtlich remember statt rememberSaveable: Geburtstag und OTP werden nie in SavedState serialisiert.
    var country by remember(state.step) { mutableStateOf("AT") }
    var birthDate by remember(state.step) { mutableStateOf("") }
    var email by remember(state.step) { mutableStateOf("") }
    var code by remember(state.step) { mutableStateOf("") }
    var alias by remember(state.step) { mutableStateOf("") }
    var username by remember(state.step) { mutableStateOf("") }
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(Res.string.auth_title))
            onSupport?.let {
                TextButton(it, modifier = Modifier.testTag("account_support")) { Text(stringResource(Res.string.support_open)) }
            }
            TextButton(model::refresh, enabled = !state.busy, modifier = Modifier.testTag("auth_refresh")) {
                Text(stringResource(Res.string.auth_refresh))
            }
            when (state.step) {
                AuthStep.AGE -> {
                    Text(stringResource(Res.string.auth_birth_privacy))
                    OutlinedTextField(
                        country,
                        { country = it.uppercase().take(2) },
                        label = { Text(stringResource(Res.string.auth_country)) },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().testTag("auth_country"),
                    )
                    OutlinedTextField(
                        birthDate,
                        { birthDate = it.take(10) },
                        label = { Text(stringResource(Res.string.auth_birth)) },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().testTag("auth_birth"),
                    )
                    Button(
                        { model.checkAge(country, birthDate) },
                        enabled =
                            !state.busy && birthDate.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")),
                        modifier = Modifier.testTag("auth_age_next"),
                    ) { Text(stringResource(Res.string.auth_continue)) }
                }

                AuthStep.PROVIDER -> {
                    google?.let {
                        Button({ model.google(it) }, enabled = !state.busy, modifier = Modifier.testTag("auth_google")) {
                            Text(stringResource(Res.string.auth_google))
                        }
                    }
                    OutlinedTextField(
                        email,
                        { email = it },
                        label = { Text(stringResource(Res.string.auth_email)) },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().testTag("auth_email"),
                    )
                    Button(
                        { model.sendCode(email) },
                        enabled = !state.busy && email.contains('@'),
                        modifier = Modifier.testTag("auth_send"),
                    ) {
                        Text(stringResource(Res.string.auth_send))
                    }
                    TextButton(model::restart, enabled = !state.busy) { Text(stringResource(Res.string.auth_restart)) }
                }

                AuthStep.CODE -> {
                    OutlinedTextField(
                        code,
                        { code = it.filter(Char::isDigit).take(10) },
                        label = { Text(stringResource(Res.string.auth_code)) },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().testTag("auth_code"),
                    )
                    Button(
                        { model.verifyCode(code) },
                        enabled = !state.busy && code.length >= 6,
                        modifier = Modifier.testTag("auth_verify"),
                    ) {
                        Text(stringResource(Res.string.auth_continue))
                    }
                    TextButton(model::restart, enabled = !state.busy) { Text(stringResource(Res.string.auth_restart)) }
                }

                AuthStep.ALIAS -> {
                    OutlinedTextField(
                        alias,
                        { alias = it.take(40) },
                        label = { Text(stringResource(Res.string.auth_alias)) },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().testTag("auth_alias"),
                    )
                    OutlinedTextField(
                        username,
                        { username = it.take(20) },
                        label = { Text(stringResource(Res.string.auth_username)) },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().testTag("auth_username"),
                    )
                    Button(
                        { model.finish(alias, username) },
                        enabled = !state.busy && alias.isNotBlank(),
                        modifier = Modifier.testTag("auth_finish"),
                    ) {
                        Text(stringResource(Res.string.auth_finish))
                    }
                }

                AuthStep.CONSENT -> {
                    Unit
                }

                AuthStep.ACCOUNT -> {
                    if (state.deletionRequested) {
                        Text(stringResource(Res.string.auth_deletion))
                        TextButton(model::cancelDeletion, enabled = !state.busy, modifier = Modifier.testTag("account_recover")) {
                            Text(stringResource(Res.string.account_recover))
                        }
                    }
                }
            }
            if (state.step == AuthStep.CONSENT) {
                state.consent?.let { consent ->
                    Text(stringResource(Res.string.consent_title))
                    Text(
                        stringResource(
                            when (consent.status) {
                                ConsentStatus.UNAVAILABLE -> Res.string.consent_unavailable
                                ConsentStatus.REQUIRED -> Res.string.consent_required
                                ConsentStatus.DECLINED -> Res.string.consent_declined
                                ConsentStatus.CURRENT -> Res.string.consent_required
                            },
                        ),
                        modifier = Modifier.testTag("consent_status"),
                    )
                    consent.documents.forEach { document ->
                        val title =
                            stringResource(
                                if (document.type ==
                                    ConsentDocumentType.TERMS
                                ) {
                                    Res.string.consent_terms
                                } else {
                                    Res.string.consent_privacy
                                },
                            )
                        Text(title)
                        Text(stringResource(Res.string.consent_version, document.version, document.language))
                        document.url?.takeIf { it.startsWith("https://advantly.eu/") }?.let { url ->
                            TextButton(
                                { uriHandler.openUri(url) },
                                modifier = Modifier.testTag("consent_link_${document.type}"),
                            ) { Text(title) }
                        }
                        if (consent.status != ConsentStatus.CURRENT && document.approved) {
                            Row {
                                Checkbox(
                                    checked = document.type in confirmed,
                                    onCheckedChange = {
                                        confirmed = if (it) confirmed + document.type else confirmed - document.type
                                    },
                                    enabled = !state.busy,
                                    modifier = Modifier.testTag("consent_confirm_${document.type}"),
                                )
                                // Die Datenschutzerklärung wird nur zur Kenntnis genommen: Rechtsgrundlage ist der Vertrag, keine Einwilligung.
                                val confirm =
                                    if (document.type ==
                                        ConsentDocumentType.TERMS
                                    ) {
                                        Res.string.consent_confirm
                                    } else {
                                        Res.string.consent_confirm_privacy
                                    }
                                Text(stringResource(confirm, title), modifier = Modifier.weight(1f))
                            }
                        }
                    }
                    if (consent.status != ConsentStatus.CURRENT) {
                        Button(
                            { model.decideConsent(true) },
                            enabled = !state.busy && consent.canAccept && confirmed.size == 2,
                            modifier = Modifier.fillMaxWidth().testTag("consent_accept"),
                        ) { Text(stringResource(Res.string.consent_accept)) }
                        TextButton(
                            { model.decideConsent(false) },
                            enabled = !state.busy,
                            modifier = Modifier.testTag("consent_decline"),
                        ) { Text(stringResource(Res.string.consent_decline)) }
                    }
                }
            }
            // Kontorechte hängen nicht an der Zustimmung: Export und Löschung bleiben auch vor der Bestätigung erreichbar.
            val rights = state.authenticated && (state.step == AuthStep.CONSENT || state.deletionRequested)
            if (rights) {
                AccountRights(model, null, onShareExport)
            } else {
                state.error?.let { error -> Text(stringResource(authError(error)), modifier = Modifier.testTag("auth_error")) }
            }
            if (state.error == "profile_missing") {
                Button(model::signOut, enabled = !state.busy, modifier = Modifier.testTag("auth_reauthenticate")) {
                    Text(stringResource(Res.string.auth_restart))
                }
            }
            if (state.authenticated) {
                TextButton(model::signOut, enabled = !state.busy, modifier = Modifier.testTag("auth_sign_out")) {
                    Text(stringResource(Res.string.auth_sign_out))
                }
            }
            if (state.busy) CircularProgressIndicator()
            LegalLinks()
        }
    }
}

internal fun authError(error: String) =
    when (error) {
        "country_not_supported" -> Res.string.auth_unavailable
        "age_below_minimum", "age_retry_later" -> Res.string.auth_too_young
        "age_ticket_required", "age_ticket_expired", "age_ticket_bound" -> Res.string.auth_expired
        "profile_missing" -> Res.string.auth_profile_missing
        "invalid_birth_date" -> Res.string.auth_invalid_birth
        "username_taken" -> Res.string.auth_username_taken
        "username_reserved", "invalid_username" -> Res.string.auth_invalid_username
        "invalid_alias" -> Res.string.auth_invalid_alias
        "rate_limit_exceeded" -> Res.string.auth_rate_limit
        "otp_expired" -> Res.string.auth_invalid_code
        "consent_required" -> Res.string.consent_required
        "consent_unavailable", "consent_documents_invalid", "consent_language_invalid" -> Res.string.consent_unavailable
        "consent_version_changed" -> Res.string.consent_changed
        "account_suspended" -> Res.string.account_suspended
        "account_deletion_pending" -> Res.string.account_deletion_pending
        else -> Res.string.auth_connection_failed
    }
