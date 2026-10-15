package eu.advantly.ui.phone.wizard

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import eu.advantly.presentation.match.ScorerOption
import eu.advantly.ui.phone.common.ToggleRow
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.designsystem.Radii
import eu.advantly.ui.phone.designsystem.Spacing
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.wizard_scorer_phone
import eu.advantly.ui.phone.resources.wizard_scorer_phone_sub
import eu.advantly.ui.phone.resources.wizard_scorer_watch
import eu.advantly.ui.phone.resources.wizard_scorer_watch_none
import eu.advantly.ui.phone.resources.wizard_scorer_watch_sub
import eu.advantly.ui.phone.resources.wizard_watch_workout
import eu.advantly.ui.phone.resources.wizard_watch_workout_hint
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * „Wer zählt?“ (04 §3.1, P1): „Meine Uhr“ (Standard, wenn eine Uhr verbunden ist; sonst gesperrt mit Hinweis) oder
 * „Dieses Handy (Director)“, darunter „Workout auf meiner Uhr mitlaufen lassen“. Genutzt im Assistenten und in der
 * Bestätigung beim Schnellstart.
 */
@Composable
internal fun ScorerChooser(
    selected: ScorerOption,
    watchAvailable: Boolean,
    watchWorkout: Boolean,
    onSelect: (ScorerOption) -> Unit,
    onWatchWorkout: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            ScorerOptionCard(
                option = ScorerOption.MY_WATCH,
                title = Res.string.wizard_scorer_watch,
                subtitle = if (watchAvailable) Res.string.wizard_scorer_watch_sub else Res.string.wizard_scorer_watch_none,
                selected = selected == ScorerOption.MY_WATCH,
                enabled = watchAvailable,
                onSelect = onSelect,
            )
            ScorerOptionCard(
                option = ScorerOption.THIS_PHONE,
                title = Res.string.wizard_scorer_phone,
                subtitle = Res.string.wizard_scorer_phone_sub,
                selected = selected == ScorerOption.THIS_PHONE,
                enabled = true,
                onSelect = onSelect,
            )
        }
        ToggleRow(
            title = stringResource(Res.string.wizard_watch_workout),
            subtitle = stringResource(Res.string.wizard_watch_workout_hint),
            checked = watchWorkout,
            onChange = onWatchWorkout,
            tag = "toggle_watch_workout",
            modifier = Modifier.padding(top = Spacing.m),
        )
    }
}

/** Auswahlkarte mit Radio-Punkt (Rolle `RadioButton`, ≥ 64 dp). */
@Composable
private fun ScorerOptionCard(
    option: ScorerOption,
    title: StringResource,
    subtitle: StringResource,
    selected: Boolean,
    enabled: Boolean,
    onSelect: (ScorerOption) -> Unit,
) {
    val colors = AdvantlyTheme.colors
    Row(
        Modifier
            .testTag("scorer_${option.name}")
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .background(colors.surface, Radii.cardShape)
            .border(1.5.dp, if (selected) colors.brand else colors.line, Radii.cardShape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton) { onSelect(option) }
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
            Text(stringResource(title), style = AdvantlyTheme.typography.label, color = if (enabled) colors.text else colors.text3)
            Text(stringResource(subtitle), style = AdvantlyTheme.typography.caption, color = colors.text2)
        }
    }
}
