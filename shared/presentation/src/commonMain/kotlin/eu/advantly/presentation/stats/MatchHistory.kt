package eu.advantly.presentation.stats

import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.presentation.match.LocalDay
import eu.advantly.presentation.match.MatchSetup
import eu.advantly.presentation.match.MatchSummary
import eu.advantly.presentation.match.isRated

enum class HistoryRole { OPPONENT, PARTNER }

enum class HistoryGrouping { WEEK, MONTH }

enum class HistoryOutcome { WIN, LOSS, DRAW, ABANDONED }

fun MatchSummary.outcome(): HistoryOutcome =
    when {
        !isRated -> HistoryOutcome.ABANDONED
        result.winner == null -> HistoryOutcome.DRAW
        result.winner == Side.A -> HistoryOutcome.WIN
        else -> HistoryOutcome.LOSS
    }

data class HistoryPerson(
    val player: Player,
    val role: HistoryRole,
)

/** Aufgelöste Personen-IDs statt Platz-IDs; Platzhalter und eigene Aliase sind keine H2H-Personen. */
fun historyPeople(
    setup: MatchSetup,
    selfIds: Set<PlayerId>,
): List<HistoryPerson> =
    Side.entries.flatMap { side ->
        setup.lineup.players(side).mapNotNull { slot ->
            val player = setup.assignedPlayers[slot] ?: setup.player(slot) ?: return@mapNotNull null
            if (player.isPlaceholder || player.id in selfIds || slot in selfIds) return@mapNotNull null
            HistoryPerson(player, if (side == Side.A) HistoryRole.PARTNER else HistoryRole.OPPONENT)
        }
    }

data class HistoryFilter(
    val sport: Sport? = null,
    val person: PlayerId? = null,
    val role: HistoryRole? = null,
    val query: String = "",
)

/** Filter und Sortierung verwenden nur Zusammenfassungen, niemals das Event-Log. */
fun filterHistory(
    matches: List<MatchSummary>,
    filter: HistoryFilter,
    selfIds: Set<PlayerId>,
): List<MatchSummary> =
    matches
        .filter { match ->
            val people = historyPeople(match.setup, selfIds)
            val matchesSport = filter.sport == null || match.setup.sport == filter.sport
            val matchesPerson =
                (filter.person == null && filter.role == null) ||
                    people.any {
                        (filter.person == null || it.player.id == filter.person) && (filter.role == null || it.role == filter.role)
                    }
            val matchesName =
                filter.query.isBlank() ||
                    (match.setup.lineup.a + match.setup.lineup.b).any { slot ->
                        val player = match.setup.player(slot)
                        player != null && !player.isPlaceholder && player.displayName.contains(filter.query.trim(), ignoreCase = true)
                    }
            matchesSport && matchesPerson && matchesName
        }.sortedWith(compareByDescending<MatchSummary> { it.finishedAtMs }.thenByDescending { it.matchId.value })

data class HeadToHead(
    val wins: Int,
    val losses: Int,
    val draws: Int,
    val recent: List<MatchSummary>,
) {
    val total: Int get() = wins + losses + draws
}

/** Bilanz aus Gerätesicht, gegen einen Gegner oder mit einem Partner; nur gewertete lokale Matches. */
fun headToHead(
    matches: List<MatchSummary>,
    person: PlayerId,
    role: HistoryRole,
    selfIds: Set<PlayerId>,
): HeadToHead {
    val rated = filterHistory(matches, HistoryFilter(person = person, role = role), selfIds).filter { it.isRated }
    return HeadToHead(
        wins = rated.count { it.outcome() == HistoryOutcome.WIN },
        losses = rated.count { it.outcome() == HistoryOutcome.LOSS },
        draws = rated.count { it.outcome() == HistoryOutcome.DRAW },
        recent = rated.take(5),
    )
}

data class HistoryGroup(
    val start: LocalDay,
    val matches: List<MatchSummary>,
)

/** Lokales Kalenderdatum wird injiziert; ISO-Wochen beginnen montags, auch über Jahresgrenzen. */
fun groupHistory(
    matches: List<MatchSummary>,
    grouping: HistoryGrouping,
    localDay: (Long) -> LocalDay,
): List<HistoryGroup> =
    matches
        .sortedByDescending { it.finishedAtMs }
        .groupBy {
            val date = localDay(it.finishedAtMs)
            if (grouping == HistoryGrouping.MONTH) LocalDay(date.year, date.month, 1) else weekStart(date)
        }.map { (start, entries) -> HistoryGroup(start, entries) }

private fun daysInMonth(
    year: Int,
    month: Int,
): Int =
    when (month) {
        2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

private fun weekStart(date: LocalDay): LocalDay {
    val y = date.year - 1
    val ordinal = 365L * y + y / 4 - y / 100 + y / 400 + (1 until date.month).sumOf { daysInMonth(date.year, it) } + date.day
    var start = date
    repeat(((ordinal - 1) % 7).toInt()) {
        start =
            when {
                start.day > 1 -> start.copy(day = start.day - 1)
                start.month > 1 -> LocalDay(start.year, start.month - 1, daysInMonth(start.year, start.month - 1))
                else -> LocalDay(start.year - 1, 12, 31)
            }
    }
    return start
}
