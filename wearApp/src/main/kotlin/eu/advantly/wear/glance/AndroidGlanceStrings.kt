package eu.advantly.wear.glance

import android.content.Context
import eu.advantly.wear.R

class AndroidGlanceStrings(
    private val context: Context,
) : GlanceStrings {
    override fun get(
        label: GlanceLabel,
        vararg args: Any,
    ): String = context.getString(resource(label), *args)

    private fun resource(label: GlanceLabel): Int =
        when (label) {
            GlanceLabel.APP -> R.string.app_name
            GlanceLabel.APP_SHORT -> R.string.glance_app_short
            GlanceLabel.APP_ACTION -> R.string.glance_app_action
            GlanceLabel.PAUSED_SHORT -> R.string.glance_paused_short
            GlanceLabel.DECISION_SHORT -> R.string.glance_decision_short
            GlanceLabel.REPEAT -> R.string.home_repeat_last
            GlanceLabel.NEW -> R.string.home_new_match
            GlanceLabel.OPEN -> R.string.glance_open
            GlanceLabel.SETS -> R.string.glance_sets
            GlanceLabel.GAMES -> R.string.glance_games
            GlanceLabel.POINTS -> R.string.glance_points
            GlanceLabel.SERVE -> R.string.glance_serve
            GlanceLabel.SCORER -> R.string.role_scorer_short
            GlanceLabel.DISPLAY -> R.string.glance_display
            GlanceLabel.PAUSED -> R.string.glance_paused
            GlanceLabel.DECISION -> R.string.glance_decision
            GlanceLabel.TENNIS -> R.string.sport_tennis
            GlanceLabel.PADEL -> R.string.sport_padel
            GlanceLabel.PICKLEBALL -> R.string.sport_pickleball
            GlanceLabel.BADMINTON -> R.string.sport_badminton
            GlanceLabel.TABLE_TENNIS -> R.string.sport_table_tennis
            GlanceLabel.SINGLES -> R.string.discipline_singles
            GlanceLabel.DOUBLES -> R.string.discipline_doubles
            GlanceLabel.BEST_OF -> R.string.format_best_of
            GlanceLabel.OPEN_FORMAT -> R.string.format_open_sets
            GlanceLabel.GAMES_TO -> R.string.glance_games_to
            GlanceLabel.POINTS_TO -> R.string.glance_points_to
            GlanceLabel.YOU -> R.string.player_you
            GlanceLabel.PARTNER -> R.string.player_partner
            GlanceLabel.OPPONENT_1 -> R.string.player_opponent_1
            GlanceLabel.OPPONENT_2 -> R.string.player_opponent_2
        }
}
