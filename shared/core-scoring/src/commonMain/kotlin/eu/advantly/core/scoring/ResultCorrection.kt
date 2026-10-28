package eu.advantly.core.scoring

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.EventId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchFormat
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMode
import eu.advantly.core.model.Side

/** Warum ein korrigiertes Ergebnis unplausibel ist (für eine verständliche Meldung in der UI). */
enum class CorrectionIssueKind {
    /** Kein Satz eingegeben. */
    NO_SETS,

    /** Mehr als [ResultCorrection.MAX_SETS] Sätze. */
    TOO_MANY_SETS,

    /** Satzart passt nicht zu Sportart bzw. Format (z. B. normaler Satz statt Match-Tiebreak im Entscheidungssatz). */
    SET_KIND,

    /** Stand nach den Regeln des Formats unmöglich (z. B. 8:4, 6:5, 21:20). */
    SET_SCORE,

    /** Satz endete mit Tiebreak, die Tiebreak-Punkte fehlen. */
    TIEBREAK_REQUIRED,

    /** Tiebreak-Punkte unmöglich oder ohne Tiebreak angegeben. */
    TIEBREAK_SCORE,

    /** Satz nach dem bereits entschiedenen Match (Best-of). */
    AFTER_MATCH_END,
}

/** Unplausible Eingabe; [set] = 0-basierter Satz, sonst `null`. */
data class CorrectionIssue(
    val kind: CorrectionIssueKind,
    val set: Int? = null,
)

/**
 * Prüfergebnis einer Ergebnis-Korrektur: bei [issue] `null` die kanonischen Sätze, Satzstand und abgeleitetes Ergebnis
 * (immer mit `corrected = true`).
 */
data class CorrectionCheck(
    val issue: CorrectionIssue?,
    val sets: List<SetRecord> = emptyList(),
    val setsWon: SidePair<Int> = SidePair(0, 0),
    val result: MatchResult? = null,
)

/**
 * Plausibilität und Wirkung von [ResultCorrected]. Statt eigener Tabellen je Sportart werden die Sätze mit der Engine
 * selbst nachgespielt (Verlierer und Sieger abwechselnd bis zum Stand des Verlierers, dann der Rest des Siegers). Damit
 * gelten exakt die Regeln aus [MatchFormat]: Kurzsätze, Vorteilssätze, Tiebreak bei n:n, Match-Tiebreak im
 * Entscheidungssatz, Punkte-Deckel (Badminton 30, Pickleball `pointCap`), 2 Punkte Vorsprung und Side-Out.
 *
 * Sieger und Grund (normativ):
 * - Best-of und eine Side erreicht die Gewinnsätze → Sieger, `COMPLETED`.
 * - Sonst bleiben Aufgabe, Walkover und Abbruch (`RETIRED`/`WALKOVER`/`ABANDONED`) mit ihrem Sieger bestehen.
 * - Sonst offener Modus: mehr Sätze gewinnt (`COMPLETED`), Gleichstand `DRAW`; Best-of unvollständig: `STOPPED` mit der
 *   nach Sätzen führenden Side (Gleichstand: kein Sieger, 02 §9.27).
 */
object ResultCorrection {
    /** Höchstzahl Sätze (Grenze der Feed-Projektion, `sanitize_projection`). */
    const val MAX_SETS: Int = 9

    /** Höchstwert je Stand (Grenze der Feed-Projektion). */
    private const val MAX_SCORE: Int = 99

    /** Erlaubte Satzarten der Sportart (Tennis/Padel zusätzlich `MATCH_TIEBREAK`). */
    fun setKinds(format: MatchFormat): List<SetKind> =
        when {
            format.sport.isTennisLike -> listOf(SetKind.REGULAR, SetKind.MATCH_TIEBREAK)
            format.sport == eu.advantly.core.model.Sport.PICKLEBALL -> listOf(SetKind.PB_GAME)
            else -> listOf(SetKind.RALLY_GAME)
        }

    /**
     * Prüft [sets] gegen [format] und leitet das Ergebnis relativ zum gezählten Ergebnis [recorded] ab. [lineup] wird nur
     * für das Nachspielen gebraucht (der Aufschlag beeinflusst nur bei Pickleball-Side-Out, wann ein Punkt zählt).
     */
    fun check(
        format: MatchFormat,
        lineup: Lineup,
        sets: List<CorrectedSet>,
        recorded: MatchResult,
    ): CorrectionCheck {
        if (sets.isEmpty()) return CorrectionCheck(CorrectionIssue(CorrectionIssueKind.NO_SETS))
        if (sets.size > MAX_SETS) return CorrectionCheck(CorrectionIssue(CorrectionIssueKind.TOO_MANY_SETS))
        var core = Core.initial(format, lineup)
        sets.forEachIndexed { index, set ->
            if (set.kind !in setKinds(format)) return issue(CorrectionIssueKind.SET_KIND, index)
            if (core.status == MatchStatus.FINISHED) return issue(CorrectionIssueKind.AFTER_MATCH_END, index)
            var firstIssue: CorrectionIssueKind? = null
            var played: Core? = null
            for (kind in candidates(format, core, set)) {
                val attempt = core.copy()
                if (kind != null) attempt.step(event(ContinueDecision(true, kind))) { null }
                val problem = play(attempt, set, index)
                if (problem == null) {
                    played = attempt
                    break
                }
                if (firstIssue == null) firstIssue = problem
            }
            core = played ?: return issue(firstIssue ?: CorrectionIssueKind.SET_KIND, index)
        }
        val leader = StopRule.winner(core.setsWon, null, null)
        val result =
            when {
                core.status == MatchStatus.FINISHED -> {
                    MatchResult(core.result?.winner, ResultReason.COMPLETED, corrected = true)
                }

                recorded.reason in KEPT -> {
                    recorded.copy(corrected = true)
                }

                format.match.mode == MatchMode.OPEN -> {
                    MatchResult(leader, if (leader == null) ResultReason.DRAW else ResultReason.COMPLETED, corrected = true)
                }

                else -> {
                    MatchResult(leader, ResultReason.STOPPED, corrected = true)
                }
            }
        return CorrectionCheck(null, core.sets, core.setsWon, result)
    }

    private val KEPT = setOf(ResultReason.RETIRED, ResultReason.WALKOVER, ResultReason.ABANDONED)

    private fun issue(
        kind: CorrectionIssueKind,
        set: Int,
    ) = CorrectionCheck(CorrectionIssue(kind, set))

    /**
     * Satzarten, mit denen [set] beginnen kann: `null` = ohne Entscheidung (erster Satz bzw. Best-of startet selbst),
     * sonst die Antworten auf „Noch ein Satz?“ im offenen Modus.
     */
    private fun candidates(
        format: MatchFormat,
        core: Core,
        set: CorrectedSet,
    ): List<NextSetKind?> =
        when {
            core.status != MatchStatus.AWAITING_DECISION -> {
                listOf(null)
            }

            set.kind == SetKind.MATCH_TIEBREAK -> {
                listOfNotNull(
                    NextSetKind.MATCH_TIEBREAK.takeIf { format.matchTiebreak != null },
                    NextSetKind.TIEBREAK_7,
                    NextSetKind.MATCH_TIEBREAK_10,
                )
            }

            else -> {
                listOf(NextSetKind.REGULAR)
            }
        }

    /** Spielt [set] als Satz [index] nach; `null` = Stand exakt erreicht und Satz beendet. */
    private fun play(
        core: Core,
        set: CorrectedSet,
        index: Int,
    ): CorrectionIssueKind? {
        if (!core.inSet || core.sets.size != index) return CorrectionIssueKind.AFTER_MATCH_END
        if (core.setKind != set.kind) return CorrectionIssueKind.SET_KIND
        val score = set.score
        if (score.a !in 0..MAX_SCORE || score.b !in 0..MAX_SCORE || score.a == score.b) return CorrectionIssueKind.SET_SCORE
        val winner = if (score.a > score.b) Side.A else Side.B
        val regular = set.kind == SetKind.REGULAR
        if (!regular && set.tiebreak != null) return CorrectionIssueKind.TIEBREAK_SCORE
        val units = alternating(winner, score)
        for ((i, side) in units.withIndex()) {
            if (core.sets.size != index) return CorrectionIssueKind.SET_SCORE
            val ok =
                if (regular && core.gameKind == GameKind.STANDARD) {
                    award(core, index, side) { it.games[side] }
                } else if (regular) {
                    // Tiebreak im Satz: nur als letztes Spiel des Siegers zulässig
                    if (i != units.lastIndex) return CorrectionIssueKind.SET_SCORE
                    val tiebreak = set.tiebreak ?: return CorrectionIssueKind.TIEBREAK_REQUIRED
                    if (tiebreak.a == tiebreak.b || (if (tiebreak.a > tiebreak.b) Side.A else Side.B) != winner) {
                        return CorrectionIssueKind.TIEBREAK_SCORE
                    }
                    if (!playPoints(core, index, tiebreak)) return CorrectionIssueKind.TIEBREAK_SCORE
                    true
                } else {
                    award(core, index, side) { it.points[side] }
                }
            if (!ok) return CorrectionIssueKind.SET_SCORE
        }
        if (core.sets.size != index + 1) return CorrectionIssueKind.SET_SCORE
        val record = core.sets[index]
        val matches =
            when (set.kind) {
                SetKind.REGULAR -> record.games == score && record.tiebreak == set.tiebreak
                SetKind.MATCH_TIEBREAK -> record.tiebreak == score
                else -> record.points == score
            }
        return when {
            matches -> null
            regular && record.tiebreak == null && set.tiebreak != null && record.games == score -> CorrectionIssueKind.TIEBREAK_SCORE
            else -> CorrectionIssueKind.SET_SCORE
        }
    }

    /** Punkte eines Tiebreaks nachspielen; `false`, wenn der Satz zu früh oder gar nicht endet. */
    private fun playPoints(
        core: Core,
        index: Int,
        points: SidePair<Int>,
    ): Boolean {
        val winner = if (points.a > points.b) Side.A else Side.B
        for (side in alternating(winner, points)) {
            if (core.sets.size != index || !award(core, index, side) { it.points[side] }) return false
        }
        return core.sets.size == index + 1
    }

    /** Verlierer und Sieger abwechselnd bis zum Stand des Verlierers, danach der Rest des Siegers. */
    private fun alternating(
        winner: Side,
        score: SidePair<Int>,
    ): List<Side> {
        val lost = score[winner.other]
        return List(2 * lost) { if (it % 2 == 0) winner else winner.other } + List(score[winner] - lost) { winner }
    }

    /**
     * Eine Einheit (Spiel bzw. Punkt) für [side]: Ballwechsel für [side], bis [counter] steigt oder der Satz endet
     * (Pickleball-Side-Out braucht bis zu drei Ballwechsel, ein Standardspiel vier).
     */
    private fun award(
        core: Core,
        index: Int,
        side: Side,
        counter: (Core) -> Int,
    ): Boolean {
        val before = counter(core)
        repeat(MAX_RALLIES) {
            if (!core.step(event(PointWon(side))) { null }) return false
            if (core.sets.size != index || counter(core) > before) return true
        }
        return false
    }

    private const val MAX_RALLIES = 8

    private val SIM_ID = EventId("result-correction")

    private fun event(payload: EventPayload): ScoreEvent = ScoreEvent(SIM_ID, MatchId("sim"), DeviceId("sim"), 1, 1, 0, null, payload)
}
