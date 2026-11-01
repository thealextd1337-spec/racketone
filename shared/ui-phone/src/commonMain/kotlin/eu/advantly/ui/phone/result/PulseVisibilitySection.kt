package eu.advantly.ui.phone.result

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.semantics.Role
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.TelemetryVisibility
import eu.advantly.presentation.match.TelemetrySettingsPort
import eu.advantly.ui.phone.common.ChoiceChip
import eu.advantly.ui.phone.common.SectionTitle
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.designsystem.Spacing
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.action_cancel
import eu.advantly.ui.phone.resources.health_delete_match
import eu.advantly.ui.phone.resources.health_delete_match_text
import eu.advantly.ui.phone.resources.health_pulse_not_recorded
import eu.advantly.ui.phone.resources.health_pulse_share_pending
import eu.advantly.ui.phone.resources.health_pulse_title
import eu.advantly.ui.phone.wizard.PULSE_LEVELS
import eu.advantly.ui.phone.wizard.pulseLevelLabel
import org.jetbrains.compose.resources.stringResource

/**
 * Pulsverlauf im Match-Detail (Plan Puls E2): Stufe nachträglich ändern. „Gar nicht“ löscht nach Rückfrage den
 * Pulsverlauf auf Handy und Uhr („Pulsverlauf löschen“). Ohne Aufzeichnung sind die anderen Stufen ausgegraut. Erscheint
 * nur, wenn es etwas zu zeigen gibt (Aufzeichnung oder gewählte Stufe).
 */
@Composable
fun PulseVisibilitySection(
    port: TelemetrySettingsPort,
    matchId: MatchId,
) {
    val revision by port.healthRevision.collectAsState()
    val setting = remember(matchId, revision) { port.pulseSetting(matchId) }
    if (!setting.recorded && setting.visibility == TelemetryVisibility.OFF) return
    var confirmDelete by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("pulse_settings")) {
        SectionTitle(stringResource(Res.string.health_pulse_title))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            PULSE_LEVELS.forEach { level ->
                ChoiceChip(
                    pulseLevelLabel(level),
                    selected = setting.visibility == level,
                    onClick = {
                        when {
                            level == setting.visibility -> Unit
                            level == TelemetryVisibility.OFF && setting.recorded -> confirmDelete = true
                            else -> port.setPulseVisibility(matchId, level)
                        }
                    },
                    tag = "pulse_level_${level.wire}",
                    role = Role.RadioButton,
                    enabled = setting.recorded || level == TelemetryVisibility.OFF,
                )
            }
        }
        val hint =
            when {
                !setting.recorded -> Res.string.health_pulse_not_recorded
                setting.visibility.sharesWithOthers -> Res.string.health_pulse_share_pending
                else -> null
            }
        hint?.let {
            Text(
                stringResource(it),
                style = AdvantlyTheme.typography.caption,
                color = AdvantlyTheme.colors.text2,
                modifier = Modifier.testTag("pulse_hint"),
            )
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(Res.string.health_delete_match)) },
            text = { Text(stringResource(Res.string.health_delete_match_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        port.setPulseVisibility(matchId, TelemetryVisibility.OFF)
                    },
                    modifier = Modifier.testTag("pulse_delete_confirm"),
                ) { Text(stringResource(Res.string.health_delete_match), color = AdvantlyTheme.colors.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }
}
