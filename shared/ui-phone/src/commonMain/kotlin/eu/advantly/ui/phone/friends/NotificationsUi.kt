package eu.advantly.ui.phone.friends

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import eu.advantly.presentation.push.NotificationPromptPort
import eu.advantly.presentation.push.NotificationSettingsPort
import eu.advantly.ui.phone.common.AdvantlyButton
import eu.advantly.ui.phone.common.ToggleRow
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.designsystem.Radii
import eu.advantly.ui.phone.designsystem.Spacing
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.notifications_hint
import eu.advantly.ui.phone.resources.notifications_hint_enable
import eu.advantly.ui.phone.resources.notifications_hint_later
import eu.advantly.ui.phone.resources.notifications_prompt_body
import eu.advantly.ui.phone.resources.notifications_prompt_enable
import eu.advantly.ui.phone.resources.notifications_prompt_later
import eu.advantly.ui.phone.resources.notifications_prompt_more
import eu.advantly.ui.phone.resources.notifications_prompt_title
import eu.advantly.ui.phone.resources.notifications_section
import eu.advantly.ui.phone.resources.notifications_toggle
import eu.advantly.ui.phone.resources.notifications_toggle_sub
import org.jetbrains.compose.resources.stringResource

/** Profil → „Benachrichtigungen“ (Plan Live §3.3): ein Schalter, standardmäßig aus; die Berechtigung fragt die Plattform. */
@Composable
fun NotificationsSection(port: NotificationSettingsPort) {
    val enabled by port.enabled.collectAsState()
    Column(Modifier.fillMaxWidth().testTag("notifications_settings"), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text(
            stringResource(Res.string.notifications_section).uppercase(),
            style = AdvantlyTheme.typography.overline,
            color = AdvantlyTheme.colors.text2,
            modifier = Modifier.semantics { heading() },
        )
        ToggleRow(
            stringResource(Res.string.notifications_toggle),
            stringResource(Res.string.notifications_toggle_sub),
            checked = enabled,
            onChange = port::setEnabled,
            tag = "notifications_toggle",
        )
    }
}

/** Dezenter Hinweis im Freunde-Tab, solange keine Benachrichtigungen erscheinen; „Nicht jetzt“ blendet ihn 30 Tage aus. */
@Composable
internal fun NotificationsHint(port: NotificationSettingsPort) {
    val show by port.hint.collectAsState()
    if (!show) return
    Column(
        Modifier
            .fillMaxWidth()
            .background(AdvantlyTheme.colors.surface, Radii.cardShape)
            .padding(Spacing.l)
            .testTag("notifications_hint"),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Text(stringResource(Res.string.notifications_hint), style = AdvantlyTheme.typography.body, color = AdvantlyTheme.colors.text)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Button({ port.setEnabled(true) }, modifier = Modifier.testTag("notifications_hint_enable")) {
                Text(stringResource(Res.string.notifications_hint_enable))
            }
            TextButton(port::dismissHint, modifier = Modifier.testTag("notifications_hint_later")) {
                Text(stringResource(Res.string.notifications_hint_later))
            }
        }
    }
}

/** Erklärseite im passenden Moment (Plan W12): Nutzen zuerst, danach fragt „Einschalten“ die Systemberechtigung ab. */
@Composable
internal fun NotificationPromptSheet(port: NotificationPromptPort) {
    val colors = AdvantlyTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.bg)
            .safeContentPadding()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.l)
            .testTag("notifications_prompt"),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Text(
            stringResource(Res.string.notifications_prompt_title),
            style = AdvantlyTheme.typography.headline,
            color = colors.text,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(Res.string.notifications_prompt_body), style = AdvantlyTheme.typography.body, color = colors.text)
        Text(stringResource(Res.string.notifications_prompt_more), style = AdvantlyTheme.typography.body, color = colors.text2)
        AdvantlyButton(
            text = stringResource(Res.string.notifications_prompt_enable),
            primary = true,
            onClick = port::accept,
            modifier = Modifier.fillMaxWidth().testTag("notifications_prompt_enable"),
        )
        AdvantlyButton(
            text = stringResource(Res.string.notifications_prompt_later),
            primary = false,
            onClick = port::later,
            modifier = Modifier.fillMaxWidth().testTag("notifications_prompt_later"),
        )
    }
}
