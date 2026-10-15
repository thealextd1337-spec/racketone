package eu.advantly.core.scoring

import eu.advantly.core.model.EndChange
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchFormat
import eu.advantly.core.model.MatchMode
import eu.advantly.core.model.MatchTiebreakFormat
import eu.advantly.core.model.MidGameEndChange
import eu.advantly.core.model.NextGameFirstServer
import eu.advantly.core.model.RegularSet
import eu.advantly.core.model.ServeRotation
import eu.advantly.core.model.SetFormat
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.model.TiebreakFormat

/**
 * Ein Verstoß gegen das Format.
 *
 * @property rule Nummer der semantischen Validierung aus 02 §3.1 (1–9) oder 0 für Schema-Regeln aus §3.2
 * @property path JSON-Pfad, z. B. `rally.cap`
 */
data class FormatViolation(
    val rule: Int,
    val path: String,
    val message: String,
)

/** Ergebnis der Formatprüfung: [errors] machen das Format ungültig, [warnings] sind nur Hinweise für die UI. */
data class FormatValidation(
    val errors: List<FormatViolation>,
    val warnings: List<FormatViolation>,
) {
    /** Format ist gültig. */
    val isValid: Boolean get() = errors.isEmpty()
}

/**
 * Prüft ein [MatchFormat] gegen Schema (02 §3.2) und semantische Validierung (02 §3.1, Regeln 1–9).
 * Ein explizites `null` für sportfremde Felder lehnt bereits die Deserialisierung ab (`MatchFormatSerializer`).
 */
object MatchFormatValidator {
    /** Liefert alle Fehler und Warnungen; leere [FormatValidation.errors] = gültig. */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun validate(format: MatchFormat): FormatValidation {
        val errors = mutableListOf<FormatViolation>()
        val warnings = mutableListOf<FormatViolation>()

        fun err(
            rule: Int,
            path: String,
            message: String,
        ) {
            errors += FormatViolation(rule, path, message)
        }

        if (format.formatVersion != MatchFormat.FORMAT_VERSION) err(0, "formatVersion", "formatVersion muss 1 sein")
        val sport = format.sport

        // --- match ---
        if (format.match.mode == MatchMode.BEST_OF) {
            when (val bestOf = format.match.bestOf) {
                null -> err(0, "match.bestOf", "BEST_OF verlangt bestOf")
                !in listOf(1, 3, 5, 7) -> err(0, "match.bestOf", "bestOf muss 1, 3, 5 oder 7 sein")
                7 -> if (sport != Sport.TABLE_TENNIS) err(9, "match.bestOf", "bestOf = 7 nur bei TABLE_TENNIS")
                else -> Unit
            }
        }

        // --- Regel 1: sportgebundene Felder ---
        fun mustBeAbsent(
            path: String,
            value: Any?,
        ) {
            if (value != null) err(1, path, "'$path' ist bei $sport sportfremd und muss fehlen")
        }

        fun mustBePresent(
            path: String,
            value: Any?,
        ) {
            if (value == null) err(1, path, "'$path' ist bei $sport Pflicht")
        }
        when {
            sport.isTennisLike -> {
                mustBePresent("game", format.game)
                mustBePresent("set", format.set)
                mustBeAbsent("pickleball", format.pickleball)
                mustBeAbsent("rally", format.rally)
            }

            sport == Sport.PICKLEBALL -> {
                mustBePresent("pickleball", format.pickleball)
                listOf("game" to format.game, "set" to format.set, "decidingSet" to format.decidingSet)
                    .plus(listOf("matchTiebreak" to format.matchTiebreak, "rally" to format.rally))
                    .forEach { (p, v) -> mustBeAbsent(p, v) }
            }

            else -> {
                mustBePresent("rally", format.rally)
                listOf("game" to format.game, "set" to format.set, "decidingSet" to format.decidingSet)
                    .plus(listOf("matchTiebreak" to format.matchTiebreak, "pickleball" to format.pickleball))
                    .forEach { (p, v) -> mustBeAbsent(p, v) }
            }
        }

        // --- Tennis/Padel ---
        format.game?.maxAdvantages?.let { if (it < 1) err(0, "game.maxAdvantages", "maxAdvantages ≥ 1") }
        format.set?.let { checkRegularSet("set", it, errors, warnings) }
        format.decidingSet?.let { ds ->
            if (format.match.mode == MatchMode.OPEN) {
                warnings += FormatViolation(3, "decidingSet", "Im OPEN-Modus wird decidingSet ignoriert (soll null sein)")
            }
            checkSetFormat("decidingSet", ds, errors, warnings)
        }
        format.matchTiebreak?.let { checkTiebreak("matchTiebreak", it.pointsToWin, it.winBy, it.endChange, errors, warnings) }

        // --- Pickleball ---
        format.pickleball?.let { pb ->
            if (pb.pointsToWin < 1) err(0, "pickleball.pointsToWin", "pointsToWin ≥ 1")
            if (pb.winBy !in 1..2) err(0, "pickleball.winBy", "winBy muss 1 oder 2 sein")
            pb.pointCap?.let { if (it <= pb.pointsToWin) err(0, "pickleball.pointCap", "pointCap muss > pointsToWin sein") }
            pb.midGameEndChangeAt?.let { if (it < 1) err(0, "pickleball.midGameEndChangeAt", "midGameEndChangeAt ≥ 1") }
        }

        // --- Rally (Badminton/Tischtennis), Regeln 5–8 ---
        format.rally?.let { r ->
            if (r.pointsToWin < 1) err(0, "rally.pointsToWin", "pointsToWin ≥ 1")
            if (r.winBy !in 1..2) err(0, "rally.winBy", "winBy muss 1 oder 2 sein")
            r.cap?.let { if (it <= r.pointsToWin) err(5, "rally.cap", "cap muss > pointsToWin sein") }
            r.midGameEndChangeAt?.let { if (it < 1) err(0, "rally.midGameEndChangeAt", "midGameEndChangeAt ≥ 1") }
            r.intervalAt?.let { if (it < 1) err(0, "rally.intervalAt", "intervalAt ≥ 1") }
            when (r.serveRotation) {
                ServeRotation.EVERY_N_POINTS -> {
                    val every = r.serveEvery
                    val atDeuce = r.serveEveryAtDeuce
                    if (every == null || every < 1) err(6, "rally.serveEvery", "EVERY_N_POINTS verlangt serveEvery ≥ 1")
                    if (atDeuce == null || atDeuce < 1) err(6, "rally.serveEveryAtDeuce", "EVERY_N_POINTS verlangt serveEveryAtDeuce ≥ 1")
                    if (r.winBy != 2) err(6, "rally.winBy", "EVERY_N_POINTS verlangt winBy = 2")
                    if (r.cap != null) err(6, "rally.cap", "EVERY_N_POINTS verlangt cap = null")
                    if (every != null && every >= 1 && (2 * (r.pointsToWin - 1)) % every != 0) {
                        err(6, "rally.serveEvery", "2·(pointsToWin − 1) muss durch serveEvery teilbar sein")
                    }
                }

                ServeRotation.WINNER_SERVES -> {
                    if (r.serveEvery != null) err(6, "rally.serveEvery", "WINNER_SERVES verlangt serveEvery = null")
                    if (r.serveEveryAtDeuce != null) err(6, "rally.serveEveryAtDeuce", "WINNER_SERVES verlangt serveEveryAtDeuce = null")
                }
            }
            when (sport) {
                Sport.BADMINTON -> {
                    if (r.serveRotation != ServeRotation.WINNER_SERVES) err(7, "rally.serveRotation", "Badminton: WINNER_SERVES")
                    if (r.nextGameFirstServer != NextGameFirstServer.GAME_WINNER) {
                        err(7, "rally.nextGameFirstServer", "Badminton: GAME_WINNER")
                    }
                    if (r.midGameEndChange != MidGameEndChange.DECIDING_GAME_ONLY) {
                        err(7, "rally.midGameEndChange", "Badminton: DECIDING_GAME_ONLY")
                    }
                }

                Sport.TABLE_TENNIS -> {
                    if (r.serveRotation != ServeRotation.EVERY_N_POINTS) err(7, "rally.serveRotation", "Tischtennis: EVERY_N_POINTS")
                    if (r.nextGameFirstServer != NextGameFirstServer.PREVIOUS_FIRST_RECEIVER) {
                        err(7, "rally.nextGameFirstServer", "Tischtennis: PREVIOUS_FIRST_RECEIVER")
                    }
                    if (r.midGameEndChange != MidGameEndChange.DECIDING_GAME_ONLY) {
                        err(7, "rally.midGameEndChange", "Tischtennis: DECIDING_GAME_ONLY")
                    }
                }

                else -> {
                    // Für andere Sportarten gelten keine zusätzlichen Rally-Bindungen.
                }
            }
            if (r.receiverSwapAtMidGameEndChange && sport != Sport.TABLE_TENNIS) {
                err(8, "rally.receiverSwapAtMidGameEndChange", "receiverSwapAtMidGameEndChange = true nur bei TABLE_TENNIS")
            }
        }
        return FormatValidation(errors, warnings)
    }

    private fun checkSetFormat(
        path: String,
        set: SetFormat,
        errors: MutableList<FormatViolation>,
        warnings: MutableList<FormatViolation>,
    ) = when (set) {
        is RegularSet -> checkRegularSet(path, set, errors, warnings)
        is MatchTiebreakFormat -> checkTiebreak(path, set.pointsToWin, set.winBy, set.endChange, errors, warnings)
    }

    private fun checkRegularSet(
        path: String,
        set: RegularSet,
        errors: MutableList<FormatViolation>,
        warnings: MutableList<FormatViolation>,
    ) {
        if (set.gamesToWin !in 1..12) errors += FormatViolation(0, "$path.gamesToWin", "gamesToWin muss 1…12 sein")
        val at = set.tiebreakAt
        if (at != null && (at < 0 || at < set.gamesToWin - 1)) {
            errors += FormatViolation(2, "$path.tiebreakAt", "tiebreakAt muss null oder ≥ gamesToWin − 1 sein")
        }
        val tb: TiebreakFormat = set.tiebreak
        checkTiebreak("$path.tiebreak", tb.pointsToWin, tb.winBy, tb.endChange, errors, warnings)
    }

    private fun checkTiebreak(
        path: String,
        pointsToWin: Int,
        winBy: Int,
        endChange: EndChange,
        errors: MutableList<FormatViolation>,
        warnings: MutableList<FormatViolation>,
    ) {
        if (pointsToWin < 1) errors += FormatViolation(0, "$path.pointsToWin", "pointsToWin ≥ 1")
        if (winBy !in 1..2) errors += FormatViolation(0, "$path.winBy", "winBy muss 1 oder 2 sein")
        if (endChange.first < 1) errors += FormatViolation(0, "$path.endChange.first", "endChange.first ≥ 1")
        endChange.every?.let { if (it < 1) errors += FormatViolation(0, "$path.endChange.every", "endChange.every ≥ 1 oder null") }
        if (winBy == 1 && pointsToWin > 7) {
            warnings += FormatViolation(4, "$path.winBy", "winBy = 1 nur für den Short-Set-Tiebreak (pointsToWin ≤ 7) üblich")
        }
    }
}

/** Prüft ein [Lineup] gegen das Format (02 §3.3). */
object LineupValidator {
    /** Liste der Fehlertexte; leer = gültig. */
    fun validate(
        format: MatchFormat,
        lineup: Lineup,
    ): List<String> {
        val errors = mutableListOf<String>()
        val size = format.discipline.playersPerSide
        for (side in Side.entries) {
            if (lineup.players(side).size != size) errors += "Side $side braucht $size Spieler (${format.discipline})"
        }
        val all = lineup.a + lineup.b
        if (all.toSet().size != all.size) errors += "Spieler-IDs müssen eindeutig sein"
        if (lineup.sideOf(lineup.firstServer) == null) errors += "firstServer gehört zu keiner Side"
        lineup.receiveRight?.let { rr ->
            for (side in Side.entries) {
                val p = rr[side]
                if (p != null && p !in lineup.players(side)) errors += "receiveRight.$side gehört nicht zu Side $side"
            }
        }
        return errors
    }
}
