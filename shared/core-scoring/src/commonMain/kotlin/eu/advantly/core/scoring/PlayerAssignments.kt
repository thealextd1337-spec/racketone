package eu.advantly.core.scoring

import eu.advantly.core.model.EventId
import eu.advantly.core.model.Placeholders
import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId

/** Reine Identitätsprojektion neben dem Score-Fold; letzte gültige Zuweisung in Eventordnung gewinnt. */
object PlayerAssignments {
    data class Projection(
        val slots: Set<PlayerId>,
        val people: Map<PlayerId, Player>,
    )

    data class IdentityProjection(
        val slots: Set<PlayerId>,
        val peopleBySlot: Map<PlayerId, PlayerId>,
    )

    fun projectIds(
        events: Collection<ScoreEvent>,
        legacyPlaceholders: Set<PlayerId> = emptySet(),
    ): IdentityProjection = fold(events, legacyPlaceholders).identities

    fun resolve(
        events: Collection<ScoreEvent>,
        legacyPlaceholders: Set<PlayerId> = emptySet(),
    ): Map<PlayerId, Player> = project(events, legacyPlaceholders).people

    fun project(
        events: Collection<ScoreEvent>,
        legacyPlaceholders: Set<PlayerId> = emptySet(),
    ): Projection = fold(events, legacyPlaceholders).names

    /** Herkunft der aktuell wirksamen Zuweisung; dieselben Gültigkeitsregeln wie für IDs und Namen. */
    fun assignmentEventIds(
        events: Collection<ScoreEvent>,
        legacyPlaceholders: Set<PlayerId> = emptySet(),
    ): Map<PlayerId, EventId> = fold(events, legacyPlaceholders).sources

    private data class Assignments(
        val identities: IdentityProjection,
        val names: Projection,
        val sources: Map<PlayerId, EventId>,
    )

    private fun fold(
        events: Collection<ScoreEvent>,
        legacyPlaceholders: Set<PlayerId>,
    ): Assignments {
        val empty = Assignments(IdentityProjection(emptySet(), emptyMap()), Projection(emptySet(), emptyMap()), emptyMap())
        val analysis = EventLog.analyze(events)
        val genesis = analysis.ordered.firstOrNull { it.payload is MatchCreated } ?: return empty
        if (genesis.id in analysis.rejected) return empty
        val created = genesis.payload as MatchCreated
        val owner = created.createdBy ?: created.lineup.a.first()
        val slots = created.lineup.a + created.lineup.b
        val legacySlots =
            legacyPlaceholders +
                analysis.ordered.mapNotNull {
                    (it.payload as? PlayerAssigned)
                        ?.takeIf { assignment ->
                            assignment.hasValidForm() && assignment.legacySlot &&
                                (assignment.assignedBy == owner || assignment.creatorAlias == owner) &&
                                assignment.slot in slots &&
                                it.matchId == genesis.matchId && it.id !in analysis.rejected && EventOrdering.compare(it, genesis) > 0
                        }?.slot
                }
        val eligible = slots.filter { Placeholders.isPlaceholder(it) || it in legacySlots }.toSet()
        val occupied = slots.filterNot { it in eligible }.toMutableSet()
        val result = linkedMapOf<PlayerId, Player>()
        val identities = linkedMapOf<PlayerId, PlayerId>()
        val sources = linkedMapOf<PlayerId, EventId>()
        for (event in analysis.ordered) {
            val assignment = event.payload as? PlayerAssigned ?: continue
            if (event.matchId != genesis.matchId || event.id in analysis.rejected || EventOrdering.compare(event, genesis) <= 0) continue
            if (!assignment.hasValidForm()) continue
            val (slot, player, actor) = assignment
            if ((actor != owner && assignment.creatorAlias != owner) || slot !in slots) continue
            if (!Placeholders.isPlaceholder(slot) && slot !in legacySlots) continue
            val personId = if (assignment.assignmentVersion == 2) assignment.playerId else player?.id
            if (personId == null) {
                identities.remove(slot)?.let { occupied.remove(it) }
                result.remove(slot)
                sources.remove(slot)
                continue
            }
            if (player != null && (player.isPlaceholder || player.displayName.isBlank())) continue
            if (Placeholders.isPlaceholder(personId) || personId in legacySlots) continue
            if (personId.value.isBlank() ||
                (personId in occupied && identities[slot] != personId)
            ) {
                continue
            }
            identities.put(slot, personId)?.let { occupied.remove(it) }
            sources[slot] = event.id
            if (player == null) result.remove(slot) else result[slot] = player
            occupied.add(personId)
        }
        return Assignments(IdentityProjection(eligible, identities), Projection(eligible, result), sources)
    }
}
