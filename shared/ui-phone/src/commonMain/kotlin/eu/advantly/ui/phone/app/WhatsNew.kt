package eu.advantly.ui.phone.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import eu.advantly.ui.phone.common.AdvantlyButton
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.designsystem.Spacing
import eu.advantly.ui.phone.home.WatchInstallHost
import eu.advantly.ui.phone.home.WatchUpdateHint
import eu.advantly.ui.phone.home.pendingWatchUpdates
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.allStringResources
import eu.advantly.ui.phone.resources.whats_new_actions
import eu.advantly.ui.phone.resources.whats_new_done
import eu.advantly.ui.phone.resources.whats_new_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Lokale Einstellung (Plan 0.5.1 §6): zuletzt gezeigtes bzw. beim Einstieg übernommenes `buildNumber`. */
internal const val WHATS_NEW_KEY = "whatsNew.lastSeenBuild"

/** Punkte zu [build] aus `whats_new_<build>_<n>` (DE/EN in composeResources), in Schlüsselreihenfolge. */
internal fun whatsNewPoints(build: Int): List<StringResource> =
    Res.allStringResources.entries
        .filter { it.key.startsWith("whats_new_${build}_") }
        .sortedBy { it.key }
        .map { it.value }

/**
 * Vollbild-Blatt „Was ist neu?“ (Plan 0.5.1 §6): nur die Punkte der aktuellen Version, dazu offene Aktionen –
 * „Pulsverlauf aufzeichnen“ solange auf diesem Gerät unentschieden, „Uhr-App aktualisieren“ solange zutreffend. „Los geht’s“
 * übernimmt die Wahl und schließt; die Aktionen bleiben im Profil bzw. auf der Startseite erreichbar.
 */
@Composable
internal fun WhatsNewSheet(
    model: AdvantlyAppModel,
    versionName: String,
    points: List<StringResource>,
    watchInstall: WatchInstallHost?,
    onDone: () -> Unit,
) {
    val colors = AdvantlyTheme.colors
    val healthOpen by model.telemetry.healthConsentOpen.collectAsState()
    val devices by model.live.deviceStatuses.collectAsState()
    val choice = rememberHealthChoice(model.telemetry)
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.bg)
            .safeContentPadding()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.l)
            .testTag("whats_new"),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Text(
            stringResource(Res.string.whats_new_title, versionName),
            style = AdvantlyTheme.typography.headline,
            color = colors.text,
            modifier = Modifier.semantics { heading() },
        )
        points.forEach { Text("•  " + stringResource(it), style = AdvantlyTheme.typography.body, color = colors.text) }
        if (healthOpen || pendingWatchUpdates(model.live, devices).isNotEmpty()) {
            Text(
                stringResource(Res.string.whats_new_actions).uppercase(),
                style = AdvantlyTheme.typography.overline,
                color = colors.text2,
                modifier = Modifier.semantics { heading() },
            )
            if (healthOpen) HealthConsentChoice(choice)
            WatchUpdateHint(model.live, watchInstall)
        }
        AdvantlyButton(
            text = stringResource(Res.string.whats_new_done),
            primary = true,
            onClick = {
                if (healthOpen) choice.apply(model.telemetry)
                onDone()
            },
            modifier = Modifier.fillMaxWidth().testTag("whats_new_done"),
        )
    }
}
