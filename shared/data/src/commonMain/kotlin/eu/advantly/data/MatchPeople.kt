package eu.advantly.data

import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.Placeholders
import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId

/** Materialisierte Zuordnung ohne Log-Zugriff. Die Engine behält ihre stabilen Platz-IDs. */
fun LocalData.assignments(matchId: MatchId): Map<PlayerId, Player> =
    storedAssignments(matchId).mapValues { (_, person) ->
        players.get(person.id)?.toPlayer() ?: person
    }

/** Besetzung für neue Matches und „zuletzt gespielt“, einschließlich nachträglicher Zuweisung. */
fun LocalData.resolvedLineup(record: MatchRecord): Lineup {
    val assigned = assignments(record.id)

    fun person(id: PlayerId) =
        assigned[id]?.id ?: if (!Placeholders.isPlaceholder(id) && isMatchPlaceholder(record.id, id)) {
            Placeholders.id(id.value)
        } else {
            id
        }
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
        .recentLineups(limit = 100)
        .flatMap { it.a + it.b }
        .distinct()
        .filterNot { isSelf(it) || isPlaceholder(it) }
        .mapNotNull(players::get)
        .take(limit)

/** Neue IDs für weiterhin unbesetzte Plätze; Rotation und Annahme werden konsistent mitgenommen. */
fun LocalData.freshPlaceholders(
    lineup: Lineup,
    newId: () -> PlayerId,
): Lineup {
    val replacements = (lineup.a + lineup.b).filter(::isPlaceholder).associateWith { Placeholders.id(newId().value) }

    fun person(id: PlayerId) = replacements[id] ?: id
    return lineup.copy(
        a = lineup.a.map(::person),
        b = lineup.b.map(::person),
        firstServer = person(lineup.firstServer),
        receiveRight = lineup.receiveRight?.let { it.copy(a = it.a?.let(::person), b = it.b?.let(::person)) },
    )
}
