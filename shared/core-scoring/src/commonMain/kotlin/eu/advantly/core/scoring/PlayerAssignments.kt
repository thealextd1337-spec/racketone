package eu.advantly.core.scoring

import eu.advantly.core.model.Placeholders
import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId

/** Reine Identitätsprojektion neben dem Score-Fold; erste gültige Zuweisung je Platz gewinnt. */
object PlayerAssignments {
    fun resolve(
        events: Collection<ScoreEvent>,
        legacyPlaceholders: Set<PlayerId> = emptySet(),
    ): Map<PlayerId, Player> {
        val analysis = EventLog.analyze(events)
        val genesis = analysis.ordered.firstOrNull { it.payload is MatchCreated } ?: return emptyMap()
        if (genesis.id in analysis.rejected) return emptyMap()
        val created = genesis.payload as MatchCreated
        val owner = created.createdBy ?: created.lineup.a.first()
        val slots = created.lineup.a + created.lineup.b
        val occupied = slots.filterNot { Placeholders.isPlaceholder(it) || it in legacyPlaceholders }.toMutableSet()
        val result = linkedMapOf<PlayerId, Player>()
        for (event in analysis.ordered) {
            val assignment = event.payload as? PlayerAssigned ?: continue
            if (event.matchId != genesis.matchId || event.id in analysis.rejected || EventOrdering.compare(event, genesis) <= 0) continue
            val (slot, player, actor) = assignment
            if (actor != owner || slot !in slots || slot in result) continue
            if (!Placeholders.isPlaceholder(slot) && slot !in legacyPlaceholders) continue
            if (player.isPlaceholder || Placeholders.isPlaceholder(player.id) || player.id in legacyPlaceholders) continue
            if (player.id.value.isBlank() || player.displayName.isBlank() || !occupied.add(player.id)) continue
            result[slot] = player
        }
        return result
    }
}
