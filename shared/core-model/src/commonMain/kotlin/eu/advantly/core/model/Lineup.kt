package eu.advantly.core.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Spieler mit Anzeigenamen. Regeln referenzieren nur [id]; der Name ist reine Anzeige. */
@Serializable
data class Player(
    val id: PlayerId,
    val displayName: String,
    val isPlaceholder: Boolean = false,
)

/** Team einer Side: ein Spieler (Einzel) oder zwei (Doppel). */
@Serializable
data class Team(
    val side: Side,
    val players: List<Player>,
)

/**
 * Besetzung und Start eines Matches (02 §3.3), JSON-Feldnamen wie in den Testvektoren.
 *
 * - [a]/[b] (`A`/`B`): Spieler-IDs je Side; die Reihenfolge ist die Aufschlag- bzw. Startaufstellung des Teams.
 * - [firstServer]: eröffnet das Match. Steht er nicht an Index 0 seines Teams, dreht die Engine das Team-Array um.
 * - [receiveRight]: Tennis/Padel-Doppel – wer auf der rechten Seite returniert. Default ist Index 0 des **hier
 *   übergebenen** Arrays, also vor dem Umdrehen des aufschlagenden Teams durch [firstServer] (Auslegung von 02 §3.3).
 * - [startEnds]: Startende von A (Default `END_1`), B steht gegenüber.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Lineup(
    @SerialName("A") val a: List<PlayerId>,
    @SerialName("B") val b: List<PlayerId>,
    val firstServer: PlayerId,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val receiveRight: ReceiveRight? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val startEnds: StartEnds? = null,
) {
    /** Spieler-IDs der Side [side] in Lineup-Reihenfolge. */
    fun players(side: Side): List<PlayerId> = if (side == Side.A) a else b

    /** Side, zu der [player] gehört, oder `null`. */
    fun sideOf(player: PlayerId): Side? =
        when (player) {
            in a -> Side.A
            in b -> Side.B
            else -> null
        }

    /** Startende von A (Default `END_1`). */
    val startEndA: End get() = startEnds?.a ?: End.END_1
}

/** Rückschläger auf der rechten Seite je Side (Tennis/Padel-Doppel), 02 §3.3. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ReceiveRight(
    @SerialName("A") @EncodeDefault(EncodeDefault.Mode.NEVER) val a: PlayerId? = null,
    @SerialName("B") @EncodeDefault(EncodeDefault.Mode.NEVER) val b: PlayerId? = null,
) {
    /** Eintrag für [side]. */
    operator fun get(side: Side): PlayerId? = if (side == Side.A) a else b
}

/** Startende von A; B steht auf dem anderen Ende (02 §3.3). */
@Serializable
data class StartEnds(
    @SerialName("A") val a: End,
)
