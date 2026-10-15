package eu.advantly.presentation.wizard

import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side

/** Adresse eines Spielerplatzes; ungültige Adressen werden bei Aktionen ignoriert. */
data class PlayerPlace(
    val side: Side,
    val index: Int,
)

/** Auswahl und Platzwechsel; der Besitzer bleibt auf seiner Seite und kann nicht entfernt werden. */
object PlayerSelection {
    fun suggestions(
        created: List<Player>,
        recent: List<Player>,
        input: WizardInput,
        selfId: PlayerId,
    ): List<Player> {
        val assigned = input.players.map { it.id }.toSet() + selfId
        return (created + recent).distinctBy { it.id }.filterNot { it.id in assigned }
    }

    fun places(input: WizardInput): List<PlayerPlace> =
        Side.entries.flatMap { side -> input.team(side).indices.map { PlayerPlace(side, it) } }

    fun assign(
        input: WizardInput,
        place: PlayerPlace,
        player: Player,
        selfId: PlayerId,
    ): WizardInput {
        if (place !in places(input) || input.team(place.side)[place.index] != null) return input
        if (player.id == selfId || input.players.any { it.id == player.id }) return input
        return input.replacing(place, player)
    }

    fun exchange(
        input: WizardInput,
        from: PlayerPlace,
        to: PlayerPlace,
        selfId: PlayerId,
    ): WizardInput {
        if (from == to || from !in places(input) || to !in places(input)) return input
        val source = input.team(from.side)[from.index] ?: return input
        val target = input.team(to.side)[to.index]
        if ((source.id == selfId && to.side != Side.A) || (target?.id == selfId && from.side != Side.A)) return input
        return input.replacing(from, target).replacing(to, source).copy(firstServer = input.firstServer)
    }

    fun remove(
        input: WizardInput,
        place: PlayerPlace,
        selfId: PlayerId,
    ): WizardInput {
        if (place !in places(input) || input.team(place.side)[place.index]?.id == selfId) return input
        return input.replacing(place, null)
    }

    private fun WizardInput.replacing(
        place: PlayerPlace,
        player: Player?,
    ): WizardInput {
        val team = team(place.side).mapIndexed { index, old -> if (index == place.index) player else old }
        val next = if (place.side == Side.A) copy(teamA = team) else copy(teamB = team)
        return next.copy(firstServer = next.firstServer?.takeIf { id -> next.players.any { it.id == id } })
    }
}
