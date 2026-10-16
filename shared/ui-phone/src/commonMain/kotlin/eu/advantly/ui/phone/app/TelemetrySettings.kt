package eu.advantly.ui.phone.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import eu.advantly.core.model.TelemetryVisibility
import eu.advantly.presentation.match.TelemetrySettingsPort
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.designsystem.Radii
import eu.advantly.ui.phone.designsystem.Spacing
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.telemetry_intro
import eu.advantly.ui.phone.resources.telemetry_off
import eu.advantly.ui.phone.resources.telemetry_off_sub
import eu.advantly.ui.phone.resources.telemetry_private
import eu.advantly.ui.phone.resources.telemetry_private_sub
import eu.advantly.ui.phone.resources.telemetry_section
import eu.advantly.ui.phone.resources.telemetry_share
import eu.advantly.ui.phone.resources.telemetry_share_pending
import eu.advantly.ui.phone.resources.telemetry_share_sub
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Aktuelle Telemetrie-Sichtbarkeit für die Live-Anzeige (P2-J); [eu.advantly.ui.phone.AdvantlyApp] stellt sie aus dem
 * [TelemetrySettingsPort] bereit. Ohne Bereitstellung (Previews, Einzeltests) gilt der Standard „Nur für mich“.
 */
val LocalTelemetryVisibility = compositionLocalOf { TelemetryVisibility.DEFAULT }

/**
 * Einstellung „Puls & Schritte“ (P2-J, F19/F29) im Profil: „Teilen“, „Nur für mich“ (Standard) oder „Gar nicht“, je mit
 * kurzer Erklärung. Gilt für Uhr und Handy (Abgleich über die Match-Session, letzte Änderung gewinnt). Bei „Teilen“ steht
 * der Hinweis, dass es erst mit Freunden/Live-Ansicht wirkt. Eigener Baustein, damit er mit dem Profil (P2) umziehen kann.
 */
@Composable
fun TelemetryVisibilitySection(
    port: TelemetrySettingsPort,
    modifier: Modifier = Modifier,
) {
    val colors = AdvantlyTheme.colors
    val selected by port.telemetryVisibility.collectAsState()
    Column(modifier.fillMaxWidth().testTag("telemetry_settings"), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text(
            stringResource(Res.string.telemetry_section).uppercase(),
            style = AdvantlyTheme.typography.overline,
            color = colors.text2,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(Res.string.telemetry_intro), style = AdvantlyTheme.typography.caption, color = colors.text2)
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            TelemetryVisibility.entries.forEach { option ->
                val (title, subtitle) = texts(option)
                TelemetryOptionCard(option, title, subtitle, selected == option) { port.setTelemetryVisibility(option) }
            }
        }
        if (selected.sharesWithOthers) {
            Text(
                stringResource(Res.string.telemetry_share_pending),
                style = AdvantlyTheme.typography.caption,
                color = colors.brand,
                modifier = Modifier.testTag("telemetry_share_pending"),
            )
        }
    }
}

private fun texts(option: TelemetryVisibility): Pair<StringResource, StringResource> =
    when (option) {
        TelemetryVisibility.SHARE -> Res.string.telemetry_share to Res.string.telemetry_share_sub
        TelemetryVisibility.PRIVATE -> Res.string.telemetry_private to Res.string.telemetry_private_sub
        TelemetryVisibility.OFF -> Res.string.telemetry_off to Res.string.telemetry_off_sub
    }

/** Auswahlkarte mit Radio-Punkt (Rolle `RadioButton`, ≥ 64 dp) wie „Wer zählt?“. */
@Composable
private fun TelemetryOptionCard(
    option: TelemetryVisibility,
    title: StringResource,
    subtitle: StringResource,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val colors = AdvantlyTheme.colors
    Row(
        Modifier
            .testTag("telemetry_${option.wire}")
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .background(colors.surface, Radii.cardShape)
            .border(1.5.dp, if (selected) colors.brand else colors.line, Radii.cardShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Box(
            Modifier
                .size(22.dp)
                .border(2.dp, if (selected) colors.brand else colors.text3, CircleShape)
                .padding(5.dp)
                .background(if (selected) colors.brand else Color.Transparent, CircleShape),
        )
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = AdvantlyTheme.typography.label, color = colors.text)
            Text(stringResource(subtitle), style = AdvantlyTheme.typography.caption, color = colors.text2)
        }
    }
}
