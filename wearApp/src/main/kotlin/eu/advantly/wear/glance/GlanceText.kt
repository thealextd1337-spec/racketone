package eu.advantly.wear.glance

import eu.advantly.core.model.Discipline
import eu.advantly.core.model.MatchMode
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.scoring.MatchStatus

/** Schlüssel statt UI-Literale: die Android-Bindung lädt DE/EN aus den Ressourcen. */
enum class GlanceLabel {
    APP,
    START,
    REPEAT,
    NEW,
    OPEN,
    SETS,
    GAMES,
    POINTS,
    SERVE,
    SCORER,
    DISPLAY,
    PAUSED,
    DECISION,
    TENNIS,
    PADEL,
    PICKLEBALL,
    BADMINTON,
    TABLE_TENNIS,
    SINGLES,
    DOUBLES,
    BEST_OF,
    OPEN_FORMAT,
    GAMES_TO,
    POINTS_TO,
    YOU,
    PARTNER,
    OPPONENT_1,
    OPPONENT_2,
}

fun interface GlanceStrings {
    fun get(
        label: GlanceLabel,
        vararg args: Any,
    ): String
}

data class GlanceCopy(
    val title: String,
    val lines: List<String>,
    val primary: GlanceAction,
    val description: String,
    val shortText: String,
    val longText: String,
)

/** Reine Zusammenstellung aller Provider-Texte, inklusive ungekürzter TalkBack-Beschreibung. */
object GlanceText {
    fun render(
        state: GlanceState,
        strings: GlanceStrings,
    ): GlanceCopy {
        val live = state.live
        if (live != null) {
            val sets = strings.get(GlanceLabel.SETS, live.sets)
            val games = live.games?.let { strings.get(GlanceLabel.GAMES, it) }
            val points = live.points?.let { strings.get(GlanceLabel.POINTS, it) }
            val role = strings.get(if (live.scorer) GlanceLabel.SCORER else GlanceLabel.DISPLAY)
            val status =
                when (live.status) {
                    MatchStatus.PAUSED -> strings.get(GlanceLabel.PAUSED)
                    MatchStatus.AWAITING_DECISION -> strings.get(GlanceLabel.DECISION)
                    else -> null
                }
            val serve = live.server?.let { strings.get(GlanceLabel.SERVE, player(it, strings)) }
            val lines = listOfNotNull(sets, games, points, serve, status)
            val description = (listOf(role) + lines + strings.get(GlanceLabel.OPEN)).joinToString(". ")
            // SHORT_TEXT: maximal sieben Zeichen; lange Rally-Stände fallen auf Games/Sätze zurück.
            val short = listOfNotNull(live.points, live.games, live.sets).firstOrNull { it.length <= 7 } ?: "–"
            return GlanceCopy(role, lines, GlanceAction.OPEN, description, short, lines.take(3).joinToString(" · "))
        }
        val last = state.last
        val title = strings.get(if (last == null) GlanceLabel.NEW else GlanceLabel.REPEAT)
        val lines = last?.let { setup(it, strings) }.orEmpty()
        return GlanceCopy(
            title,
            lines,
            if (last == null) GlanceAction.NEW else GlanceAction.REPEAT,
            (listOf(title) + lines).joinToString(". "),
            strings.get(GlanceLabel.START),
            strings.get(GlanceLabel.NEW),
        )
    }

    private fun setup(
        setup: GlanceSetup,
        strings: GlanceStrings,
    ): List<String> {
        val format = setup.format
        val sport =
            strings.get(
                when (format.sport) {
                    Sport.TENNIS -> GlanceLabel.TENNIS
                    Sport.PADEL -> GlanceLabel.PADEL
                    Sport.PICKLEBALL -> GlanceLabel.PICKLEBALL
                    Sport.BADMINTON -> GlanceLabel.BADMINTON
                    Sport.TABLE_TENNIS -> GlanceLabel.TABLE_TENNIS
                },
            )
        val discipline = strings.get(if (format.discipline == Discipline.DOUBLES) GlanceLabel.DOUBLES else GlanceLabel.SINGLES)
        val mode =
            if (format.match.mode == MatchMode.OPEN) {
                strings.get(GlanceLabel.OPEN_FORMAT)
            } else {
                strings.get(GlanceLabel.BEST_OF, requireNotNull(format.match.bestOf))
            }
        val target =
            format.set?.gamesToWin?.let { strings.get(GlanceLabel.GAMES_TO, it) }
                ?: (format.rally?.pointsToWin ?: format.pickleball?.pointsToWin ?: format.matchTiebreak?.pointsToWin)
                    ?.let { strings.get(GlanceLabel.POINTS_TO, it) }
        val teams =
            Side.entries
                .map { side ->
                    setup.players.filter { it.side == side }.joinToString("/") { player(it, strings) }
                }.joinToString(" – ")
        return listOf("$sport · $discipline", listOfNotNull(mode, target).joinToString(" · "), teams)
    }

    private fun player(
        player: GlancePlayer,
        strings: GlanceStrings,
    ): String =
        when {
            player.isSelf -> strings.get(GlanceLabel.YOU)
            player.name != null -> player.name
            player.side == Side.A -> strings.get(GlanceLabel.PARTNER)
            player.index == 0 -> strings.get(GlanceLabel.OPPONENT_1)
            else -> strings.get(GlanceLabel.OPPONENT_2)
        }
}
