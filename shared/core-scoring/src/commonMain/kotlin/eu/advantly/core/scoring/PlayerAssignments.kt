package eu.advantly.core.scoring

import eu.advantly.core.model.Placeholders
import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId

/** Reine Identitätsprojektion neben dem Score-Fold; letzte gültige Zuweisung in Eventordnung gewinnt. */
object PlayerAssignments {
    data class Projection(
        val slots: Set<PlayerId>,
        val people: Map<PlayerId, Player>,
    )

    fun resolve(
        events: Collection<ScoreEvent>,
        legacyPlaceholders: Set<PlayerId> = emptySet(),
    ): Map<PlayerId, Player> = project(events, legacyPlaceholders).people

    fun project(
        events: Collection<ScoreEvent>,
        legacyPlaceholders: Set<PlayerId> = emptySet(),
    ): Projection {
        val analysis = EventLog.analyze(events)
        val genesis = analysis.ordered.firstOrNull { it.payload is MatchCreated } ?: return Projection(emptySet(), emptyMap())
        if (genesis.id in analysis.rejected) return Projection(emptySet(), emptyMap())
        val created = genesis.payload as MatchCreated
        val owner = created.createdBy ?: created.lineup.a.first()
        val slots = created.lineup.a + created.lineup.b
        val legacySlots =
            legacyPlaceholders +
                analysis.ordered.mapNotNull {
                    (it.payload as? PlayerAssigned)
                        ?.takeIf { assignment ->
                            assignment.legacySlot && (assignment.assignedBy == owner || assignment.creatorAlias == owner) &&
                                assignment.slot in slots &&
                                it.matchId == genesis.matchId && it.id !in analysis.rejected && EventOrdering.compare(it, genesis) > 0
                        }?.slot
                }
        val eligible = slots.filter { Placeholders.isPlaceholder(it) || it in legacySlots }.toSet()
        val occupied = slots.filterNot { it in eligible }.toMutableSet()
        val result = linkedMapOf<PlayerId, Player>()
        for (event in analysis.ordered) {
            val assignment = event.payload as? PlayerAssigned ?: continue
            if (event.matchId != genesis.matchId || event.id in analysis.rejected || EventOrdering.compare(event, genesis) <= 0) continue
            val (slot, player, actor) = assignment
            if ((actor != owner && assignment.creatorAlias != owner) || slot !in slots) continue
            if (!Placeholders.isPlaceholder(slot) && slot !in legacySlots) continue
            if (player == null) {
                result.remove(slot)?.let { occupied.remove(it.id) }
                continue
            }
            if (player.isPlaceholder || Placeholders.isPlaceholder(player.id) || player.id in legacySlots) continue
            if (player.id.value.isBlank() || player.displayName.isBlank() ||
                (player.id in occupied && result[slot]?.id != player.id)
            ) {
                continue
            }
            result.put(slot, player)?.let { occupied.remove(it.id) }
            occupied.add(player.id)
        }
        return Projection(eligible, result)
    }
}
