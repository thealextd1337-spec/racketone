package eu.advantly.presentation.live

/**
 * Spike S4: bewusst einfacher Tennis-Zähler als Fake-Zustand für den Live-Score-Screen.
 *
 * KEINE Scoring-Engine – die entsteht parallel in `core-scoring` (Event Sourcing, Regelwerke, Undo-Events).
 * Dieser Fake kennt nur: Vorteil-Zählweise, Games bis 6 mit 2 Abstand, Tiebreak bis 7 bei 6:6,
 * Best of 3 und den Aufschlagwechsel. Er wird ersetzt, sobald `core-scoring` steht.
 */
enum class Team {
    Us,
    Them,
    ;

    val other: Team get() = if (this == Us) Them else Us
}

/** Format des Fake-Matches (Tennis, Best of 3, Vorteil, Tiebreak bei 6:6). */
data class FakeTennisFormat(
    val setsToWin: Int = 2,
    val gamesPerSet: Int = 6,
    val tiebreakTo: Int = 7,
    val firstServer: Team = Team.Us,
)

/** Abgeschlossener Satz; [tiebreakLoserPoints] nur bei 7:6 (für die hochgestellte Tiebreak-Zahl). */
data class SetResult(
    val gamesUs: Int,
    val gamesThem: Int,
    val tiebreakLoserPoints: Int? = null,
) {
    val winner: Team get() = if (gamesUs > gamesThem) Team.Us else Team.Them
}

/** Zustand des Fake-Matches. Punkte sind rohe Zähler (0, 1, 2, …), die Anzeige leitet [pointLabel] ab. */
data class FakeTennisState(
    val format: FakeTennisFormat = FakeTennisFormat(),
    val completedSets: List<SetResult> = emptyList(),
    val gamesUs: Int = 0,
    val gamesThem: Int = 0,
    val pointsUs: Int = 0,
    val pointsThem: Int = 0,
    val tiebreak: Boolean = false,
    val tiebreakFirstServer: Team? = null,
    val server: Team = format.firstServer,
    val winner: Team? = null,
) {
    val isFinished: Boolean get() = winner != null

    fun games(team: Team): Int = if (team == Team.Us) gamesUs else gamesThem

    fun points(team: Team): Int = if (team == Team.Us) pointsUs else pointsThem

    fun setsWon(team: Team): Int = completedSets.count { it.winner == team }
}

/** Anzeige eines Punktestands: Zahl (0/15/30/40 bzw. Tiebreak-Punkte) oder Vorteil („AD“, lokalisiert in der UI). */
sealed interface PointLabel {
    data class Number(
        val value: Int,
    ) : PointLabel

    data object Advantage : PointLabel
}

/** Hinweis-Pill (04 §4.3.1): Art, betroffenes Team und Darstellungsstufe (04 §5.6). */
data class ScoreHint(
    val kind: Kind,
    val team: Team?,
) {
    enum class Kind(
        val level: Level,
    ) {
        MatchPoint(Level.Filled),
        SetPoint(Level.Tinted),
        BreakPoint(Level.Tinted),
        Deuce(Level.Outline),
        Tiebreak(Level.Outline),
        ChangeEnds(Level.Outline),
        MatchWon(Level.Filled),
        MatchLost(Level.Filled),
    }

    /** Kontur (Einstand) · gefüllt-hell (Break-/Satzball) · gefüllt + Puls (Matchball). */
    enum class Level { Outline, Tinted, Filled }
}

/** Reine Funktionen des Fake-Zählers. */
object FakeTennis {
    private val tennisPoints = intArrayOf(0, 15, 30, 40)

    fun point(
        state: FakeTennisState,
        team: Team,
    ): FakeTennisState {
        if (state.isFinished) return state
        val us = state.pointsUs + if (team == Team.Us) 1 else 0
        val them = state.pointsThem + if (team == Team.Them) 1 else 0
        val mine = if (team == Team.Us) us else them
        val theirs = if (team == Team.Us) them else us
        return if (state.tiebreak) {
            if (mine >= state.format.tiebreakTo && mine - theirs >= 2) {
                winGame(state, team, tiebreakLoserPoints = theirs)
            } else {
                state.copy(pointsUs = us, pointsThem = them, server = tiebreakServer(state, us + them))
            }
        } else if (mine >= 4 && mine - theirs >= 2) {
            winGame(state, team, tiebreakLoserPoints = null)
        } else {
            state.copy(pointsUs = us, pointsThem = them)
        }
    }

    /** Tiebreak: erster Punkt Aufschläger A, danach wechselt der Aufschlag alle zwei Punkte. */
    private fun tiebreakServer(
        state: FakeTennisState,
        pointsPlayed: Int,
    ): Team {
        val first = state.tiebreakFirstServer ?: state.server
        return if (((pointsPlayed + 1) / 2) % 2 == 0) first else first.other
    }

    private fun winGame(
        state: FakeTennisState,
        team: Team,
        tiebreakLoserPoints: Int?,
    ): FakeTennisState {
        val gamesUs = state.gamesUs + if (team == Team.Us) 1 else 0
        val gamesThem = state.gamesThem + if (team == Team.Them) 1 else 0
        val mine = if (team == Team.Us) gamesUs else gamesThem
        val theirs = if (team == Team.Us) gamesThem else gamesUs
        val g = state.format.gamesPerSet
        val setWon = state.tiebreak || (mine >= g && mine - theirs >= 2)
        // Nach dem Tiebreak schlägt im neuen Satz zuerst, wer im Tiebreak zuerst rückgeschlagen hat.
        val nextServer = if (state.tiebreak) (state.tiebreakFirstServer ?: state.server).other else state.server.other
        if (setWon) {
            val sets = state.completedSets + SetResult(gamesUs, gamesThem, tiebreakLoserPoints)
            val won = sets.count { it.winner == team }
            return state.copy(
                completedSets = sets,
                gamesUs = 0,
                gamesThem = 0,
                pointsUs = 0,
                pointsThem = 0,
                tiebreak = false,
                tiebreakFirstServer = null,
                server = nextServer,
                winner = if (won >= state.format.setsToWin) team else null,
            )
        }
        val tiebreak = gamesUs == g && gamesThem == g
        return state.copy(
            gamesUs = gamesUs,
            gamesThem = gamesThem,
            pointsUs = 0,
            pointsThem = 0,
            tiebreak = tiebreak,
            tiebreakFirstServer = if (tiebreak) nextServer else null,
            server = nextServer,
        )
    }

    fun pointLabel(
        state: FakeTennisState,
        team: Team,
    ): PointLabel {
        val mine = state.points(team)
        val theirs = state.points(team.other)
        if (state.tiebreak) return PointLabel.Number(mine)
        if (mine >= 3 && theirs >= 3) {
            return if (mine > theirs) PointLabel.Advantage else PointLabel.Number(40)
        }
        return PointLabel.Number(tennisPoints[mine.coerceAtMost(3)])
    }

    /** Wer gewinnt mit dem nächsten Punkt das aktuelle Game (bzw. den Tiebreak)? */
    fun gamePointFor(state: FakeTennisState): Team? {
        if (state.isFinished) return null
        return Team.entries.firstOrNull { team ->
            val mine = state.points(team)
            val theirs = state.points(team.other)
            val needed = if (state.tiebreak) state.format.tiebreakTo - 1 else 3
            mine >= needed && mine > theirs
        }
    }

    /** Hinweis nach Priorität: Match > Satz > Break > Einstand > Seitenwechsel > Tiebreak. */
    fun hint(state: FakeTennisState): ScoreHint? {
        state.winner?.let { return ScoreHint(if (it == Team.Us) ScoreHint.Kind.MatchWon else ScoreHint.Kind.MatchLost, it) }
        gamePointFor(state)?.let { team ->
            val next = point(state, team)
            when {
                next.winner == team -> return ScoreHint(ScoreHint.Kind.MatchPoint, team)
                next.completedSets.size > state.completedSets.size -> return ScoreHint(ScoreHint.Kind.SetPoint, team)
                !state.tiebreak && team != state.server -> return ScoreHint(ScoreHint.Kind.BreakPoint, team)
            }
        }
        if (!state.tiebreak && state.pointsUs >= 3 && state.pointsUs == state.pointsThem) {
            return ScoreHint(ScoreHint.Kind.Deuce, null)
        }
        if (changeEnds(state)) return ScoreHint(ScoreHint.Kind.ChangeEnds, null)
        if (state.tiebreak) return ScoreHint(ScoreHint.Kind.Tiebreak, null)
        return null
    }

    /** Seitenwechsel nach ungerader Game-Summe (bzw. alle 6 Tiebreak-Punkte), solange noch kein Punkt gespielt ist. */
    fun changeEnds(state: FakeTennisState): Boolean {
        if (state.isFinished) return false
        val pointsPlayed = state.pointsUs + state.pointsThem
        if (state.tiebreak) return pointsPlayed > 0 && pointsPlayed % 6 == 0
        if (pointsPlayed != 0) return false
        val games = state.gamesUs + state.gamesThem
        if (games > 0) return games % 2 == 1
        val lastSet = state.completedSets.lastOrNull() ?: return false
        return (lastSet.gamesUs + lastSet.gamesThem) % 2 == 1
    }
}
