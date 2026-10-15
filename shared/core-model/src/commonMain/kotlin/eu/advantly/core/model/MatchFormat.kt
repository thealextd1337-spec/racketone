package eu.advantly.core.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * Zählformat eines Matches (02 §3). Teil von `MatchCreated` und während des Matches unveränderlich.
 *
 * JSON-Form exakt wie das Schema in 02 §3.2 (Feldnamen, Enums als Strings). **Sportfremde Felder werden weggelassen,
 * nicht auf `null` gesetzt** (Validierung 1): Der [MatchFormatSerializer] schreibt `game`/`set`/`decidingSet`/
 * `matchTiebreak` nur bei Tennis/Padel, `pickleball` nur bei Pickleball und `rally` nur bei Badminton/Tischtennis.
 * Ein explizites `null` für ein sportfremdes Feld (oder für `game`/`set`/`pickleball`/`rally` überhaupt) lehnt er beim
 * Einlesen mit einer `SerializationException` ab, weil das Schema es verbietet. Die semantischen Regeln prüft
 * `MatchFormatValidator` in `core-scoring`.
 *
 * @property formatVersion Schema-Version, aktuell immer 1
 * @property presetId nur Anzeige/Statistik, nicht regelrelevant (02 §4)
 * @property set Kotlin-Name wie im JSON (`set`): regulärer Satz (Tennis/Padel)
 */
@Serializable(with = MatchFormatSerializer::class)
data class MatchFormat(
    val formatVersion: Int = FORMAT_VERSION,
    val presetId: String? = null,
    val sport: Sport,
    val discipline: Discipline,
    val match: MatchSettings,
    val game: GameFormat? = null,
    val set: RegularSet? = null,
    val decidingSet: SetFormat? = null,
    val matchTiebreak: MatchTiebreakFormat? = null,
    val pickleball: PickleballFormat? = null,
    val rally: RallyFormat? = null,
) {
    /** Gewinnsätze bei `BEST_OF` = (bestOf + 1) / 2, sonst `null`. */
    val setsToWin: Int?
        get() = if (match.mode == MatchMode.BEST_OF) match.bestOf?.let { (it + 1) / 2 } else null

    companion object {
        /** Aktuelle Version des Formats (02 §3.1 `formatVersion`). */
        const val FORMAT_VERSION: Int = 1
    }
}

/** Modus des Matches (02 §5.2). */
@Serializable
enum class MatchMode {
    /** Feste Satzanzahl, Matchende automatisch. */
    BEST_OF,

    /** Satz für Satz mit Frage „Noch ein Satz?“. */
    OPEN,
}

/**
 * JSON-Objekt `match` (02 §3.1).
 *
 * @property bestOf 1, 3, 5 oder 7 (7 nur Tischtennis); nur bei [MatchMode.BEST_OF], sonst weggelassen
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class MatchSettings(
    val mode: MatchMode,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val bestOf: Int? = null,
) {
    companion object {
        /** Best of [n]. */
        fun bestOf(n: Int): MatchSettings = MatchSettings(MatchMode.BEST_OF, n)

        /** Offener Modus. */
        val OPEN: MatchSettings = MatchSettings(MatchMode.OPEN)
    }
}

/** Einstandsregel eines Standardspiels (02 §3.1 `game.deuceMode`). */
@Serializable
enum class DeuceMode {
    /** Vorteil, unbegrenzt. */
    ADVANTAGE,

    /** No-Ad (Tennis) bzw. Golden Point (Padel): bei 40:40 entscheidet der nächste Punkt. */
    DECIDING_POINT,

    /** FIP Star Point: höchstens [GameFormat.maxAdvantages] Vorteile, dann Entscheidungspunkt. */
    STAR_POINT,
}

/**
 * JSON-Objekt `game` (Tennis/Padel).
 *
 * @property maxAdvantages nur bei [DeuceMode.STAR_POINT]; `null` = Default 2 (FIP 2026). Wird nur geschrieben, wenn gesetzt.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class GameFormat(
    val deuceMode: DeuceMode,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val maxAdvantages: Int? = null,
) {
    /** Wirksame Anzahl erlaubter Vorteile beim Star Point. */
    val effectiveMaxAdvantages: Int get() = maxAdvantages ?: DEFAULT_MAX_ADVANTAGES

    companion object {
        /** FIP 2026: zwei Vorteile, dann Star Point. */
        const val DEFAULT_MAX_ADVANTAGES: Int = 2
    }
}

/**
 * Seitenwechsel im Tiebreak: nach der Punktsumme [first], danach alle [every] Punkte (`null` = nur einmal).
 * Fehlt `every` im JSON, gilt der Default 6 (02 §3.1).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class EndChange(
    val first: Int,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val every: Int? = 6,
)

/** Tiebreak-Parameter (02 §3.2 `$defs/tiebreak`). */
@Serializable
data class TiebreakFormat(
    val pointsToWin: Int,
    val winBy: Int,
    val endChange: EndChange,
) {
    companion object {
        /** TB7: bis 7, 2 Vorsprung, Wechsel alle 6 Punkte. */
        val TB7: TiebreakFormat = TiebreakFormat(7, 2, EndChange(6, 6))
    }
}

/** Art eines Satz-Objekts im JSON (`kind`). */
@Serializable
enum class SetFormatKind {
    REGULAR,
    MATCH_TIEBREAK,
}

/** Satzformat: regulärer Satz aus Spielen oder Match-Tiebreak (für `decidingSet`). */
@Serializable(with = SetFormatSerializer::class)
sealed interface SetFormat {
    /** JSON-`kind` dieses Formats. */
    val kind: SetFormatKind
}

/**
 * Regulärer Satz (02 §3.2 `$defs/regularSet`).
 *
 * @property gamesToWin 1…12
 * @property tiebreakAt Spielstand n:n für den Tiebreak; `null` = Vorteilssatz ohne Tiebreak
 * @property tiebreak Tiebreak-Format (Default TB7)
 */
@Serializable(with = RegularSetSerializer::class)
data class RegularSet(
    val gamesToWin: Int,
    val tiebreakAt: Int?,
    val tiebreak: TiebreakFormat = TiebreakFormat.TB7,
) : SetFormat {
    override val kind: SetFormatKind get() = SetFormatKind.REGULAR
}

/** Match-Tiebreak (02 §3.2 `$defs/matchTiebreak`), JSON mit `kind = MATCH_TIEBREAK`. */
@Serializable(with = MatchTiebreakSerializer::class)
data class MatchTiebreakFormat(
    val pointsToWin: Int,
    val winBy: Int,
    val endChange: EndChange,
) : SetFormat {
    override val kind: SetFormatKind get() = SetFormatKind.MATCH_TIEBREAK

    companion object {
        /** MTB10 („Champions-Tiebreak“): bis 10, 2 Vorsprung, Wechsel alle 6 Punkte. */
        val MTB10: MatchTiebreakFormat = MatchTiebreakFormat(10, 2, EndChange(6, 6))

        /** MTB7 (Tiebreak als eigener Satz, ITF App. VI 3): bis 7, 2 Vorsprung, Wechsel alle 6 Punkte. */
        val MTB7: MatchTiebreakFormat = MatchTiebreakFormat(7, 2, EndChange(6, 6))
    }
}

/** Pickleball-Zählweise (02 §3.1). */
@Serializable
enum class PickleballScoring {
    SIDE_OUT,
    RALLY,
}

/** Einmaliger Seitenwechsel in der Mitte eines Spiels bzw. Satzes (Pickleball, Badminton, Tischtennis). */
@Serializable
enum class MidGameEndChange {
    /** Nur im Entscheidungsspiel bzw. -satz eines Best-of (bei Best of 1 im einzigen Spiel). */
    DECIDING_GAME_ONLY,

    /** In jedem Spiel. */
    EVERY_GAME,

    /** Nie. */
    NEVER,
}

/** JSON-Objekt `pickleball` (02 §3.2 `$defs/pickleball`). */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PickleballFormat(
    val scoring: PickleballScoring,
    val pointsToWin: Int,
    val winBy: Int,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val pointCap: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val firstServiceTurnSingleServer: Boolean = true,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val midGameEndChange: MidGameEndChange = MidGameEndChange.DECIDING_GAME_ONLY,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val midGameEndChangeAt: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val gameWinningPointOnServeOnly: Boolean = false,
) {
    /** Punktzahl für den Seitenwechsel in der Spielmitte: gesetzt oder ⌈pointsToWin/2⌉. */
    val effectiveMidGameEndChangeAt: Int get() = midGameEndChangeAt ?: ((pointsToWin + 1) / 2)
}

/** Aufschlagwechsel im Rally-Satz (02 §3.1 `rally.serveRotation`). */
@Serializable
enum class ServeRotation {
    /** Badminton: Wer den Ballwechsel gewinnt, schlägt auf. */
    WINNER_SERVES,

    /** Tischtennis: Wechsel nach je `serveEvery` Punkten, ab Verlängerung nach `serveEveryAtDeuce`. */
    EVERY_N_POINTS,
}

/** Wer den nächsten Rally-Satz eröffnet (durch die Sportart festgelegt, Validierung 7). */
@Serializable
enum class NextGameFirstServer {
    /** Badminton (BWF 7.6): der Satzsieger. */
    GAME_WINNER,

    /** Tischtennis (ITTF 2.13.6): wer im Vorsatz zuerst annahm. */
    PREVIOUS_FIRST_RECEIVER,
}

/** JSON-Objekt `rally` für Badminton und Tischtennis (02 §3.2 `$defs/rally`). */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class RallyFormat(
    val pointsToWin: Int,
    val winBy: Int,
    val cap: Int?,
    val serveRotation: ServeRotation,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val serveEvery: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val serveEveryAtDeuce: Int? = null,
    val nextGameFirstServer: NextGameFirstServer,
    val midGameEndChange: MidGameEndChange,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val midGameEndChangeAt: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val receiverSwapAtMidGameEndChange: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val intervalAt: Int? = null,
) {
    /** Punktzahl für den Seitenwechsel in der Satzmitte: gesetzt oder ⌈pointsToWin/2⌉. */
    val effectiveMidGameEndChangeAt: Int get() = midGameEndChangeAt ?: ((pointsToWin + 1) / 2)
}
