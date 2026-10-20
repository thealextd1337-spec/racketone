package eu.advantly.wear.glance

import android.content.Context
import eu.advantly.data.AndroidInstallationAvailability
import eu.advantly.data.AndroidInstallationGuard
import eu.advantly.wear.R

/** Provider zeigen bei Speicherfehlern weder alte Snapshots noch Schnellstart-Aktionen. */
internal fun installationGlance(context: Context): Pair<GlanceState, GlanceCopy> {
    if (AndroidInstallationGuard.check(context) is AndroidInstallationAvailability.Ready) {
        val state = GlancePublisher.snapshot
        return state to GlanceText.render(state, AndroidGlanceStrings(context))
    }
    val title = context.getString(R.string.installation_error_title)
    val open = context.getString(R.string.glance_open_app)
    return GlanceState() to GlanceCopy(title, listOf(open), GlanceAction.OPEN, "$title. $open", title, "$title. $open")
}
