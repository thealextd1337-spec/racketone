package eu.advantly.ui.phone.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.advantly.core.model.Side
import eu.advantly.presentation.match.ConflictInfo
import eu.advantly.presentation.match.DeviceKind
import eu.advantly.presentation.match.ForkBranch
import eu.advantly.presentation.match.ForkChoice
import eu.advantly.presentation.match.ForkInfo
import eu.advantly.presentation.match.LiveMatchSnapshot
import eu.advantly.presentation.match.SyncInfo
import eu.advantly.presentation.match.SyncState
import eu.advantly.ui.phone.common.AdvantlyButton
import eu.advantly.ui.phone.common.stateScore
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.designsystem.Radii
import eu.advantly.ui.phone.designsystem.Spacing
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.absorbed_notice
import eu.advantly.ui.phone.resources.conflict_keep
import eu.advantly.ui.phone.resources.conflict_title
import eu.advantly.ui.phone.resources.conflict_undo_them
import eu.advantly.ui.phone.resources.conflict_undo_us
import eu.advantly.ui.phone.resources.device_other
import eu.advantly.ui.phone.resources.device_phone
import eu.advantly.ui.phone.resources.device_watch
import eu.advantly.ui.phone.resources.fork_merge
import eu.advantly.ui.phone.resources.fork_text
import eu.advantly.ui.phone.resources.fork_title
import eu.advantly.ui.phone.resources.fork_version
import eu.advantly.ui.phone.resources.sync_conflict
import eu.advantly.ui.phone.resources.sync_connected_many
import eu.advantly.ui.phone.resources.sync_connected_one
import eu.advantly.ui.phone.resources.sync_local
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/** Sichtdauer des Hinweises „Doppelte Eingabe zusammengeführt“. */
private const val ABSORBED_NOTICE_MILLIS = 3_000L

/** Sync-Pill (04 §3.2, §5.6): Punkt + Text – „Uhr verbunden“ · „Nur lokal“ · „Konflikt“ (nie nur Farbe). */
@Composable
internal fun SyncPill(
    sync: SyncInfo,
    outdoor: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = AdvantlyTheme.colors
    val (text, dot) =
        when (sync.state) {
            SyncState.CONNECTED -> {
                (
                    if (sync.peers >
                        1
                    ) {
                        stringResource(Res.string.sync_connected_many, sync.peers)
                    } else {
                        stringResource(Res.string.sync_connected_one)
                    }
                ) to
                    colors.success
            }

            SyncState.LOCAL_ONLY -> {
                stringResource(Res.string.sync_local) to colors.text2
            }

            SyncState.CONFLICT -> {
                stringResource(Res.string.sync_conflict) to colors.danger
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
        androidx.compose.foundation.layout
            .Box(Modifier.size(8.dp).background(dot, CircleShape))
        Text(text, style = AdvantlyTheme.typography.label.copy(fontSize = 13.sp), color = if (outdoor) Color.White else colors.text)
    }
}

/**
 * Konflikt-Banner (01 §4.4): „Gleichzeitig Punkt für Wir und Gegner – korrigieren?“ mit 1-Tap-Undo je Side und
 * „Beide behalten“.
 */
@Composable
internal fun ConflictBanner(
    conflict: ConflictInfo,
    controller: LiveScoreController,
    modifier: Modifier = Modifier,
) {
    val colors = AdvantlyTheme.colors
    Column(
        modifier
            .testTag("conflict_banner")
            .fillMaxWidth()
            .background(colors.surface2, Radii.cardShape)
            .border(1.5.dp, colors.hint, Radii.cardShape)
            .semantics { liveRegion = LiveRegionMode.Assertive }
            .padding(Spacing.m),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Text(stringResource(Res.string.conflict_title), style = AdvantlyTheme.typography.label, color = colors.text)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            AdvantlyButton(
                stringResource(Res.string.conflict_undo_us),
                primary = false,
                onClick = { controller.undoConflictPoint(conflict.id, Side.A) },
                modifier = Modifier.weight(1f).testTag("conflict_undo_us"),
            )
            AdvantlyButton(
                stringResource(Res.string.conflict_undo_them),
                primary = false,
                onClick = { controller.undoConflictPoint(conflict.id, Side.B) },
                modifier = Modifier.weight(1f).testTag("conflict_undo_them"),
            )
        }
        AdvantlyButton(
            stringResource(Res.string.conflict_keep),
            primary = false,
            onClick = { controller.dismissConflict(conflict.id) },
            modifier = Modifier.fillMaxWidth().testTag("conflict_keep"),
        )
    }
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

@Composable
private fun deviceName(branch: ForkBranch): String =
    stringResource(
        when (branch.device) {
            DeviceKind.WATCH -> Res.string.device_watch
            DeviceKind.PHONE -> Res.string.device_phone
            DeviceKind.OTHER -> Res.string.device_other
        },
    )

/**
 * Verzweigungs-Dialog: „Version Uhr (A:B) / Version Handy (C:D) / Beide zusammenführen“. Die Vorschau-Stände kommen
 * aus der Engine (über den Port); die Wahl wird als `ForkResolved` repliziert.
 */
@Composable
internal fun BoxScope.ForkSheet(
    controller: LiveScoreController,
    snapshot: LiveMatchSnapshot,
    fork: ForkInfo,
) {
    val sport = snapshot.setup.sport
    val title = stringResource(Res.string.fork_title)
    LiveSheet(title = title, tag = "fork_sheet") {
        Text(title, style = AdvantlyTheme.typography.title, color = AdvantlyTheme.colors.text)
        Text(stringResource(Res.string.fork_text), style = AdvantlyTheme.typography.body, color = AdvantlyTheme.colors.text2)
        for ((choice, branch) in listOf(ForkChoice.BRANCH_A to fork.branchA, ForkChoice.BRANCH_B to fork.branchB)) {
            AdvantlyButton(
                stringResource(Res.string.fork_version, deviceName(branch), stateScore(branch.preview, sport)),
                primary = false,
                onClick = { controller.resolveFork(fork.forkId, choice) },
                modifier = Modifier.fillMaxWidth().testTag("fork_${choice.name.lowercase()}"),
            )
        }
        AdvantlyButton(
            stringResource(Res.string.fork_merge, stateScore(fork.mergedPreview, sport)),
            primary = true,
            onClick = { controller.resolveFork(fork.forkId, ForkChoice.BOTH) },
            modifier = Modifier.fillMaxWidth().testTag("fork_both"),
        )
    }
}
