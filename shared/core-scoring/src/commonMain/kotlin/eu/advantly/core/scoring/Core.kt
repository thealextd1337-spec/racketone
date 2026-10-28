package eu.advantly.core.scoring

import eu.advantly.core.model.CourtSide
import eu.advantly.core.model.DeuceMode
import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.Discipline
import eu.advantly.core.model.End
import eu.advantly.core.model.EventId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchFormat
import eu.advantly.core.model.MatchMode
import eu.advantly.core.model.MatchTiebreakFormat
import eu.advantly.core.model.MidGameEndChange
import eu.advantly.core.model.PickleballScoring
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.RegularSet
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.model.TiebreakFormat

/**
 * Interner, veränderlicher Reducer-Zustand. Alle Felder halten **unveränderliche** Werte, daher ist [copy] flach und
 * billig (Was-wäre-wenn für die Status-Flags, inkrementelles `apply`). Die Algorithmen folgen 02 §6 wörtlich;
 * Kommentare verweisen auf die jeweilige Stelle. Nur Ganzzahlen, keine Uhr, kein Zufall.
 */
@Suppress("TooManyFunctions", "LargeClass")
internal class Core private constructor(
    val format: MatchFormat,
    val lineup: Lineup,
) {
    private val sport: Sport = format.sport
    private val doubles: Boolean = format.discipline == Discipline.DOUBLES

    // --- Match ---
    var status: MatchStatus = MatchStatus.IN_PROGRESS
    var result: MatchResult? = null
    var setsWon: SidePair<Int> = ZERO
    var sets: List<SetRecord> = emptyList()
    var ends: SidePair<End> = SidePair(End.END_1, End.END_2)
    var pendingEndChange: Boolean = false
    var inSet: Boolean = false
    var frozen: Boolean = false

    /** Anzahl angewendeter `PointWon` (WALKOVER-Prüfung); die IDs hält der Aufrufer (keine quadratischen Kopien). */
    var pointsApplied: Int = 0
    var ignored: List<EventId> = emptyList()
    var effects: Set<Effect> = emptySet()
    var order: SidePair<List<PlayerId>> = SidePair(lineup.a, lineup.b)
    var nextOpener: Side? = null

    // --- laufender Satz / laufendes Spiel ---
    var setKind: SetKind = SetKind.REGULAR
    var gameKind: GameKind = GameKind.STANDARD
    var points: SidePair<Int> = ZERO
    var setOpener: Side = Side.A
    var rallies: Int = 0
    var midDone: Boolean = false

    /** Tennis/Padel: Side, die das laufende Standardspiel aufschlägt („an der Reihe“). Pickleball/Badminton: S. */
    var serving: Side = Side.A

    // --- Tennis/Padel ---
    var games: SidePair<Int> = ZERO
    var serveIdx: SidePair<Int> = ZERO
    var tbFirst: Side = Side.A
    var tiebreak: TiebreakFormat? = null
    var regularSet: RegularSet? = null
    var servedInSet: SidePair<Boolean> = SidePair(false, false)
    var receiveRight: SidePair<PlayerId> = SidePair(lineup.a[0], lineup.b[0])
    var receiveRightNext: SidePair<PlayerId> = receiveRight
    var gamesWonTotal: SidePair<Int> = ZERO

    /** Doppel-Aufschlägerwahl im laufenden Satz (§6.5.5): Wahl vor dem 1. Ballwechsel bzw. des zweiten Teams. */
    var openingChoice: PlayerId? = null
    var secondChoice: PlayerId? = null

    // --- Pickleball ---
    var pbServer: PlayerId = lineup.a[0]
    var pbServerNumber: Int? = null

    // --- Badminton-Doppel / Tischtennis-Doppel ---
    var pos: SidePair<Positions> = SidePair(Positions(lineup.a[0], lineup.a.getOrNull(1)), Positions(lineup.b[0], lineup.b.getOrNull(1)))
    var cycle: List<PlayerId> = emptyList()
    var prevCycle: List<PlayerId>? = null

    // --- Korrekturen nach Matchende ---

    /** Gezählter Stand vor der ersten wirksamen [ResultCorrected] (für das Zurücksetzen), sonst `null`. */
    var recorded: RecordedResult? = null

    /** Wirksame Aufschläger je Spiel ([GameServersCorrected]). */
    var gameServers: List<GameServer> = emptyList()

    fun copy(): Core =
        Core(format, lineup).also { c ->
            c.status = status
            c.result = result
            c.setsWon = setsWon
            c.sets = sets
            c.ends = ends
            c.pendingEndChange = pendingEndChange
            c.inSet = inSet
            c.frozen = frozen
            c.pointsApplied = pointsApplied
            c.ignored = ignored
            c.effects = effects
            c.order = order
            c.nextOpener = nextOpener
            c.setKind = setKind
            c.gameKind = gameKind
            c.points = points
            c.setOpener = setOpener
            c.rallies = rallies
            c.midDone = midDone
            c.serving = serving
            c.games = games
            c.serveIdx = serveIdx
            c.tbFirst = tbFirst
            c.tiebreak = tiebreak
            c.regularSet = regularSet
            c.servedInSet = servedInSet
            c.receiveRight = receiveRight
            c.receiveRightNext = receiveRightNext
            c.gamesWonTotal = gamesWonTotal
            c.openingChoice = openingChoice
            c.secondChoice = secondChoice
            c.pbServer = pbServer
            c.pbServerNumber = pbServerNumber
            c.pos = pos
            c.cycle = cycle
            c.prevCycle = prevCycle
            c.recorded = recorded
            c.gameServers = gameServers
        }

    // =================================================================================================================
    // Events (02 §7)
    // =================================================================================================================

    /**
     * Wendet ein wirksames Event an; ist seine Vorbedingung verletzt, landet es in [ignored] (02 §7.3 Schritt 4).
     * [appliedPoint] liefert zu einer Event-ID das bereits angewendete `PointWon` (für `PointDetailSet`), sonst `null`.
     */
    fun step(
        event: ScoreEvent,
        appliedPoint: (EventId) -> AppliedPoint?,
    ): Boolean {
        effects = emptySet()
        val applied =
            when (val payload = event.payload) {
                is PointWon -> onPointWon(payload)
                is ContinueDecision -> onContinueDecision(payload)
                is Correction -> onCorrection(payload)
                is MatchEnded -> onMatchEnded(payload)
                Paused -> onPaused()
                Resumed -> onResumed()
                is PointDetailSet -> onPointDetailSet(payload, appliedPoint(payload.target))
                is ServerChosen -> onServerChosen(payload)
                is ResultCorrected -> onResultCorrected(payload)
                is GameServersCorrected -> onGameServersCorrected(payload)
                is PlayerAssigned -> error("PlayerAssigned wirkt nur in der Identitätsprojektion")
                is MatchCreated -> false
                is Undo -> error("Undo geht nicht in den Fold ein (02 §7.3)")
                is ScorerChanged -> error("ScorerChanged wirkt in der Log-Analyse (02 §7.5)")
            }
        if (!applied) markIgnored(event.id)
        return applied
    }

    /** Übersprungenes Event: `id` an `ignoredEvents` anhängen, Effekt `IGNORED` (02 §7.3 Schritte 4–5). */
    fun markIgnored(id: EventId) {
        ignored = ignored + id
        effects = setOf(Effect.IGNORED)
    }

    private fun onPointWon(payload: PointWon): Boolean {
        if (!inSet || (status != MatchStatus.IN_PROGRESS && status != MatchStatus.PAUSED)) return false
        if (status == MatchStatus.PAUSED) {
            status = MatchStatus.IN_PROGRESS
            effects = effects + Effect.RESUMED
        }
        pointsApplied += 1
        pointWon(payload.side)
        return true
    }

    /** §7.3: Ziel ist ein wirksames `PointWon`; Aufschlag-Info nur Tennis/Padel, Doppelfehler nur für die Rückschlagseite. */
    private fun onPointDetailSet(
        payload: PointDetailSet,
        target: AppliedPoint?,
    ): Boolean = target != null && PointDetailRules.isAllowedInFold(payload.detail, target.side, target.servingSide, sport)

    /** Aufschlag des nächsten Ballwechsels (für die Punkt-Zuordnung), `null` ohne laufenden Satz. */
    fun serveNow(): ServeInfo? = if (inSet && !frozen) serveInfo() else null

    private fun onPaused(): Boolean {
        if (status != MatchStatus.IN_PROGRESS) return false
        status = MatchStatus.PAUSED
        effects = effects + Effect.PAUSED
        return true
    }

    private fun onResumed(): Boolean {
        if (status != MatchStatus.PAUSED) return false
        status = MatchStatus.IN_PROGRESS
        effects = effects + Effect.RESUMED
        return true
    }

    private fun onContinueDecision(payload: ContinueDecision): Boolean {
        if (status != MatchStatus.AWAITING_DECISION) return false
        if (payload.anotherSet) {
            val allowed =
                when (payload.nextSetKind) {
                    NextSetKind.REGULAR -> true
                    NextSetKind.MATCH_TIEBREAK -> sport.isTennisLike && format.matchTiebreak != null
                    NextSetKind.TIEBREAK_7, NextSetKind.MATCH_TIEBREAK_10 -> sport.isTennisLike
                }
            if (!allowed) return false
            status = MatchStatus.IN_PROGRESS
            startSet(checkNotNull(nextOpener), payload.nextSetKind)
        } else {
            val winner =
                when {
                    setsWon.a > setsWon.b -> Side.A
                    setsWon.b > setsWon.a -> Side.B
                    else -> null
                }
            finish(winner, if (winner == null) ResultReason.DRAW else ResultReason.COMPLETED)
            effects = effects + (if (winner == null) Effect.MATCH_DRAWN else Effect.MATCH_WON)
        }
        return true
    }

    private fun onMatchEnded(payload: MatchEnded): Boolean {
        if (status == MatchStatus.FINISHED) return false
        val needsSide = payload.reason == EndReason.RETIRED || payload.reason == EndReason.WALKOVER
        if (needsSide && payload.side == null) return false
        if (payload.reason == EndReason.WALKOVER && pointsApplied > 0) return false
        val winner =
            when (payload.reason) {
                EndReason.RETIRED, EndReason.WALKOVER -> payload.side?.other
                EndReason.ABANDONED -> null
                EndReason.STOPPED -> leadingSide()
            }
        val reason =
            when (payload.reason) {
                EndReason.RETIRED -> ResultReason.RETIRED
                EndReason.WALKOVER -> ResultReason.WALKOVER
                EndReason.ABANDONED -> ResultReason.ABANDONED
                EndReason.STOPPED -> ResultReason.STOPPED
            }
        finish(winner, reason)
        frozen = inSet
        effects = effects + Effect.MATCH_ENDED
        return true
    }

    /** Ergebnis-Korrektur ([ResultCorrected]): nur nach Matchende, Plausibilität über [ResultCorrection.check]. */
    private fun onResultCorrected(payload: ResultCorrected): Boolean {
        if (status != MatchStatus.FINISHED) return false
        val original = recorded ?: RecordedResult(checkNotNull(result), sets, setsWon, inSet)
        if (payload.sets.isEmpty()) {
            result = original.result
            sets = original.sets
            setsWon = original.setsWon
            inSet = original.inSet
            recorded = null
            return true
        }
        val check = ResultCorrection.check(format, lineup, payload.sets, original.result)
        if (check.issue != null) return false
        recorded = original
        result = check.result
        sets = check.sets
        setsWon = check.setsWon
        // Das Ergebnis besteht nur aus den eingegebenen Sätzen; ein eingefrorener laufender Satz entfällt.
        inSet = false
        return true
    }

    /** Aufschläger je Spiel ([GameServersCorrected]): Tennis/Padel nach Matchende, jedes vorhandene Spiel höchstens einmal. */
    private fun onGameServersCorrected(payload: GameServersCorrected): Boolean {
        if (status != MatchStatus.FINISHED || !sport.isTennisLike) return false
        val keys = payload.servers.map { it.set to it.game }
        if (keys.toSet().size != keys.size) return false
        if (payload.servers.any { sideOf(it.player) == null || it.game !in 0 until standardGames(it.set) }) return false
        gameServers = payload.servers
        return true
    }

    /**
     * Standardspiele im Satz [set] (Tennis/Padel): abgeschlossener `REGULAR`-Satz ohne sein Tiebreak-Spiel, beim
     * eingefrorenen laufenden Satz inklusive des unterbrochenen Standardspiels; Match-Tiebreaks haben keine.
     */
    fun standardGames(set: Int): Int {
        val record = sets.getOrNull(set)
        val games = record?.games
        return when {
            record != null -> {
                if (record.kind == SetKind.REGULAR &&
                    games != null
                ) {
                    games.a + games.b - (if (record.tiebreak != null) 1 else 0)
                } else {
                    0
                }
            }

            set == sets.size && inSet -> {
                this.games.a + this.games.b + (if (gameKind == GameKind.STANDARD) 1 else 0)
            }

            else -> {
                0
            }
        }
    }

    /**
     * Führende Side für „Beenden, wie es steht“ (02 §9.27): erst gewonnene Sätze, dann Spiele im laufenden Satz
     * (Tennis/Padel), dann Punkte im laufenden Spiel bzw. Satz (Rohpunkte, im Tiebreak die Tiebreak-Punkte); bei
     * Gleichstand auf allen Stufen `null`. Ohne laufenden Satz (`AWAITING_DECISION`) zählen nur die Sätze.
     */
    private fun leadingSide(): Side? = StopRule.winner(setsWon, games.takeIf { inSet && sport.isTennisLike }, points.takeIf { inSet })

    private fun finish(
        winner: Side?,
        reason: ResultReason,
    ) {
        status = MatchStatus.FINISHED
        result = MatchResult(winner, reason)
        // §6.4/§6.7: Nach Matchende gibt es keinen Seitenwechsel – ein vorgemerkter Wechsel verfällt.
        pendingEndChange = false
    }

    // =================================================================================================================
    // Satz und Match (02 §6.3, §6.4) – gemeinsam für alle Sportarten
    // =================================================================================================================

    /** Startet einen Satz (bzw. ein Pickleball-Spiel); wendet einen vorgemerkten Seitenwechsel an (§6.4 `startSet`). */
    fun startSet(
        opener: Side,
        kind: NextSetKind = NextSetKind.REGULAR,
    ) {
        if (pendingEndChange) {
            changeEnds()
            pendingEndChange = false
        }
        inSet = true
        points = ZERO
        rallies = 0
        midDone = false
        setOpener = opener
        nextOpener = null
        openingChoice = null
        secondChoice = null
        effects = effects + Effect.SET_STARTED
        when {
            sport.isTennisLike -> tpStartSet(opener, kind)
            sport == Sport.PICKLEBALL -> pbStartGame(opener)
            else -> rallyStartSet(opener)
        }
    }

    private fun setWon(
        winner: Side,
        record: SetRecord,
        opener: Side,
    ) {
        sets = sets + record
        setsWon = setsWon.with(winner, setsWon[winner] + 1)
        effects = effects + Effect.SET_WON
        inSet = false
        nextOpener = opener
        if (format.match.mode == MatchMode.BEST_OF) {
            if (setsWon[winner] == format.setsToWin) {
                finish(winner, ResultReason.COMPLETED)
                effects = effects + Effect.MATCH_WON
            } else {
                startSet(opener)
            }
        } else {
            // OPEN: pendingEndChange bleibt bis zur Antwort (§6.4)
            status = MatchStatus.AWAITING_DECISION
            effects = effects + Effect.ASK_ANOTHER_SET
        }
    }

    /** §6.3: `mode == BEST_OF && setsWon[A] == setsWon[B] == setsToWin − 1` (Best of 1 ⇒ immer). */
    private fun isDecidingSet(): Boolean {
        val toWin = format.setsToWin ?: return false
        return setsWon.a == toWin - 1 && setsWon.b == toWin - 1
    }

    private fun changeEnds() {
        ends = SidePair(ends.a.other, ends.b.other)
        effects = effects + Effect.CHANGE_ENDS
    }

    /** Einmaliger Seitenwechsel in der Mitte (PB §6.6, B/TT §6.8.1): Gilt er im laufenden Satz? */
    private fun midChangeApplies(mode: MidGameEndChange): Boolean =
        when (mode) {
            MidGameEndChange.EVERY_GAME -> true
            MidGameEndChange.NEVER -> false
            MidGameEndChange.DECIDING_GAME_ONLY -> format.match.mode == MatchMode.BEST_OF && isDecidingSet()
        }

    private fun pointWon(side: Side) {
        when {
            sport.isTennisLike -> tpPointWon(side)
            sport == Sport.PICKLEBALL -> pbPointWon(side)
            else -> rallyPointWon(side)
        }
    }

    private fun partner(player: PlayerId): PlayerId {
        val team = if (player in order.a) order.a else order.b
        return team.firstOrNull { it != player } ?: player
    }

    private fun sideOf(player: PlayerId): Side? = lineup.sideOf(player)

    // =================================================================================================================
    // Tennis/Padel (02 §6.1–§6.5)
    // =================================================================================================================

    private fun tpStartSet(
        opener: Side,
        kind: NextSetKind,
    ) {
        games = ZERO
        serveIdx = ZERO
        servedInSet = SidePair(false, false)
        serving = opener
        val sf =
            when {
                kind == NextSetKind.MATCH_TIEBREAK -> checkNotNull(format.matchTiebreak)
                kind == NextSetKind.TIEBREAK_7 -> MatchTiebreakFormat.MTB7
                kind == NextSetKind.MATCH_TIEBREAK_10 -> MatchTiebreakFormat.MTB10
                isDecidingSet() -> format.decidingSet ?: checkNotNull(format.set)
                else -> checkNotNull(format.set)
            }
        when (sf) {
            is MatchTiebreakFormat -> {
                setKind = SetKind.MATCH_TIEBREAK
                regularSet = null
                startTiebreakGame(GameKind.MATCH_TIEBREAK, TiebreakFormat(sf.pointsToWin, sf.winBy, sf.endChange), opener)
            }

            is RegularSet -> {
                setKind = SetKind.REGULAR
                regularSet = sf
                if (sf.tiebreakAt == 0) {
                    startTiebreakGame(GameKind.TIEBREAK, sf.tiebreak, serving)
                    effects = effects + Effect.TIEBREAK_STARTED
                } else {
                    tpStartStandardGame()
                }
            }
        }
    }

    private fun tpStartStandardGame() {
        gameKind = GameKind.STANDARD
        points = ZERO
        tiebreak = null
        receiveRight = receiveRightNext
    }

    private fun startTiebreakGame(
        kind: GameKind,
        tb: TiebreakFormat,
        first: Side,
    ) {
        gameKind = kind
        tiebreak = tb
        tbFirst = first
        points = ZERO
        receiveRight = receiveRightNext
    }

    /** §6.1 Entscheidungspunkt (vor dem Punkt ausgewertet). */
    private fun tpIsDecidingPoint(): Boolean {
        if (gameKind != GameKind.STANDARD) return false
        val g = checkNotNull(format.game)
        val maxAdv =
            when (g.deuceMode) {
                DeuceMode.ADVANTAGE -> Int.MAX_VALUE
                DeuceMode.DECIDING_POINT -> 0
                DeuceMode.STAR_POINT -> g.effectiveMaxAdvantages
            }
        val deuceNo = if (points.a == points.b && points.a >= 3) points.a - 2 else 0
        return deuceNo > 0 && deuceNo > maxAdv
    }

    private fun tpPointWon(x: Side) {
        effects = effects + Effect.POINT
        rallies += 1
        val serverSide = tpServeSide()
        servedInSet = servedInSet.with(serverSide, true)
        if (gameKind == GameKind.STANDARD) {
            val wasDeciding = tpIsDecidingPoint()
            points = points.with(x, points[x] + 1)
            if ((points[x] >= 4 && points[x] - points[x.other] >= 2) || wasDeciding) tpGameWon(x)
        } else {
            val tb = checkNotNull(tiebreak)
            points = points.with(x, points[x] + 1)
            val k = points.a + points.b
            if (points[x] >= tb.pointsToWin && points[x] - points[x.other] >= tb.winBy) {
                tpGameWon(x)
            } else {
                // §6.2: Wechsel im Tiebreak nur, wenn er weitergeht
                val first = tb.endChange.first
                val every = tb.endChange.every
                if (k == first || (every != null && k > first && (k - first) % every == 0)) changeEnds()
            }
        }
    }

    /** §6.3 `gameWon` – Standardspiel, Tiebreak oder Match-Tiebreak. */
    private fun tpGameWon(x: Side) {
        effects = effects + Effect.GAME_WON
        gamesWonTotal = gamesWonTotal.with(x, gamesWonTotal[x] + 1)
        val lastKind = gameKind
        if (lastKind == GameKind.STANDARD) {
            // §6.5.1: nach dem Spiel serveIdx[S] += 1 und S = other(S)
            serveIdx = serveIdx.with(serving, serveIdx[serving] + 1)
            serving = serving.other
        }
        games = games.with(x, games[x] + 1)
        val sf = regularSet
        val setEnds =
            lastKind != GameKind.STANDARD ||
                (sf != null && games[x] >= sf.gamesToWin && games[x] - games[x.other] >= 2)
        if ((games.a + games.b) % 2 == 1) {
            if (setEnds) pendingEndChange = true else changeEnds()
        }
        if (setEnds) {
            // §6.5.3: Standardspiel → other(Aufschläger des letzten Spiels) = serving (schon gewechselt); Tiebreak → other(tbFirst)
            val opener = if (lastKind == GameKind.STANDARD) serving else tbFirst.other
            val record =
                when (lastKind) {
                    GameKind.MATCH_TIEBREAK -> {
                        SetRecord(SetKind.MATCH_TIEBREAK, SidePair.of { if (it == x) 1 else 0 }, points, null, x)
                    }

                    GameKind.TIEBREAK -> {
                        SetRecord(SetKind.REGULAR, games, points, null, x)
                    }

                    else -> {
                        SetRecord(SetKind.REGULAR, games, null, null, x)
                    }
                }
            setWon(x, record, opener)
        } else if (sf?.tiebreakAt != null && games.a == sf.tiebreakAt && games.b == sf.tiebreakAt) {
            startTiebreakGame(GameKind.TIEBREAK, sf.tiebreak, serving)
            effects = effects + Effect.TIEBREAK_STARTED
        } else {
            tpStartStandardGame()
        }
    }

    /** §6.5.2: Slot `s = (k + 1) / 2` → Folge 0,1,1,2,2,… */
    private fun tbSideAt(k: Int): Side = if (((k + 1) / 2) % 2 == 0) tbFirst else tbFirst.other

    private fun tbSlotOfSide(k: Int): Int {
        val s = (k + 1) / 2
        return if (s % 2 == 0) s / 2 else (s - 1) / 2
    }

    private fun tpServeSide(): Side = if (gameKind == GameKind.STANDARD) serving else tbSideAt(points.a + points.b)

    private fun tpServer(): PlayerId {
        if (gameKind == GameKind.STANDARD) return rotate(serving, serveIdx[serving])
        val k = points.a + points.b
        val side = tbSideAt(k)
        return rotate(side, serveIdx[side] + tbSlotOfSide(k))
    }

    private fun rotate(
        side: Side,
        index: Int,
    ): PlayerId {
        val team = order[side]
        return team[((index % team.size) + team.size) % team.size]
    }

    private fun tpCourt(): ServeCourt =
        when {
            gameKind == GameKind.STANDARD && tpIsDecidingPoint() -> ServeCourt.RECEIVER_CHOICE
            (points.a + points.b) % 2 == 0 -> ServeCourt.RIGHT
            else -> ServeCourt.LEFT
        }

    private fun tpReceiver(
        receivingSide: Side,
        court: ServeCourt,
    ): PlayerId? =
        when {
            court == ServeCourt.RECEIVER_CHOICE -> null
            !doubles -> order[receivingSide][0]
            court == ServeCourt.RIGHT -> receiveRight[receivingSide]
            else -> partner(receiveRight[receivingSide])
        }

    private fun tpDisplay(): SidePair<String> {
        if (gameKind != GameKind.STANDARD) return points.map { _, p -> p.toString() }
        if (points.a >= 3 && points.b >= 3) {
            return when {
                points.a == points.b -> SidePair("40", "40")
                points.a > points.b -> SidePair("AD", "40")
                else -> SidePair("40", "AD")
            }
        }
        return points.map { _, p -> TENNIS_POINTS[p.coerceAtMost(3)] }
    }

    private fun tpCallout(): Callout? {
        if (gameKind != GameKind.STANDARD || points.a < 3 || points.b < 3) return null
        if (points.a == points.b) {
            if (!tpIsDecidingPoint()) return Callout.DEUCE
            return if (format.game?.deuceMode == DeuceMode.STAR_POINT) Callout.STAR_POINT else Callout.DECIDING_POINT
        }
        val leader = if (points.a > points.b) Side.A else Side.B
        return if (leader == tpServeSide()) Callout.ADVANTAGE_SERVER else Callout.ADVANTAGE_RECEIVER
    }

    // =================================================================================================================
    // Pickleball (02 §6.6)
    // =================================================================================================================

    private val pb get() = checkNotNull(format.pickleball)

    private fun pbStartGame(first: Side) {
        setKind = SetKind.PB_GAME
        gameKind = GameKind.RALLY_GAME
        serving = first
        pbServer = pbRightPlayer(first)
        pbServerNumber =
            if (doubles && pb.scoring == PickleballScoring.SIDE_OUT) {
                if (pb.firstServiceTurnSingleServer) 2 else 1
            } else {
                null
            }
    }

    /** §6.6 `rightPlayer`: Startaufschläger steht bei geradem Team-Score rechts (5.B.3). */
    private fun pbRightPlayer(x: Side): PlayerId {
        val team = order[x]
        return if (team.size == 1 || points[x] % 2 == 0) team[0] else team[1]
    }

    private fun pbWouldWin(
        x: Side,
        px: Int,
    ): Boolean {
        val cap = pb.pointCap
        return (px >= pb.pointsToWin && px - points[x.other] >= pb.winBy) || (cap != null && px >= cap)
    }

    private fun pbPointWon(x: Side) {
        rallies += 1
        val s = serving
        var sideOut = false
        if (pb.scoring == PickleballScoring.SIDE_OUT) {
            when {
                x == s -> {
                    points = points.with(x, points[x] + 1)
                    effects = effects + Effect.POINT
                }

                doubles && pbServerNumber == 1 -> {
                    pbServerNumber = 2
                    pbServer = partner(pbServer)
                    effects = effects + Effect.SECOND_SERVER
                }

                else -> {
                    serving = x
                    pbServer = pbRightPlayer(x)
                    pbServerNumber = if (doubles) 1 else null
                    sideOut = true
                }
            }
        } else {
            if (!(pb.gameWinningPointOnServeOnly && x != s && pbWouldWin(x, points[x] + 1))) {
                points = points.with(x, points[x] + 1)
                effects = effects + Effect.POINT
            }
            if (x != s) {
                // §6.6: Side Out nach der Punktvergabe – der Aufschläger steht laut neuem Score rechts
                serving = x
                pbServer = pbRightPlayer(x)
                sideOut = true
            }
        }
        if (pbWouldWin(x, points[x])) {
            // §6.6 gameWon: Seitenwechsel erst beim Start des nächsten Spiels (§6.7); kein SIDE_OUT am Spielende
            pendingEndChange = true
            setWon(x, SetRecord(SetKind.PB_GAME, null, null, points, x), setOpener.other)
            return
        }
        if (sideOut) effects = effects + Effect.SIDE_OUT
        if (midChangeApplies(pb.midGameEndChange) && !midDone && maxOf(points.a, points.b) >= pb.effectiveMidGameEndChangeAt) {
            changeEnds()
            midDone = true
        }
    }

    private fun pbCourt(): ServeCourt =
        if (!doubles) {
            if (points[serving] % 2 == 0) ServeCourt.RIGHT else ServeCourt.LEFT
        } else {
            if (pbServer == pbRightPlayer(serving)) ServeCourt.RIGHT else ServeCourt.LEFT
        }

    private fun pbPositions(x: Side): Positions {
        val right = pbRightPlayer(x)
        return Positions(right, if (doubles) partner(right) else null)
    }

    // =================================================================================================================
    // Rally-Satz: Badminton und Tischtennis (02 §6.8, §6.9)
    // =================================================================================================================

    private val rally get() = checkNotNull(format.rally)

    private fun rallyStartSet(first: Side) {
        setKind = SetKind.RALLY_GAME
        gameKind = GameKind.RALLY_GAME
        serving = first
        if (sport == Sport.BADMINTON) {
            // §6.8.3: Startaufstellung jedes Satzes aus order (Index 0 rechts)
            pos = SidePair.of { Positions(order[it][0], order[it].getOrNull(1)) }
        } else if (doubles) {
            cycle = ttBuildCycle()
        }
    }

    /** §6.9.3: Zyklus für den laufenden Satz (Satz 1 aus der Aufstellung, ab Satz 2 aus dem Vorsatz-Zyklus). */
    private fun ttBuildCycle(): List<PlayerId> {
        val s = serving
        val r = s.other
        val previous = prevCycle
        if (sets.isEmpty() || previous == null) return listOf(order[s][0], order[r][0], order[s][1], order[r][1])
        val x = order[s][0]
        val y = previous[(previous.indexOf(x) + 3) % 4]
        return listOf(x, y, partner(x), partner(y))
    }

    private fun rallyServeSide(): Side = if (sport == Sport.BADMINTON) serving else ttServeSide()

    private fun ttD(): Int = 2 * (rally.pointsToWin - 1)

    private fun ttSlot(): Int {
        val k = points.a + points.b
        val n = checkNotNull(rally.serveEvery)
        val m = checkNotNull(rally.serveEveryAtDeuce)
        val d = ttD()
        return if (k < d) k / n else d / n + (k - d) / m
    }

    private fun ttServeSide(): Side = if (ttSlot() % 2 == 0) serving else serving.other

    private fun ttServesRemaining(): Int {
        val k = points.a + points.b
        val n = checkNotNull(rally.serveEvery)
        val m = checkNotNull(rally.serveEveryAtDeuce)
        val d = ttD()
        return if (k < d) n - (k % n) else m - ((k - d) % m)
    }

    private fun rallyPointWon(x: Side) {
        rallies += 1
        val before = rallyServeSide()
        points = points.with(x, points[x] + 1)
        effects = effects + Effect.POINT
        if (sport == Sport.BADMINTON) {
            // §6.8.2/§6.8.3 sportStep
            if (x == serving) {
                if (doubles) pos = pos.with(x, Positions(checkNotNull(pos[x].left), pos[x].right))
            } else {
                serving = x
            }
        }
        val r = rally
        val cap = r.cap
        if ((points[x] >= r.pointsToWin && points[x] - points[x.other] >= r.winBy) || (cap != null && points[x] >= cap)) {
            // §6.8.1 rallySetWon
            pendingEndChange = true
            prevCycle = cycle
            val opener = if (sport == Sport.BADMINTON) x else serving.other
            setWon(x, SetRecord(SetKind.RALLY_GAME, null, null, points, x), opener)
            return
        }
        if (midChangeApplies(r.midGameEndChange) && !midDone && maxOf(points.a, points.b) >= r.effectiveMidGameEndChangeAt) {
            changeEnds()
            midDone = true
            if (doubles && r.receiverSwapAtMidGameEndChange && sport == Sport.TABLE_TENNIS) ttSwapReceivingPair()
        }
        if (rallyServeSide() != before) effects = effects + Effect.SIDE_OUT
    }

    /** §6.9.3 `swapReceivingPair`: tauscht c[(s+1) mod 4] und c[(s+3) mod 4]. */
    private fun ttSwapReceivingPair() {
        val s = ttSlot()
        val i = (s + 1) % 4
        val j = (s + 3) % 4
        cycle =
            cycle.mapIndexed { idx, p ->
                if (idx == i) {
                    cycle[j]
                } else if (idx == j) {
                    cycle[i]
                } else {
                    p
                }
            }
        effects = effects + Effect.RECEIVE_ORDER_SWAPPED
    }

    private fun rallyDecidingPoint(): Boolean {
        val r = rally
        val cap = r.cap
        return points.a == points.b &&
            ((cap != null && points.a == cap - 1) || (r.winBy == 1 && points.a == r.pointsToWin - 1))
    }

    private fun rallyCallout(): Callout? =
        when {
            rallyDecidingPoint() -> Callout.DECIDING_POINT
            points.a == points.b && points.a >= rally.pointsToWin - 1 && rally.winBy == 2 -> Callout.DEUCE
            else -> null
        }

    // =================================================================================================================
    // Korrekturen (02 §7.1, §7.4)
    // =================================================================================================================

    private fun onCorrection(c: Correction): Boolean {
        if (c.kind == CorrectionKind.INITIAL_SERVER) return c.player != null && sideOf(c.player) != null
        val live = inSet && (status == MatchStatus.IN_PROGRESS || status == MatchStatus.PAUSED)
        val applied =
            when (c.kind) {
                CorrectionKind.SERVER -> live && correctServer(c)
                CorrectionKind.SERVING_ORDER -> live && correctServingOrder(c)
                CorrectionKind.RECEIVE_POSITIONS -> correctReceivePositions(c)
                CorrectionKind.INITIAL_SERVER -> false
            }
        if (applied) dropStaleServerChoices()
        return applied
    }

    /**
     * §6.5.5: Eine Aufschlägerwahl gilt nur, solange der gewählte Spieler tatsächlich der nächste Aufschläger seines
     * Teams ist. Ändert eine Korrektur das (z. B. `SERVING_ORDER` nach der Wahl), ist die Wahl aufgehoben (`chosen = null`,
     * Wahl wieder erwartet); eine erneute Wahl setzt den Spieler wieder ein.
     */
    private fun dropStaleServerChoices() {
        if (!sport.isTennisLike || !doubles || !inSet) return
        openingChoice = openingChoice?.takeIf { rallies == 0 && tpServeSide() == sideOf(it) && tpServer() == it }
        secondChoice =
            secondChoice?.takeIf { player ->
                val side = sideOf(player)
                side != null && !servedInSet[side] && rotate(side, serveIdx[side]) == player
            }
    }

    private fun correctServer(c: Correction): Boolean {
        val player = c.player ?: return false
        val side = sideOf(player) ?: return false
        val index = order[side].indexOf(player)
        when {
            sport.isTennisLike -> {
                if (gameKind == GameKind.STANDARD) {
                    serving = side
                    serveIdx = serveIdx.with(side, index)
                } else {
                    // Tiebreak: Rhythmus bleibt an k gebunden, der Rotationsanker wird auf den aktuellen Punkt gelegt
                    val k = points.a + points.b
                    if (tbSideAt(k) != side) tbFirst = tbFirst.other
                    val len = order[side].size
                    serveIdx = serveIdx.with(side, ((index - tbSlotOfSide(k)) % len + len) % len)
                }
            }

            sport == Sport.PICKLEBALL -> {
                val number = c.serverNumber
                if (number != null && number != 1 && number != 2) return false
                serving = side
                pbServer = player
                pbServerNumber = if (doubles && pb.scoring == PickleballScoring.SIDE_OUT) number ?: 1 else null
            }

            sport == Sport.BADMINTON -> {
                serving = side
                if (doubles) {
                    val court = if (points[side] % 2 == 0) CourtSide.RIGHT else CourtSide.LEFT
                    val current = pos[side]
                    val atCourt = if (court == CourtSide.RIGHT) current.right else current.left
                    if (atCourt != player) pos = pos.with(side, Positions(checkNotNull(current.left), current.right))
                }
            }

            else -> {
                // Tischtennis: gameFirst so setzen, dass serveSide() = side(player)
                val slot = ttSlot()
                if (c.receiver != null && sideOf(c.receiver) != side.other) return false
                serving = if (slot % 2 == 0) side else side.other
                if (doubles) {
                    val receiver =
                        c.receiver ?: cycle.getOrNull((slot + 1) % 4)?.takeIf { sideOf(it) == side.other } ?: order[side.other][0]
                    val newCycle = arrayOfNulls<PlayerId>(4)
                    newCycle[slot % 4] = player
                    newCycle[(slot + 1) % 4] = receiver
                    newCycle[(slot + 2) % 4] = partner(player)
                    newCycle[(slot + 3) % 4] = partner(receiver)
                    cycle = newCycle.map { checkNotNull(it) }
                }
            }
        }
        return true
    }

    /**
     * §6.5.5 Aufschlägerwahl im Doppel (Tennis/Padel): vor dem ersten Ballwechsel des Satzes jeder der vier Spieler
     * (ggf. wechselt die eröffnende Side), danach nur die Side, die im Satz noch nicht aufgeschlagen hat.
     */
    private fun onServerChosen(c: ServerChosen): Boolean {
        if (!sport.isTennisLike || !doubles) return false
        if (!inSet || (status != MatchStatus.IN_PROGRESS && status != MatchStatus.PAUSED)) return false
        if (c.set != sets.size || sideOf(c.player) != c.side) return false
        val side = c.side
        if (rallies > 0 && servedInSet[side]) return false
        if (rallies == 0 && side != tpServeSide()) {
            // Wahl aus dem anderen Team: diese Side eröffnet den Satz (Standardspiel bzw. Tiebreak-Satz)
            if (gameKind == GameKind.STANDARD) serving = side else tbFirst = side
        }
        order = order.with(side, listOf(c.player) + order[side].filter { it != c.player })
        serveIdx = serveIdx.with(side, 0)
        if (rallies == 0) openingChoice = c.player else secondChoice = c.player
        return true
    }

    /** Offenes Fenster der Aufschlägerwahl (§8 `serverChoice`), sonst `null`. */
    private fun serverChoice(): ServerChoice? {
        if (!sport.isTennisLike || !doubles || !inSet || frozen) return null
        if (status != MatchStatus.IN_PROGRESS && status != MatchStatus.PAUSED) return null
        val next = tpServeSide()
        val server = tpServer()
        // `chosen` ist nur der tatsächlich wirksame nächste Aufschläger (Invariante: chosen ∈ {null, serve.player})
        if (rallies == 0) return ServerChoice(1, next, order[next] + order[next.other], openingChoice?.takeIf { it == server })
        if (servedInSet[next]) return null
        return ServerChoice(2, next, order[next], secondChoice?.takeIf { it == server })
    }

    private fun correctServingOrder(c: Correction): Boolean {
        val side = c.side ?: return false
        val players = c.players ?: return false
        if (players.size != order[side].size || players.toSet() != order[side].toSet()) return false
        when {
            sport.isTennisLike -> {
                if (servedInSet[side]) return false
                order = order.with(side, players)
                serveIdx = serveIdx.with(side, 0)
            }

            sport == Sport.PICKLEBALL -> {
                if (rallies > 0) return false
                order = order.with(side, players)
                if (serving == side) pbServer = pbRightPlayer(side)
            }

            sport == Sport.BADMINTON -> {
                if (rallies > 0) return false
                order = order.with(side, players)
                pos = pos.with(side, Positions(players[0], players.getOrNull(1)))
            }

            else -> {
                if (rallies > 0) return false
                // Rückschlagseite nur im 1. Satz (ITTF 2.13.4)
                if (side != serving && sets.isNotEmpty()) return false
                order = order.with(side, players)
                if (doubles) cycle = ttBuildCycle()
            }
        }
        return true
    }

    private fun correctReceivePositions(c: Correction): Boolean {
        val side = c.side ?: return false
        val right = c.rightPlayer ?: return false
        if (!doubles || right !in order[side]) return false
        return when {
            sport.isTennisLike -> {
                if (status == MatchStatus.FINISHED) return false
                // R27e/FIP 8.4: wirksam ab dem nächsten Spiel; zwischen den Spielen (noch kein Punkt) sofort
                receiveRightNext = receiveRightNext.with(side, right)
                if (inSet && points.a + points.b == 0) receiveRight = receiveRight.with(side, right)
                true
            }

            sport == Sport.BADMINTON -> {
                if (!inSet || (status != MatchStatus.IN_PROGRESS && status != MatchStatus.PAUSED)) return false
                val current = pos[side]
                if (current.right != right) pos = pos.with(side, Positions(right, current.right))
                true
            }

            else -> {
                false
            }
        }
    }

    // =================================================================================================================
    // Export in die kanonische Form (02 §8)
    // =================================================================================================================

    fun export(
        scorer: DeviceId? = null,
        rejectedByRole: List<EventId> = emptyList(),
    ): MatchState {
        val live = inSet && !frozen
        val serve = if (live) serveInfo() else null
        return MatchState(
            status = status,
            pending = if (status == MatchStatus.AWAITING_DECISION) PendingQuestion.ASK_ANOTHER_SET else null,
            result = result,
            setsWon = setsWon,
            sets = sets,
            current = if (inSet) currentSet() else null,
            serve = serve,
            scoreCall = serve?.let { scoreCall(it) },
            positions = if (live) positions() else null,
            ends = ends,
            statusFlags = if (live && (status == MatchStatus.IN_PROGRESS || status == MatchStatus.PAUSED)) statusFlags() else emptyList(),
            effects = effects.sortedBy { it.ordinal },
            ignoredEvents = ignored,
            continuation = continuation(),
            scorerDeviceId = scorer,
            rejectedByRole = rejectedByRole,
            serverChoice = serverChoice(),
        )
    }

    private fun currentSet(): CurrentSet =
        when {
            sport.isTennisLike -> {
                CurrentSet(
                    setIndex = sets.size,
                    setKind = setKind,
                    gameKind = gameKind,
                    games = games,
                    points = points,
                    display = tpDisplay(),
                    callout = tpCallout(),
                    deuceCount = if (gameKind == GameKind.STANDARD) maxOf(0, minOf(points.a, points.b) - 2) else 0,
                    decidingPoint = tpIsDecidingPoint(),
                )
            }

            sport == Sport.PICKLEBALL -> {
                CurrentSet(sets.size, setKind, gameKind, null, points, decimal(), null, null, null)
            }

            else -> {
                CurrentSet(sets.size, setKind, gameKind, null, points, decimal(), rallyCallout(), null, rallyDecidingPoint())
            }
        }

    private fun decimal(): SidePair<String> = points.map { _, p -> p.toString() }

    private fun serveInfo(): ServeInfo =
        when {
            sport.isTennisLike -> {
                val side = tpServeSide()
                val court = tpCourt()
                ServeInfo(side, tpServer(), court, null, tpReceiver(side.other, court), null)
            }

            sport == Sport.PICKLEBALL -> {
                val court = pbCourt()
                val receiving = serving.other
                val receiver =
                    if (!doubles) {
                        order[receiving][0]
                    } else {
                        val right = pbRightPlayer(receiving)
                        if (court == ServeCourt.RIGHT) right else partner(right)
                    }
                ServeInfo(serving, pbServer, court, pbServerNumber, receiver, null)
            }

            sport == Sport.BADMINTON -> {
                val court = if (points[serving] % 2 == 0) CourtSide.RIGHT else CourtSide.LEFT
                val receiving = serving.other
                val (server, receiver) =
                    if (doubles) {
                        pos[serving].at(court) to pos[receiving].at(court)
                    } else {
                        order[serving][0] to order[receiving][0]
                    }
                ServeInfo(serving, server, if (court == CourtSide.RIGHT) ServeCourt.RIGHT else ServeCourt.LEFT, null, receiver, null)
            }

            else -> {
                val side = ttServeSide()
                val (server, receiver) =
                    if (doubles) {
                        val slot = ttSlot()
                        cycle[slot % 4] to cycle[(slot + 1) % 4]
                    } else {
                        order[side][0] to order[side.other][0]
                    }
                ServeInfo(side, server, null, null, receiver, ttServesRemaining())
            }
        }

    private fun Positions.at(court: CourtSide): PlayerId = if (court == CourtSide.RIGHT) right else checkNotNull(left)

    private fun scoreCall(serve: ServeInfo): String {
        val s = serve.side
        return when {
            sport.isTennisLike -> {
                val d = tpDisplay()
                "${d[s]}-${d[s.other]}"
            }

            sport == Sport.PICKLEBALL && doubles && pb.scoring == PickleballScoring.SIDE_OUT -> {
                "${points[s]}-${points[s.other]}-${serve.serverNumber}"
            }

            else -> {
                "${points[s]}-${points[s.other]}"
            }
        }
    }

    private fun positions(): SidePair<Positions>? =
        when (sport) {
            Sport.PICKLEBALL -> SidePair.of { pbPositions(it) }
            Sport.BADMINTON -> if (doubles) pos else SidePair.of { Positions(order[it][0], null) }
            else -> null
        }

    /** §8.2: Was-wäre-wenn je Side (erst A, dann B). */
    private fun statusFlags(): List<StatusFlag> {
        val flags = mutableListOf<StatusFlag>()
        val serveSide = serveInfo().side
        for (x in Side.entries) {
            val sim = copy()
            sim.status = MatchStatus.IN_PROGRESS
            sim.effects = emptySet()
            sim.pointWon(x)
            val level =
                when {
                    format.match.mode == MatchMode.BEST_OF && sim.status == MatchStatus.FINISHED && sim.result?.winner == x -> {
                        FlagLevel.MATCH_POINT
                    }

                    sim.setsWon[x] > setsWon[x] -> {
                        FlagLevel.SET_POINT
                    }

                    sport.isTennisLike && gameKind == GameKind.STANDARD && sim.gamesWonTotal[x] > gamesWonTotal[x] -> {
                        FlagLevel.GAME_POINT
                    }

                    else -> {
                        null
                    }
                } ?: continue
            val lead = points[x] - points[x.other]
            flags +=
                StatusFlag(
                    side = x,
                    level = level,
                    count = if (lead > 0) lead else 1,
                    breakPoint = sport.isTennisLike && gameKind == GameKind.STANDARD && x != serveSide,
                )
        }
        return flags
    }

    private fun continuation(): Continuation? {
        val finishedRegularly = status == MatchStatus.FINISHED && result?.reason == ResultReason.COMPLETED
        if (status != MatchStatus.AWAITING_DECISION && !finishedRegularly) return null
        val side = nextOpener ?: return null
        return Continuation(side, order[side][0])
    }

    /** Gezählter Endstand, den eine [ResultCorrected] überdeckt. */
    data class RecordedResult(
        val result: MatchResult,
        val sets: List<SetRecord>,
        val setsWon: SidePair<Int>,
        val inSet: Boolean,
    )

    companion object {
        private val ZERO = SidePair(0, 0)
        private val TENNIS_POINTS = listOf("0", "15", "30", "40")

        /**
         * Startzustand `initial(config, lineup')` (02 §7.3 Schritt 3). Steht `firstServer` nicht an Index 0 seines Teams,
         * wird das Team-Array umgedreht (02 §3.3).
         */
        fun initial(
            format: MatchFormat,
            lineup: Lineup,
        ): Core {
            val firstSide = requireNotNull(lineup.sideOf(lineup.firstServer)) { "firstServer gehört zu keiner Side" }
            val core = Core(format, lineup)
            core.order =
                SidePair.of { side ->
                    val team = lineup.players(side)
                    if (side == firstSide && team[0] != lineup.firstServer) team.reversed() else team
                }
            core.ends = SidePair(lineup.startEndA, lineup.startEndA.other)
            core.receiveRight =
                SidePair.of { side ->
                    lineup.receiveRight?.get(side)?.takeIf { it in lineup.players(side) } ?: lineup.players(side)[0]
                }
            core.receiveRightNext = core.receiveRight
            core.startSet(firstSide)
            core.effects = emptySet()
            return core
        }
    }
}
