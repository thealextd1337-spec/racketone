package eu.advantly.core.scoring

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.End
import eu.advantly.core.model.EventId
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Kanonischer Match-Zustand (02 §8). Genau diese Form gilt für Testvektoren und den Garmin-Abgleich:
 * alle Schlüssel sind immer vorhanden, `null` wird explizit geschrieben (keine Defaults im Konstruktor).
 */
@Serializable
data class MatchState(
    val status: MatchStatus,
    val pending: PendingQuestion?,
    val result: MatchResult?,
    val setsWon: SidePair<Int>,
    val sets: List<SetRecord>,
    val current: CurrentSet?,
    val serve: ServeInfo?,
    val scoreCall: String?,
    val positions: SidePair<Positions>?,
    val ends: SidePair<End>,
    val statusFlags: List<StatusFlag>,
    val effects: List<Effect>,
    val ignoredEvents: List<EventId>,
    val continuation: Continuation?,
    val scorerDeviceId: DeviceId?,
    val rejectedByRole: List<EventId>,
    val serverChoice: ServerChoice?,
)

/** Wertepaar je Side, JSON `{"A": …, "B": …}`. */
@Serializable
data class SidePair<T>(
    @SerialName("A") val a: T,
    @SerialName("B") val b: T,
) {
    /** Wert für [side]. */
    operator fun get(side: Side): T = if (side == Side.A) a else b

    /** Kopie mit neuem Wert für [side]. */
    fun with(
        side: Side,
        value: T,
    ): SidePair<T> = if (side == Side.A) copy(a = value) else copy(b = value)

    /** Wendet [f] auf beide Werte an. */
    fun <R> map(f: (Side, T) -> R): SidePair<R> = SidePair(f(Side.A, a), f(Side.B, b))

    companion object {
        /** Paar aus einer Funktion je Side. */
        fun <T> of(f: (Side) -> T): SidePair<T> = SidePair(f(Side.A), f(Side.B))
    }
}

/** Status des Matches (02 §8). */
@Serializable
enum class MatchStatus {
    IN_PROGRESS,
    PAUSED,
    AWAITING_DECISION,
    FINISHED,
}

/** Offene Frage an die Nutzer. */
@Serializable
enum class PendingQuestion {
    /** OPEN-Modus: „Noch ein Satz?“ */
    ASK_ANOTHER_SET,
}

/** Grund des Matchendes. */
@Serializable
enum class ResultReason {
    COMPLETED,
    DRAW,
    RETIRED,
    WALKOVER,
    ABANDONED,
}

/** Ergebnis; `winner = null` bei `DRAW` und `ABANDONED`. */
@Serializable
data class MatchResult(
    val winner: Side?,
    val reason: ResultReason,
)

/** Art eines Satzes in der Engine (02 §0, §8). */
@Serializable
enum class SetKind {
    REGULAR,
    MATCH_TIEBREAK,

    /** Pickleball-„Game“ (in der Engine ein Satz). */
    PB_GAME,

    /** Badminton-/Tischtennis-Satz. */
    RALLY_GAME,
}

/** Art des laufenden Spiels (02 §8). */
@Serializable
enum class GameKind {
    STANDARD,
    TIEBREAK,
    MATCH_TIEBREAK,

    /** Pickleball, Badminton, Tischtennis. */
    RALLY_GAME,
}

/**
 * Abgeschlossener Satz (02 §8 `sets[]`).
 *
 * Tennis/Padel `REGULAR`: [games] = Endstand, [tiebreak] = Tiebreak-Punkte oder `null`. `MATCH_TIEBREAK`:
 * `games = {Sieger: 1, Verlierer: 0}`, [tiebreak] = Punkte. Pickleball/Badminton/Tischtennis: nur [points].
 */
@Serializable
data class SetRecord(
    val kind: SetKind,
    val games: SidePair<Int>?,
    val tiebreak: SidePair<Int>?,
    val points: SidePair<Int>?,
    val winner: Side,
)

/** Hinweis zur Punktanzeige (02 §8 `current.callout`). */
@Serializable
enum class Callout {
    DEUCE,
    ADVANTAGE_SERVER,
    ADVANTAGE_RECEIVER,
    DECIDING_POINT,
    STAR_POINT,
}

/**
 * Laufender Satz bzw. laufendes Spiel (02 §8 `current.*`). Bei vorzeitigem Matchende eingefroren (letzter Stand).
 *
 * @property games Spiele im Satz (Tennis/Padel), sonst `null`
 * @property points Rohpunkte des laufenden Spiels/Tiebreaks/Rally-Satzes
 * @property display Anzeige je Side (02 §8.1), z. B. `"AD"`, `"40"`, `"7"`
 * @property deuceCount Standardspiel: Nummer des erreichten Einstands, Tiebreak 0, sonst `null`
 * @property decidingPoint der nächste Punkt entscheidet das Spiel bzw. den Satz; Pickleball `null`
 */
@Serializable
data class CurrentSet(
    val setIndex: Int,
    val setKind: SetKind,
    val gameKind: GameKind,
    val games: SidePair<Int>?,
    val points: SidePair<Int>,
    val display: SidePair<String>,
    val callout: Callout?,
    val deuceCount: Int?,
    val decidingPoint: Boolean?,
)

/** Aufschlagseite bzw. -feld (02 §6.5.4); `RECEIVER_CHOICE` beim Entscheidungspunkt. */
@Serializable
enum class ServeCourt {
    RIGHT,
    LEFT,
    RECEIVER_CHOICE,
}

/**
 * Aufschlag des nächsten Ballwechsels (02 §8 `serve`).
 *
 * @property court `null` bei Tischtennis
 * @property serverNumber Pickleball-Doppel mit Side-Out: 1 oder 2, sonst `null`
 * @property receiver `null` bei Entscheidungspunkt (Rückschläger wählt)
 * @property servesRemaining Tischtennis: verbleibende Aufschläge der Folge inkl. des nächsten, sonst `null`
 */
@Serializable
data class ServeInfo(
    val side: Side,
    val player: PlayerId,
    val court: ServeCourt?,
    val serverNumber: Int?,
    val receiver: PlayerId?,
    val servesRemaining: Int?,
)

/** Felder einer Side (Pickleball, Badminton), JSON `{"RIGHT": …, "LEFT": …}`; Einzel: `LEFT = null`. */
@Serializable
data class Positions(
    @SerialName("RIGHT") val right: PlayerId,
    @SerialName("LEFT") val left: PlayerId?,
)

/** Art eines Status-Flags (02 §8.2). */
@Serializable
enum class FlagLevel {
    GAME_POINT,
    SET_POINT,
    MATCH_POINT,
}

/**
 * Spiel-/Satz-/Matchball einer Side (02 §8.2), per Was-wäre-wenn ermittelt.
 *
 * @property count Punktvorsprung bzw. 1 beim Entscheidungspunkt („3 Satzbälle“)
 * @property breakPoint Tennis/Padel-Standardspiel und [side] schlägt nicht auf
 */
@Serializable
data class StatusFlag(
    val side: Side,
    val level: FlagLevel,
    val count: Int,
    val breakPoint: Boolean,
)

/** Übergänge durch das letzte Event (02 §8.3). Die Deklarationsreihenfolge ist die kanonische Reihenfolge. */
@Serializable
enum class Effect {
    RESUMED,
    POINT,
    SECOND_SERVER,
    SIDE_OUT,
    GAME_WON,
    SET_WON,
    MATCH_WON,
    MATCH_DRAWN,
    MATCH_ENDED,
    CHANGE_ENDS,
    RECEIVE_ORDER_SWAPPED,
    TIEBREAK_STARTED,
    SET_STARTED,
    ASK_ANOTHER_SET,
    PAUSED,
    UNDONE,
    IGNORED,

    /** `ScorerChanged`: ein anderes Gerät zählt ab jetzt (02 §7.5). */
    SCORER_CHANGED,
}

/**
 * Offene Aufschläger-Wahl im Doppel (Tennis/Padel, 02 §6.5.5, §8 `serverChoice`). Blockiert das Zählen nicht: Wird
 * ohne Wahl weitergezählt, gilt die bisherige Reihenfolge.
 *
 * @property game 1 = vor dem ersten Ballwechsel des Satzes (Wahl aus allen vier Spielern), 2 = vor dem ersten
 *   Aufschlag des anderen Teams (Wahl aus dessen zwei Spielern)
 * @property side Side, die nach aktuellem Stand aufschlägt (bei `game = 1` der Default; eine Wahl aus dem anderen
 *   Team wechselt die eröffnende Side)
 * @property players wählbare Spieler (bei `game = 1` erst die Spieler von [side], dann die des anderen Teams)
 * @property chosen bereits in diesem Fenster gewählter Spieler, sonst `null` („Wahl erwartet“)
 */
@Serializable
data class ServerChoice(
    val game: Int,
    val side: Side,
    val players: List<PlayerId>,
    val chosen: PlayerId?,
)

/** Wer den nächsten Satz eröffnen würde (02 §5.2 `state.continuation`). */
@Serializable
data class Continuation(
    val side: Side,
    val player: PlayerId,
)
