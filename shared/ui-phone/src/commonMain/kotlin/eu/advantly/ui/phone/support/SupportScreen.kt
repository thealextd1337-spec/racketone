package eu.advantly.ui.phone.support

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import eu.advantly.presentation.support.SupportViewModel
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.support_back
import eu.advantly.ui.phone.resources.support_body
import eu.advantly.ui.phone.resources.support_body_hint
import eu.advantly.ui.phone.resources.support_bug
import eu.advantly.ui.phone.resources.support_closed
import eu.advantly.ui.phone.resources.support_contact
import eu.advantly.ui.phone.resources.support_diagnostics
import eu.advantly.ui.phone.resources.support_discard
import eu.advantly.ui.phone.resources.support_empty
import eu.advantly.ui.phone.resources.support_error
import eu.advantly.ui.phone.resources.support_feature
import eu.advantly.ui.phone.resources.support_feedback
import eu.advantly.ui.phone.resources.support_frozen
import eu.advantly.ui.phone.resources.support_intro
import eu.advantly.ui.phone.resources.support_invalid
import eu.advantly.ui.phone.resources.support_new
import eu.advantly.ui.phone.resources.support_open
import eu.advantly.ui.phone.resources.support_planned
import eu.advantly.ui.phone.resources.support_rate
import eu.advantly.ui.phone.resources.support_receipt
import eu.advantly.ui.phone.resources.support_refresh
import eu.advantly.ui.phone.resources.support_reports
import eu.advantly.ui.phone.resources.support_resolved
import eu.advantly.ui.phone.resources.support_retry
import eu.advantly.ui.phone.resources.support_reviewing
import eu.advantly.ui.phone.resources.support_send
import eu.advantly.ui.phone.resources.support_storage
import eu.advantly.ui.phone.resources.support_title
import eu.advantly.ui.phone.resources.support_unavailable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Ein lokaler Entwurf, ausdrückliche Diagnosenfreigabe und quittierter Versand. */
@Composable
fun SupportScreen(
    model: SupportViewModel,
    diagnostics: Map<String, String>,
    onClose: () -> Unit,
) {
    val state by model.state.collectAsState()
    Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val fontScale = LocalDensity.current.fontScale
            val deviceDetails =
                (
                    diagnostics +
                        mapOf(
                            "display" to "${maxWidth.value.toInt()} × ${maxHeight.value.toInt()} dp",
                            "font_scale" to fontScale.toString(),
                        )
                ).filterValues { it.isNotBlank() }
                    .mapValues { it.value.take(200) }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(onClose) { Text(stringResource(Res.string.support_back)) }
                Text(stringResource(Res.string.support_open), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(Res.string.support_intro))
                if (!model.available) Text(stringResource(Res.string.support_unavailable))
                state.receipt?.let { receipt ->
                    SelectionContainer {
                        Text(
                            stringResource(Res.string.support_receipt, receipt),
                            modifier = Modifier.testTag("support_receipt"),
                        )
                    }
                }
                state.draft?.let { draft ->
                    val editable = !state.busy && !draft.submitted

                    fun edit(
                        category: String = draft.category,
                        title: String = draft.title,
                        body: String = draft.body,
                        contact: String = draft.contactEmail,
                        details: Map<String, String> = draft.diagnostics,
                    ) = model.edit(category, title, body, contact, details)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "feedback" to Res.string.support_feedback,
                            "feature" to Res.string.support_feature,
                            "bug" to Res.string.support_bug,
                        ).forEach { (value, label) ->
                            FilterChip(
                                selected = draft.category == value,
                                onClick = { edit(category = value) },
                                enabled = editable,
                                label = { Text(stringResource(label)) },
                                modifier = Modifier.testTag("support_category_$value"),
                            )
                        }
                    }
                    OutlinedTextField(
                        draft.title,
                        { edit(title = it) },
                        label = { Text(stringResource(Res.string.support_title)) },
                        enabled = editable,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("support_title"),
                    )
                    OutlinedTextField(
                        draft.body,
                        { edit(body = it) },
                        label = { Text(stringResource(Res.string.support_body)) },
                        supportingText = { Text(stringResource(Res.string.support_body_hint)) },
                        enabled = editable,
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth().testTag("support_body"),
                    )
                    OutlinedTextField(
                        draft.contactEmail,
                        { edit(contact = it) },
                        label = { Text(stringResource(Res.string.support_contact)) },
                        enabled = editable,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("support_contact"),
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(
                                draft.diagnostics.isNotEmpty(),
                                enabled = editable,
                                role = Role.Checkbox,
                                onValueChange = { edit(details = if (it) deviceDetails else emptyMap()) },
                            ).testTag("support_diagnostics"),
                    ) {
                        Checkbox(draft.diagnostics.isNotEmpty(), onCheckedChange = null, enabled = editable)
                        Text(stringResource(Res.string.support_diagnostics), modifier = Modifier.padding(top = 12.dp))
                    }
                    if (draft.diagnostics.isNotEmpty()) {
                        SelectionContainer {
                            Text(draft.diagnostics.values.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (draft.submitted) Text(stringResource(Res.string.support_frozen))
                    state.error?.let {
                        Text(
                            stringResource(errorLabel(it)),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("support_error"),
                        )
                    }
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Button(
                        model::submit,
                        enabled = model.available && !state.busy && draft.title.isNotBlank() && draft.body.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().testTag("support_send"),
                    ) {
                        Text(stringResource(if (draft.submitted) Res.string.support_retry else Res.string.support_send))
                    }
                    TextButton(model::discard, enabled = !state.busy) { Text(stringResource(Res.string.support_discard)) }
                } ?: run {
                    Text(stringResource(Res.string.support_storage))
                    TextButton(model::discard, enabled = !state.busy) { Text(stringResource(Res.string.support_discard)) }
                }
                if (state.ownerId != null) {
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    Text(stringResource(Res.string.support_reports), style = MaterialTheme.typography.titleLarge)
                    TextButton(model::refreshTickets, enabled = !state.busy) { Text(stringResource(Res.string.support_refresh)) }
                    if (state.tickets.isEmpty()) Text(stringResource(Res.string.support_empty))
                    state.tickets.forEach { ticket ->
                        Text(ticket.title, style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(statusLabel(ticket.status)), style = MaterialTheme.typography.labelLarge)
                        SelectionContainer { Text(ticket.body) }
                        ticket.messages.forEach { message -> SelectionContainer { Text(message.body) } }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

private fun errorLabel(error: String): StringResource =
    when (error) {
        "support_storage_failed" -> Res.string.support_storage
        "support_unavailable" -> Res.string.support_unavailable
        "support_invalid" -> Res.string.support_invalid
        "support_rate_limited" -> Res.string.support_rate
        else -> Res.string.support_error
    }

private fun statusLabel(status: String): StringResource =
    when (status) {
        "reviewing" -> Res.string.support_reviewing
        "planned" -> Res.string.support_planned
        "resolved" -> Res.string.support_resolved
        "closed" -> Res.string.support_closed
        else -> Res.string.support_new
    }
