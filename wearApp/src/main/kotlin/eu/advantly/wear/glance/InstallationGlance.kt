package eu.advantly.wear.glance

import android.content.Context
import eu.advantly.data.AndroidInstallationAvailability
import eu.advantly.data.AndroidInstallationGuard
import eu.advantly.wear.R

/**
 * Provider zeigen bei Speicherfehlern weder alte Snapshots noch Schnellstart-Aktionen. Tile- und Complication-Anfragen
 * kommen auf dem Main-Thread: Die Installationsprüfung läuft dafür asynchron, [onResult] danach auf dem Main-Thread.
 * Fehler beim Aufbau des Texts gehen an [onFailure] (Tile: an die Future), statt den Main-Thread abstürzen zu lassen.
 */
internal fun installationGlance(
    context: Context,
    onFailure: (Exception) -> Unit = { throw it },
    onResult: (GlanceState, GlanceCopy) -> Unit,
) = AndroidInstallationGuard.checkAsync(context) { availability ->
    val glance =
        try {
            if (availability is AndroidInstallationAvailability.Ready) {
                val state = GlancePublisher.snapshot
                state to GlanceText.render(state, AndroidGlanceStrings(context))
            } else {
                val title = context.getString(R.string.installation_error_title)
                val open = context.getString(R.string.glance_open_app)
                GlanceState() to GlanceCopy(title, listOf(open), GlanceAction.OPEN, "$title. $open", title, "$title. $open")
            }
        } catch (error: Exception) {
            return@checkAsync onFailure(error)
        }
    onResult(glance.first, glance.second)
}
