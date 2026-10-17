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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import eu.advantly.presentation.auth.AuthStep
import eu.advantly.presentation.auth.AuthViewModel
import eu.advantly.presentation.auth.GoogleCredential
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.auth_adoption_accept
import eu.advantly.ui.phone.resources.auth_adoption_cancel
import eu.advantly.ui.phone.resources.auth_adoption_phone
import eu.advantly.ui.phone.resources.auth_adoption_title
import eu.advantly.ui.phone.resources.auth_adoption_watch
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
import eu.advantly.ui.phone.resources.auth_link_google
import eu.advantly.ui.phone.resources.auth_local
import eu.advantly.ui.phone.resources.auth_optional
import eu.advantly.ui.phone.resources.auth_profile_missing
import eu.advantly.ui.phone.resources.auth_rate_limit
import eu.advantly.ui.phone.resources.auth_ready
import eu.advantly.ui.phone.resources.auth_restart
import eu.advantly.ui.phone.resources.auth_send
import eu.advantly.ui.phone.resources.auth_sign_out
import eu.advantly.ui.phone.resources.auth_title
import eu.advantly.ui.phone.resources.auth_too_young
import eu.advantly.ui.phone.resources.auth_unavailable
import eu.advantly.ui.phone.resources.auth_username
import eu.advantly.ui.phone.resources.auth_username_taken
import eu.advantly.ui.phone.resources.support_open
import org.jetbrains.compose.resources.stringResource

/** Optionaler Kontobereich; kein Zugriff auf lokale Matches und kein Login auf der Uhr. */
@Composable
fun AccountScreen(
    model: AuthViewModel,
    google: (suspend () -> GoogleCredential)?,
    onSupport: (() -> Unit)? = null,
    onClose: () -> Unit,
) {
    val state by model.state.collectAsState()
    // Absichtlich remember statt rememberSaveable: Geburtstag und OTP werden nie in SavedState serialisiert.
    var country by remember(state.step) { mutableStateOf("AT") }
    var birthDate by remember(state.step) { mutableStateOf("") }
    var email by remember(state.step) { mutableStateOf("") }
    var code by remember(state.step) { mutableStateOf("") }
    var alias by remember(state.step) { mutableStateOf("") }
    var username by remember(state.step) { mutableStateOf("") }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(Res.string.auth_title))
        Text(stringResource(Res.string.auth_optional))
        onSupport?.let { TextButton(it) { Text(stringResource(Res.string.support_open)) } }
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
                Button({ model.sendCode(email) }, enabled = !state.busy && email.contains('@'), modifier = Modifier.testTag("auth_send")) {
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

            AuthStep.ACCOUNT -> {
                Text(stringResource(if (state.deletionRequested) Res.string.auth_deletion else Res.string.auth_ready))
                if (!state.deletionRequested) {
                    google?.let {
                        Button(
                            { model.google(it, link = true) },
                            enabled = !state.busy,
                        ) { Text(stringResource(Res.string.auth_link_google)) }
                    }
                }
            }
        }
        if (state.adoptionSources.isNotEmpty()) {
            Text(stringResource(Res.string.auth_adoption_title))
            state.adoptionSources.forEachIndexed { index, source ->
                Row {
                    Checkbox(
                        checked = source.key in state.selectedSources,
                        onCheckedChange = { model.selectSource(source.key) },
                        enabled = !state.busy,
                        modifier = Modifier.testTag("auth_adoption_source_$index"),
                    )
                    Text(
                        stringResource(
                            if (source.watch) Res.string.auth_adoption_watch else Res.string.auth_adoption_phone,
                            source.eventCount,
                        ),
                    )
                }
            }
            Button(
                model::acceptAdoption,
                enabled = !state.busy && state.selectedSources.isNotEmpty(),
                modifier = Modifier.testTag("auth_adoption_accept"),
            ) { Text(stringResource(Res.string.auth_adoption_accept)) }
            TextButton(
                model::cancelAdoption,
                enabled = !state.busy,
                modifier = Modifier.testTag("auth_adoption_cancel"),
            ) { Text(stringResource(Res.string.auth_adoption_cancel)) }
        }
        state.error?.let { error -> Text(stringResource(authError(error)), modifier = Modifier.testTag("auth_error")) }
        if (state.error == "profile_missing") {
            Button(model::signOut, enabled = !state.busy, modifier = Modifier.testTag("auth_reauthenticate")) {
                Text(stringResource(Res.string.auth_restart))
            }
        }
        if (state.step == AuthStep.ACCOUNT || state.step == AuthStep.ALIAS) {
            TextButton(model::signOut, enabled = !state.busy, modifier = Modifier.testTag("auth_sign_out")) {
                Text(stringResource(Res.string.auth_sign_out))
            }
        }
        if (state.busy) CircularProgressIndicator()
        TextButton({
            model.restart()
            onClose()
        }, enabled = !state.busy, modifier = Modifier.testTag("auth_local")) {
            Text(stringResource(Res.string.auth_local))
        }
    }
}

private fun authError(error: String) =
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
        else -> Res.string.auth_connection_failed
    }
