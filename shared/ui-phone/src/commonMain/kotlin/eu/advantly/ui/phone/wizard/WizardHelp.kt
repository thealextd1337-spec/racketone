package eu.advantly.ui.phone.wizard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import eu.advantly.ui.phone.common.ChoiceChip
import eu.advantly.ui.phone.common.SectionTitle
import eu.advantly.ui.phone.designsystem.AdvantlyIcons
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.designsystem.Spacing
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.action_close
import eu.advantly.ui.phone.resources.help_badminton
import eu.advantly.ui.phone.resources.help_deciding
import eu.advantly.ui.phone.resources.help_deuce
import eu.advantly.ui.phone.resources.help_discipline
import eu.advantly.ui.phone.resources.help_end_change
import eu.advantly.ui.phone.resources.help_formats
import eu.advantly.ui.phone.resources.help_games
import eu.advantly.ui.phone.resources.help_pickleball
import eu.advantly.ui.phone.resources.help_serve_stats
import eu.advantly.ui.phone.resources.help_sets
import eu.advantly.ui.phone.resources.help_table_tennis
import eu.advantly.ui.phone.resources.help_tiebreak
import eu.advantly.ui.phone.resources.wizard_help_description
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Vollständiger Ressourcenkatalog: jedes Hilfesymbol hat genau einen erklärenden Text. */
internal enum class WizardHelp(
    val body: StringResource,
) {
    FORMATS(Res.string.help_formats),
    SETS(Res.string.help_sets),
    GAMES(Res.string.help_games),
    DEUCE(Res.string.help_deuce),
    DECIDING(Res.string.help_deciding),
    TIEBREAK(Res.string.help_tiebreak),
    SERVE_STATS(Res.string.help_serve_stats),
    DISCIPLINE(Res.string.help_discipline),
    BADMINTON(Res.string.help_badminton),
    TABLE_TENNIS(Res.string.help_table_tennis),
    PICKLEBALL(Res.string.help_pickleball),
    END_CHANGE(Res.string.help_end_change),
}

@Composable
internal fun HelpSection(
    title: String,
    topic: WizardHelp,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SectionTitle(title, Modifier.weight(1f))
        HelpIcon(title, topic)
    }
}

/** Tippbares, beschriftetes 48-dp-Ziel; Dialogtext scrollt auch bei 200 % Schrift. */
@Composable
internal fun HelpIcon(
    title: String,
    topic: WizardHelp,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = Modifier.size(Spacing.minTouchTarget).testTag("help_${topic.name}")) {
        Icon(AdvantlyIcons.Info, stringResource(Res.string.wizard_help_description, title), tint = AdvantlyTheme.colors.text2)
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                Text(
                    stringResource(topic.body),
                    modifier = Modifier.verticalScroll(rememberScrollState()).testTag("help_text"),
                )
            },
            confirmButton = {
                TextButton(onClick = { open = false }, modifier = Modifier.testTag("help_close")) {
                    Text(stringResource(Res.string.action_close))
                }
            },
            modifier = Modifier.testTag("help_dialog"),
        )
    }
}

/** Platz für jede Beschriftung statt schmaler Segmente bei großer Systemschrift. */
@Composable
internal fun <T> WizardChoices(
    options: List<T>,
    selected: T?,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    tagPrefix: String,
    modifier: Modifier = Modifier,
    accent: Color = AdvantlyTheme.colors.brand,
) {
    FlowRow(
        modifier.fillMaxWidth().padding(bottom = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        options.forEachIndexed { index, option ->
            ChoiceChip(
                label(option),
                selected == option,
                { onSelect(option) },
                "${tagPrefix}_$index",
                role = Role.RadioButton,
                accent = accent,
            )
        }
    }
}
