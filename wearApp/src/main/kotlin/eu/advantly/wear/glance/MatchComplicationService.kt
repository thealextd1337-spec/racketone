package eu.advantly.wear.glance

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import eu.advantly.wear.MainActivity
import eu.advantly.wear.R

/** Spielstand des Uhr-Matches oder App-Einstieg; kein Start-/Zählkommando auf dem Zifferblatt. */
class MatchComplicationService : ComplicationDataSourceService() {
    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationRequestListener,
    ) {
        listener.onComplicationData(data(request.complicationType, GlancePublisher.snapshot))
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        data(
            type,
            GlanceState(
                live =
                    GlanceMatch(
                        "1:0",
                        "3:2",
                        "30:15",
                        null,
                        true,
                        eu.advantly.core.scoring.MatchStatus.IN_PROGRESS,
                    ),
            ),
        )

    private fun data(
        type: ComplicationType,
        state: GlanceState,
    ): ComplicationData? {
        val copy = GlanceText.render(state, AndroidGlanceStrings(this))
        val tap =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).putExtra(GlanceLaunch.EXTRA_ACTION, GlanceAction.OPEN.name),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val description = plain(if (state.live == null) getString(R.string.glance_open_app) else copy.description)
        return when (type) {
            ComplicationType.SHORT_TEXT -> {
                ShortTextComplicationData
                    .Builder(plain(copy.shortText), description)
                    .setTapAction(tap)
                    .build()
            }

            ComplicationType.LONG_TEXT -> {
                LongTextComplicationData
                    .Builder(plain(if (state.live == null) getString(R.string.glance_open_app) else copy.longText), description)
                    .setTitle(plain(getString(R.string.app_name)))
                    .setTapAction(tap)
                    .build()
            }

            else -> {
                null
            }
        }
    }

    private fun plain(value: String): PlainComplicationText = PlainComplicationText.Builder(value).build()
}
