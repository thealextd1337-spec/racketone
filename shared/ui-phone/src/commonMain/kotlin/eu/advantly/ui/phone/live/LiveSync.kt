package eu.advantly.ui.phone.live

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.advantly.presentation.match.DeviceKind
import eu.advantly.presentation.match.LiveMatchSnapshot
import eu.advantly.presentation.match.SyncInfo
import eu.advantly.presentation.match.WallClock
import eu.advantly.ui.phone.common.AdvantlyButton
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.designsystem.Radii
import eu.advantly.ui.phone.designsystem.Spacing
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.absorbed_notice
import eu.advantly.ui.phone.resources.action_cancel
import eu.advantly.ui.phone.resources.action_ok
import eu.advantly.ui.phone.resources.age_minutes
import eu.advantly.ui.phone.resources.age_seconds
import eu.advantly.ui.phone.resources.live_role_display
import eu.advantly.ui.phone.resources.live_scoring_by
import eu.advantly.ui.phone.resources.rejected_many
import eu.advantly.ui.phone.resources.rejected_one
import eu.advantly.ui.phone.resources.rejected_title
import eu.advantly.ui.phone.resources.scorer_other_device
import eu.advantly.ui.phone.resources.scorer_phone_of
import eu.advantly.ui.phone.resources.scorer_watch_of
import eu.advantly.ui.phone.resources.scorer_your_phone
import eu.advantly.ui.phone.resources.scorer_your_watch
import eu.advantly.ui.phone.resources.stale_text
import eu.advantly.ui.phone.resources.stale_title
import eu.advantly.ui.phone.resources.sync_in_sync
import eu.advantly.ui.phone.resources.sync_local
import eu.advantly.ui.phone.resources.sync_pending
import eu.advantly.ui.phone.resources.sync_scorer_connected
import eu.advantly.ui.phone.resources.sync_scorer_unknown
import eu.advantly.ui.phone.resources.sync_stale
import eu.advantly.ui.phone.resources.takeover_action
import eu.advantly.ui.phone.resources.takeover_confirm
import eu.advantly.ui.phone.resources.takeover_text
import eu.advantly.ui.phone.resources.takeover_title
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/** Sichtdauer des Hinweises „Doppelte Eingabe zusammengeführt“. */
private const val ABSORBED_NOTICE_MILLIS = 3_000L

/** Takt für „Stand vor X s“. */
private const val STALENESS_TICK_MILLIS = 1_000L

/**
 * Aktuelle Wanduhrzeit, auf Anzeige-Geräten ([ticking]) jede Sekunde neu gelesen – Basis für „Stand vor X s“ und das
 * Übernahmeangebot nach > 30 s (04 §3.2).
 */
@Composable
internal fun rememberNow(
    clock: WallClock,
    ticking: Boolean,
): Long {
    var now by remember { mutableLongStateOf(clock.nowMs()) }
    LaunchedEffect(clock, ticking) {
        now = clock.nowMs()
        while (ticking) {
            delay(STALENESS_TICK_MILLIS)
            now = clock.nowMs()
        }
    }
    return now
}

/** „45 s“ bzw. „3 min“. */
@Composable
internal fun ageText(ms: Long): String {
    val seconds = ms / 1_000
    return if (seconds <
        60
    ) {
        stringResource(Res.string.age_seconds, seconds.toInt())
    } else {
        stringResource(Res.string.age_minutes, (seconds / 60).toInt())
    }
}

/** Zählendes Gerät als Nominalphrase: „Uhr von Alex“ / „deine Uhr“ (P1: immer ein eigenes Gerät). */
@Composable
internal fun scorerDevice(snapshot: LiveMatchSnapshot): String {
    val name = snapshot.scorerName.orEmpty()
    return when (snapshot.scorerKind) {
        DeviceKind.WATCH -> {
            if (name.isBlank()) {
                stringResource(
                    Res.string.scorer_your_watch,
                )
            } else {
                stringResource(Res.string.scorer_watch_of, name)
            }
        }

        DeviceKind.PHONE -> {
            if (name.isBlank()) {
                stringResource(
                    Res.string.scorer_your_phone,
                )
            } else {
                stringResource(Res.string.scorer_phone_of, name)
            }
        }

        DeviceKind.OTHER -> {
            stringResource(Res.string.scorer_other_device)
        }
    }
}

/**
 * Sync-Pill (04 §3.2, §5.6), Punkt + Text, nie nur Farbe. Zähler: „Synchron“ · „N ausstehend“ · „Nur lokal“.
 * Anzeige: „Zähler verbunden“ · „Stand vor X s“ · „Zähler nicht verbunden“.
 */
@Composable
internal fun SyncPill(
    snapshot: LiveMatchSnapshot,
    nowMs: Long,
    outdoor: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = AdvantlyTheme.colors
    val sync = snapshot.sync
    val (text, dot) =
        if (snapshot.isScorer) {
            when {
                sync.peers == 0 -> stringResource(Res.string.sync_local) to colors.text2
                sync.pendingToPeers == 0 -> stringResource(Res.string.sync_in_sync) to colors.success
                else -> stringResource(Res.string.sync_pending, sync.pendingToPeers) to colors.hint
            }
        } else {
            val stale = sync.staleForMs(nowMs)
            when {
                sync.scorerConnected -> {
                    stringResource(Res.string.sync_scorer_connected) to colors.success
                }

                stale == null -> {
                    stringResource(Res.string.sync_scorer_unknown) to colors.danger
                }

                else -> {
                    stringResource(Res.string.sync_stale, ageText(stale)) to
                        if (stale > SyncInfo.STALE_AFTER_MS) colors.danger else colors.hint
                }
            }
        }
    Row(
        modifier
            .testTag("sync_pill")
            .clearAndSetSemantics {
                contentDescription = text
                liveRegion = LiveRegionMode.Polite
            }.heightIn(min = 28.dp)
            .background(if (outdoor) Color.Black else colors.surface, Radii.pillShape)
            .then(if (outdoor) Modifier.border(1.5.dp, Color.White, Radii.pillShape) else Modifier)
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).background(dot, CircleShape))
        Text(text, style = AdvantlyTheme.typography.label.copy(fontSize = 13.sp), color = if (outdoor) Color.White else colors.text)
    }
}

/** Deutliche Kennzeichnung des Anzeige-Modus (04 H6a): „ANZEIGE · Es zählt: Uhr von Alex“. */
@Composable
internal fun DisplayBanner(
    snapshot: LiveMatchSnapshot,
    outdoor: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = AdvantlyTheme.colors
    val role = stringResource(Res.string.live_role_display)
    val by = stringResource(Res.string.live_scoring_by, scorerDevice(snapshot))
    Row(
        modifier
            .testTag("display_banner")
            .clearAndSetSemantics {
                contentDescription = "$role · $by"
                heading()
            }.fillMaxWidth()
            .background(if (outdoor) Color.Black else colors.surface2, Radii.cardShape)
            .border(1.5.dp, if (outdoor) Color.White else colors.hint, Radii.cardShape)
            .padding(horizontal = Spacing.m, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Text(role.uppercase(), style = AdvantlyTheme.typography.overline, color = if (outdoor) Color.White else colors.hint)
        Text(
            by,
            style = AdvantlyTheme.typography.label,
            color = if (outdoor) Color.White else colors.text,
            modifier = Modifier.testTag("display_scorer"),
        )
    }
}

/** Hinweiskarte über den Flächen (Titel, Text, Aktion). */
@Composable
private fun NoticeCard(
    tag: String,
    title: String,
    text: String,
    action: String,
    actionTag: String,
    onAction: () -> Unit,
    modifier: Modifier,
) {
    val colors = AdvantlyTheme.colors
    Column(
        modifier
            .testTag(tag)
            .fillMaxWidth()
            .background(colors.surface2, Radii.cardShape)
            .border(1.5.dp, colors.hint, Radii.cardShape)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(Spacing.m),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Text(title, style = AdvantlyTheme.typography.label, color = colors.text)
        Text(text, style = AdvantlyTheme.typography.body, color = colors.text2)
        AdvantlyButton(action, primary = true, onClick = onAction, modifier = Modifier.fillMaxWidth().testTag(actionTag))
    }
}

/** Nach > 30 s ohne Verbindung zum Zähler (04 §3.2): Hinweis mit „Zählen übernehmen“ (öffnet die Bestätigung). */
@Composable
internal fun StaleHint(
    snapshot: LiveMatchSnapshot,
    staleForMs: Long,
    onTakeOver: () -> Unit,
    modifier: Modifier = Modifier,
) = NoticeCard(
    tag = "stale_hint",
    title = stringResource(Res.string.stale_title),
    text = stringResource(Res.string.stale_text, ageText(staleForMs), scorerDevice(snapshot)),
    action = stringResource(Res.string.takeover_action),
    actionTag = "stale_takeover",
    onAction = onTakeOver,
    modifier = modifier,
)

/** „Zählen wurde übernommen – N Eingaben nicht übernommen“ (02 §7.5 Nr. 4). */
@Composable
internal fun RejectedNotice(
    count: Int,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) = NoticeCard(
    tag = "rejected_notice",
    title = stringResource(Res.string.rejected_title),
    text = if (count == 1) stringResource(Res.string.rejected_one) else stringResource(Res.string.rejected_many, count),
    action = stringResource(Res.string.action_ok),
    actionTag = "rejected_ok",
    onAction = onDismiss,
    modifier = modifier,
)

/**
 * Bestätigung „Zählen übernehmen“ (04 §3.2): „Auf diesem Gerät zählen? Bisher zählt [Gerät]. Danach zeigt das bisherige
 * Gerät nur noch an.“ Abbrechen ändert nichts.
 */
@Composable
internal fun TakeOverDialog(
    snapshot: LiveMatchSnapshot,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.takeover_title)) },
        text = { Text(stringResource(Res.string.takeover_text, scorerDevice(snapshot))) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("takeover_confirm")) {
                Text(stringResource(Res.string.takeover_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("takeover_cancel")) {
                Text(stringResource(Res.string.action_cancel))
            }
        },
        modifier = Modifier.testTag("takeover_dialog"),
    )
}

/** Kurzer Hinweis „Doppelte Eingabe zusammengeführt“ (04 §3.2), verschwindet nach 3 s. */
@Composable
internal fun AbsorbedNotice(
    id: Int,
    onTimeout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(id) {
        delay(ABSORBED_NOTICE_MILLIS)
        onTimeout()
    }
    val colors = AdvantlyTheme.colors
    Text(
        stringResource(Res.string.absorbed_notice),
        style = AdvantlyTheme.typography.label,
        color = colors.text,
        modifier =
            modifier
                .testTag("absorbed_notice")
                .semantics { liveRegion = LiveRegionMode.Polite }
                .background(colors.surface2, Radii.cardShape)
                .border(1.dp, colors.line, Radii.cardShape)
                .padding(horizontal = Spacing.l, vertical = Spacing.m),
    )
}
