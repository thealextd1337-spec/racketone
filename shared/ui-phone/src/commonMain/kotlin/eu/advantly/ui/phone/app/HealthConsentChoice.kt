package eu.advantly.ui.phone.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import eu.advantly.core.model.TelemetryVisibility
import eu.advantly.presentation.match.TelemetrySettingsPort
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.designsystem.Spacing
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.consent_privacy
import eu.advantly.ui.phone.resources.health_adopted_keep
import eu.advantly.ui.phone.resources.health_adopted_off
import eu.advantly.ui.phone.resources.health_adopted_text
import eu.advantly.ui.phone.resources.health_adopted_title
import eu.advantly.ui.phone.resources.health_choice_watch
import eu.advantly.ui.phone.resources.health_grant_text
import eu.advantly.ui.phone.resources.health_record_locked
import eu.advantly.ui.phone.resources.health_record_toggle
import eu.advantly.ui.phone.resources.health_record_toggle_sub
import eu.advantly.ui.phone.resources.privacy_url
import eu.advantly.ui.phone.resources.telemetry_intro
import eu.advantly.ui.phone.resources.telemetry_section
import org.jetbrains.compose.resources.stringResource

/**
 * Wahl „Puls & Schritte“ und „Pulsverlauf aufzeichnen“ beim Konto-Einstieg (Plan 0.5.1 §5.1) und im „Was ist neu?“ (§6).
 * Wirkt erst mit [apply]; „Später“ ruft es nicht auf und entscheidet damit nichts.
 */
@Stable
internal class HealthChoice(
    current: TelemetryVisibility,
) {
    private val initial = current.shown
    var visibility by mutableStateOf(initial)
    var record by mutableStateOf(false)

    /** Nur Geändertes schreiben: eine unveränderte Sichtbarkeit bekommt keinen neuen Zähler (kein „Teilen“ → „Nur für mich“). */
    fun apply(port: TelemetrySettingsPort) {
        if (visibility != initial) port.setTelemetryVisibility(visibility)
        if (record && visibility.allowsLive) port.setHealthConsent(true)
    }
}

@Composable
internal fun rememberHealthChoice(port: TelemetrySettingsPort): HealthChoice =
    remember(port) { HealthChoice(port.telemetryVisibility.value) }

/**
 * Art. 9 Abs. 2 lit. a / Art. 7 DSGVO: freiwillig, getrennt von AGB/Datenschutz, Ankreuzfeld nicht vorausgewählt, Text wie
 * im Profil-Dialog. Ohne Live-Werte („Gar nicht“) ist die Aufzeichnung gesperrt wie im Profil.
 */
@Composable
internal fun HealthConsentChoice(choice: HealthChoice) {
    val colors = AdvantlyTheme.colors
    val uriHandler = LocalUriHandler.current
    val privacyUrl = stringResource(Res.string.privacy_url)
    Column(Modifier.fillMaxWidth().testTag("health_choice"), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text(
            stringResource(Res.string.telemetry_section),
            style = AdvantlyTheme.typography.title,
            color = colors.text,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(Res.string.telemetry_intro), style = AdvantlyTheme.typography.caption, color = colors.text2)
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            TELEMETRY_CHOICES.forEach { option ->
                TelemetryOptionCard(option, choice.visibility == option) {
                    choice.visibility = option
                    if (!option.allowsLive) choice.record = false
                }
            }
        }
        val locked = !choice.visibility.allowsLive
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.minTouchTarget)
                .toggleable(choice.record, enabled = !locked, role = Role.Checkbox) { choice.record = it }
                .testTag("health_choice_record"),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            Checkbox(checked = choice.record, onCheckedChange = null, enabled = !locked)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(stringResource(Res.string.health_record_toggle), style = AdvantlyTheme.typography.label, color = colors.text)
                Text(
                    stringResource(if (locked) Res.string.health_record_locked else Res.string.health_grant_text),
                    style = AdvantlyTheme.typography.caption,
                    color = colors.text2,
                )
            }
        }
        Text(stringResource(Res.string.health_record_toggle_sub), style = AdvantlyTheme.typography.caption, color = colors.text2)
        Text(stringResource(Res.string.health_choice_watch), style = AdvantlyTheme.typography.caption, color = colors.text2)
        TextButton({ uriHandler.openUri(privacyUrl) }, modifier = Modifier.testTag("health_choice_privacy")) {
            Text(stringResource(Res.string.consent_privacy))
        }
    }
}

/**
 * Plan 0.5.1 §5.4: Das Handy hat nie selbst entschieden und „an“ von der Uhr übernommen – einmal nachfragen. Kein
 * Wegtippen: die Frage bleibt, bis sie beantwortet ist.
 */
@Composable
internal fun AdoptedHealthConsentDialog(port: TelemetrySettingsPort) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(Res.string.health_adopted_title)) },
        text = { Text(stringResource(Res.string.health_adopted_text)) },
        confirmButton = {
            TextButton({ port.confirmHealthConsent(keep = true) }, modifier = Modifier.testTag("health_adopted_keep")) {
                Text(stringResource(Res.string.health_adopted_keep), color = AdvantlyTheme.colors.brand)
            }
        },
        dismissButton = {
            TextButton({ port.confirmHealthConsent(keep = false) }, modifier = Modifier.testTag("health_adopted_off")) {
                Text(stringResource(Res.string.health_adopted_off), color = AdvantlyTheme.colors.danger)
            }
        },
        modifier = Modifier.testTag("health_adopted"),
    )
}
