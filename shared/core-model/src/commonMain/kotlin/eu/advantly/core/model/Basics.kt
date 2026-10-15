package eu.advantly.core.model

import kotlinx.serialization.Serializable

/** Sportart (02 §3.1 `sport`). */
@Serializable
enum class Sport {
    TENNIS,
    PADEL,
    PICKLEBALL,
    BADMINTON,
    TABLE_TENNIS,
    ;

    /** Tennis oder Padel: Spiele aus 0-15-30-40, Sätze aus Spielen, Tiebreaks. */
    val isTennisLike: Boolean get() = this == TENNIS || this == PADEL

    /** Badminton oder Tischtennis: Rally-Sätze über `config.rally` (02 §6.8/§6.9). */
    val isRallySport: Boolean get() = this == BADMINTON || this == TABLE_TENNIS
}

/** Einzel oder Doppel (02 §3.1 `discipline`). */
@Serializable
enum class Discipline {
    SINGLES,
    DOUBLES,
    ;

    /** Spieler je Side. */
    val playersPerSide: Int get() = if (this == SINGLES) 1 else 2
}

/** Partei eines Matches (02 §0): Einzelspieler oder Doppel-Team. */
@Serializable
enum class Side {
    A,
    B,
    ;

    /** Gegenseite. */
    val other: Side get() = if (this == A) B else A
}

/** Platzhälfte (02 §0 „Ende“). */
@Serializable
enum class End {
    END_1,
    END_2,
    ;

    /** Die jeweils andere Platzhälfte. */
    val other: End get() = if (this == END_1) END_2 else END_1
}

/** Aufschlag- bzw. Positionsfeld aus Sicht des Spielers (02 §0 „Aufschlagseite“). */
@Serializable
enum class CourtSide {
    RIGHT,
    LEFT,
}
