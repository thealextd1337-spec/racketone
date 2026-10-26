package eu.advantly.wear.glance

import android.content.Context
import eu.advantly.data.AndroidInstallationAvailability
import eu.advantly.data.AndroidInstallationGuard
import eu.advantly.wear.R

/**
 * Provider zeigen bei Speicherfehlern weder alte Snapshots noch Schnellstart-Aktionen. Tile- und Complication-Anfragen
 * kommen auf dem Main-Thread: Die Installationsprüfung läuft dafür asynchron, [onResult] danach auf dem Main-Thread.
 */
internal fun installationGlance(
    context: Context,
    onResult: (GlanceState, GlanceCopy) -> Unit,
) = AndroidInstallationGuard.checkAsync(context) { availability ->
    if (availability is AndroidInstallationAvailability.Ready) {
        val state = GlancePublisher.snapshot
        onResult(state, GlanceText.render(state, AndroidGlanceStrings(context)))
    } else {
        val title = context.getString(R.string.installation_error_title)
        val open = context.getString(R.string.glance_open_app)
        onResult(GlanceState(), GlanceCopy(title, listOf(open), GlanceAction.OPEN, "$title. $open", title, "$title. $open"))
    }
}
