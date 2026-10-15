package eu.advantly.data

import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId
import eu.advantly.core.scoring.PlayerAssignments

/** Aus dem unveränderten Log abgeleitete Zuordnung. Die Engine behält ihre stabilen Platz-IDs. */
fun LocalData.assignments(matchId: MatchId): Map<PlayerId, Player> =
    PlayerAssignments.resolve(events.scoreEvents(matchId), players.placeholderIds()).mapValues { (_, person) ->
        players.get(person.id)?.toPlayer() ?: person
    }

/** Besetzung für neue Matches und „zuletzt gespielt“, einschließlich nachträglicher Zuweisung. */
fun LocalData.resolvedLineup(record: MatchRecord): Lineup {
    val assigned = assignments(record.id)

    fun person(id: PlayerId) = assigned[id]?.id ?: id
    return record.lineup.copy(
        a = record.lineup.a.map(::person),
        b = record.lineup.b.map(::person),
        firstServer = person(record.lineup.firstServer),
        receiveRight = record.lineup.receiveRight?.let { it.copy(a = it.a?.let(::person), b = it.b?.let(::person)) },
    )
}

/** Kleines, reproduzierbar sortiertes Verzeichnis aus tatsächlich gespielten Besetzungen. */
fun LocalData.recentPeople(limit: Int = 24): List<LocalPlayer> =
    matches
        .history(limit = 100)
        .flatMap { resolvedLineup(it).let { lineup -> lineup.a + lineup.b } }
        .distinct()
        .filterNot { isSelf(it) || isPlaceholder(it) }
        .mapNotNull(players::get)
        .take(limit)
