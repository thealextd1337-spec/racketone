package eu.advantly.presentation.live

import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.scoring.Callout
import eu.advantly.core.scoring.Effect
import eu.advantly.core.scoring.FlagLevel
import eu.advantly.core.scoring.GameKind
import eu.advantly.core.scoring.MatchState
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.core.scoring.SetKind
import eu.advantly.core.scoring.SidePair
import eu.advantly.presentation.match.LiveMatchSnapshot
import eu.advantly.presentation.match.isDraw

/**
 * Haptik-Ereignisse des Live-Scores (04 §5.8). Die Plattform-Umsetzung (Vibrator-Primitives bzw.
 * UIFeedbackGenerator) liegt in `ui-phone`.
 */
enum class HapticCue {
    PointUs,
    PointThem,
    Undo,
    GameWon,
    GameLost,
    SetWon,
    SetLost,
    ChangeEnds,
    Rejected,
}

/** Anzeige eines Punktestands: Text der Engine (`"0"`, `"15"`, `"7"`) oder Vorteil (`"AD"`, lokalisiert in der UI). */
sealed interface PointLabel {
    data class Number(
        val value: String,
    ) : PointLabel

    data object Advantage : PointLabel
}

/** Hinweis-Pill (04 §4.3.1, 02 §8.2/§8.4): Art, betroffene Side, Anzahl („3 Satzbälle“) und Darstellungsstufe. */
data class ScoreHint(
    val kind: Kind,
    val side: Side? = null,
    val count: Int = 1,
    /** Zweiter, gleichzeitig nötiger Hinweis (TT-Doppel bei 5 im Entscheidungssatz: Seitenwechsel + Rückschlagtausch). */
    val also: Kind? = null,
) {
    enum class Kind(
        val level: Level,
    ) {
        MatchPoint(Level.Filled),
        SetPoint(Level.Tinted),
        BreakPoint(Level.Tinted),
        GamePoint(Level.Outline),

        /** Golden Point / No-Ad (Tennis, Padel) bzw. Entscheidungspunkt beim Deckel (Badminton). */
        DecidingPoint(Level.Tinted),
        StarPoint(Level.Tinted),

        /** Einstand (Tennis/Padel) bzw. Verlängerung (Badminton/Tischtennis). */
        Deuce(Level.Outline),
        Tiebreak(Level.Outline),
        MatchTiebreak(Level.Outline),
        ChangeEnds(Level.Outline),
        ReceiveOrderSwapped(Level.Outline),
        SideOut(Level.Outline),
        SecondServer(Level.Outline),

        /** Badminton: „Pause bis 60 s“ beim Erreichen von `intervalAt` (BWF 16.2.1). */
        Interval(Level.Outline),
        Paused(Level.Outline),
        MatchWon(Level.Filled),
        MatchLost(Level.Filled),
        MatchDrawn(Level.Filled),

        /** Abbruch ohne Wertung. */
        MatchAbandoned(Level.Filled),
    }

    /** Kontur · gefüllt-hell (Break-/Satzball) · gefüllt + Puls (Matchball). */
    enum class Level { Outline, Tinted, Filled }
}

/** Eine Spalte der Satztabelle (04 §5.6). */
data class SetColumn(
    val values: SidePair<Int>,
    /** Tiebreak-Punkte des Satzverlierers (hochgestellt, z. B. 7:6⁵). */
    val tiebreakLoserPoints: Int?,
    val winner: Side?,
    val isCurrent: Boolean,
    val isTiebreakSet: Boolean = false,
)

/** Reine Ableitungen für den Live-Screen aus dem kanonischen Zustand (02 §8). */
object LiveScoreRules {
    /**
     * Punktanzeige je Side; ohne laufenden Satz (Satzende-Frage, reguläres Matchende) die gewonnenen Sätze. Bei
     * vorzeitigem Ende bleibt der eingefrorene Stand stehen (02 §9.2: „6:4 2:1 (30:0) Aufg.“).
     */
    fun pointLabel(
        state: MatchState,
        side: Side,
    ): PointLabel {
        val current = state.current ?: return PointLabel.Number(state.setsWon[side].toString())
        val text = current.display[side]
        return if (text == "AD") PointLabel.Advantage else PointLabel.Number(text)
    }

    /** Satztabelle: abgeschlossene Sätze, dazu der laufende (Tennis/Padel: Games, sonst Punkte). */
    fun setColumns(state: MatchState): List<SetColumn> {
        val done =
            state.sets.map { set ->
                when (set.kind) {
                    SetKind.REGULAR -> {
                        val games = set.games ?: SidePair(0, 0)
                        SetColumn(games, set.tiebreak?.get(set.winner.other), set.winner, isCurrent = false)
                    }

                    SetKind.MATCH_TIEBREAK -> {
                        SetColumn(set.tiebreak ?: set.points ?: SidePair(0, 0), null, set.winner, isCurrent = false, isTiebreakSet = true)
                    }

                    SetKind.PB_GAME, SetKind.RALLY_GAME -> {
                        SetColumn(set.points ?: SidePair(0, 0), null, set.winner, isCurrent = false)
                    }
                }
            }
        // Bei vorzeitigem Ende ist `current` eingefroren und bleibt als letzte Spalte sichtbar (02 §9.2/§9.22)
        val current = state.current ?: return done
        val values = if (current.setKind == SetKind.MATCH_TIEBREAK) current.points else current.games ?: current.points
        return done + SetColumn(values, null, null, isCurrent = true, isTiebreakSet = current.setKind == SetKind.MATCH_TIEBREAK)
    }

    /** Hinweis nach Vorrang (02 §8.2 „Watch-Anzeige“, §8.4; Handy zeigt zusätzlich den Spielball). */
    fun hint(
        snapshot: LiveMatchSnapshot,
        sport: Sport = snapshot.setup.sport,
    ): ScoreHint? {
        val state = snapshot.state
        when (state.status) {
            MatchStatus.FINISHED -> {
                val result = state.result ?: return null
                return when {
                    result.isDraw -> ScoreHint(ScoreHint.Kind.MatchDrawn)
                    result.winner == null -> ScoreHint(ScoreHint.Kind.MatchAbandoned)
                    result.winner == Side.A -> ScoreHint(ScoreHint.Kind.MatchWon, Side.A)
                    else -> ScoreHint(ScoreHint.Kind.MatchLost, Side.B)
                }
            }

            MatchStatus.AWAITING_DECISION -> {
                return null
            }

            MatchStatus.PAUSED -> {
                return ScoreHint(ScoreHint.Kind.Paused)
            }

            MatchStatus.IN_PROGRESS -> {
                Unit
            }
        }
        val current = state.current ?: return null
        val effects = state.effects
        val swap = Effect.RECEIVE_ORDER_SWAPPED in effects
        if (Effect.CHANGE_ENDS in effects) {
            return ScoreHint(ScoreHint.Kind.ChangeEnds, also = if (swap) ScoreHint.Kind.ReceiveOrderSwapped else null)
        }
        if (swap) return ScoreHint(ScoreHint.Kind.ReceiveOrderSwapped)
        when (current.callout) {
            Callout.DECIDING_POINT -> return ScoreHint(ScoreHint.Kind.DecidingPoint)
            Callout.STAR_POINT -> return ScoreHint(ScoreHint.Kind.StarPoint)
            else -> Unit
        }
        state.statusFlags.maxWithOrNull(compareBy({ it.level.ordinal }, { it.breakPoint }))?.let { flag ->
            val kind =
                when (flag.level) {
                    FlagLevel.MATCH_POINT -> ScoreHint.Kind.MatchPoint
                    FlagLevel.SET_POINT -> ScoreHint.Kind.SetPoint
                    FlagLevel.GAME_POINT -> if (flag.breakPoint) ScoreHint.Kind.BreakPoint else ScoreHint.Kind.GamePoint
                }
            return ScoreHint(kind, flag.side, flag.count)
        }
        val interval =
            snapshot.setup.format.rally
                ?.intervalAt
        if (sport == Sport.BADMINTON && interval != null && Effect.POINT in effects) {
            val p = current.points
            if (maxOf(p.a, p.b) == interval && minOf(p.a, p.b) < interval) return ScoreHint(ScoreHint.Kind.Interval)
        }
        if (current.callout == Callout.DEUCE) return ScoreHint(ScoreHint.Kind.Deuce)
        if (Effect.SIDE_OUT in effects && sport == Sport.PICKLEBALL) return ScoreHint(ScoreHint.Kind.SideOut)
        if (Effect.SECOND_SERVER in effects) return ScoreHint(ScoreHint.Kind.SecondServer)
        return when (current.gameKind) {
            GameKind.TIEBREAK -> ScoreHint(ScoreHint.Kind.Tiebreak)
            GameKind.MATCH_TIEBREAK -> ScoreHint(ScoreHint.Kind.MatchTiebreak)
            else -> null
        }
    }

    /** Haptik nach dem stärksten Ereignis eines Punkts für [side]: Satz/Match > Seitenwechsel > Spiel > Punkt (04 §5.8). */
    fun cueFor(
        after: MatchState,
        side: Side,
    ): HapticCue {
        val effects = after.effects
        val us = side == Side.A
        return when {
            Effect.SET_WON in effects || Effect.MATCH_WON in effects -> if (us) HapticCue.SetWon else HapticCue.SetLost
            Effect.CHANGE_ENDS in effects -> HapticCue.ChangeEnds
            Effect.GAME_WON in effects -> if (us) HapticCue.GameWon else HapticCue.GameLost
            else -> if (us) HapticCue.PointUs else HapticCue.PointThem
        }
    }
}
