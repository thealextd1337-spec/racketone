package eu.advantly.wear.live

import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMode
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.scoring.Correction
import eu.advantly.core.scoring.GameKind
import eu.advantly.core.scoring.MatchState
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.core.scoring.ResultReason
import eu.advantly.core.scoring.ServeCourt
import eu.advantly.core.scoring.SetKind
import eu.advantly.core.scoring.SidePair
import eu.advantly.data.ScoreSummary
import eu.advantly.match.session.ActiveMatch
import eu.advantly.match.session.SyncStatus
import eu.advantly.presentation.live.LiveScoreRules
import eu.advantly.presentation.live.PointLabel
import eu.advantly.presentation.live.ScoreHint
import eu.advantly.presentation.live.SetColumn
import eu.advantly.presentation.match.LiveMatchSnapshot
import eu.advantly.presentation.match.MatchSetup
import eu.advantly.sync.protocol.CompatibilityStatus
import eu.advantly.sync.protocol.PeerKind

/** Rolle dieses Geräts im offenen Match (Single-Scorer, 00 „Rollen“). */
enum class LiveRole {
    /** Dieses Gerät zählt: Tap-Flächen, Undo, Dialoge. */
    SCORER,

    /** Ein anderes Gerät zählt: gleiche Darstellung ohne Eingaben, „Anzeige“. */
    DISPLAY,
}

/** Sync-Anzeige (00 „Sync Zähler → Handy“, 04 §3.2). */
sealed interface SyncUi {
    /** Zähler: alle Events bestätigt, Gegenstelle verbunden („synchron“). */
    data object Synced : SyncUi

    /** Zähler: alles bestätigt, aber gerade keine Gegenstelle („nur lokal“). */
    data object LocalOnly : SyncUi

    /** Zähler: [count] Events noch bei keiner Gegenstelle („N ausstehend“). */
    data class Pending(
        val count: Int,
    ) : SyncUi

    /** Anzeige: Zähler verbunden. */
    data object Live : SyncUi

    /** Anzeige: Zähler nicht verbunden, letzter Abgleich vor [seconds] s (`null` = in dieser Sitzung noch keiner). */
    data class Stale(
        val seconds: Long?,
    ) : SyncUi
}

/** Aufschlagfeld rechts/links (02 §8 `serve.court`). */
enum class CourtLetter { R, L }

/**
 * Aufschlag-Indikator (04 §4.3.1, 02 §8.4): Ball an der aufschlagenden Side, Feld R/L, Tischtennis verbleibende
 * Aufschläge; im Doppel Aufschläger → Rückschläger.
 */
data class ServeUi(
    val side: Side,
    val court: CourtLetter?,
    val servesRemaining: Int?,
    val serverNumber: Int?,
    /** Nur Doppel: Namen (leer = Standardbezeichnung der UI). */
    val server: PlayerUi?,
    val receiver: PlayerUi?,
)

/** Spieler für die Anzeige: [name] `null` = Standardbezeichnung („Du“, „Gegner 1“ …) anhand von Side/Index. */
data class PlayerUi(
    val id: PlayerId,
    val side: Side,
    val index: Int,
    val name: String?,
)

/** Satzende-Dialog (02 §5.2, Modus Offen). */
data class DecisionUi(
    /** 1-basiert. */
    val setNumber: Int,
    val score: SidePair<Int>,
    val winner: Side,
    val offerMatchTiebreak: Boolean,
)

/** Matchende (04 W7). */
data class FinishedUi(
    val reason: ResultReason,
    val winner: Side?,
    val sets: List<SetColumn>,
    /** „Noch einer in gleicher Besetzung?“ (02 §5.2): nur am Zähler, nach regulärem Ende eines Best-of. */
    val canFollowUp: Boolean,
)

/** Bestätigungen über dem Live-Screen. */
enum class LiveOverlay {
    /** „Zählen übernehmen?“ (04 §3.2). */
    CONFIRM_TAKE_OVER,

    /** „Match beenden?“ (04 §4.3.3 Punkt 8). */
    CONFIRM_END,

    /** Aufschläger bzw. Aufstellung korrigieren (02 §7.1/§7.4). */
    CORRECT_SERVER,
}

/** Positionstausch einer Side im Doppel (02 §7.1 `RECEIVE_POSITIONS`, §7.4; Tischtennis: Rückschläger tauschen). */
data class PositionSwap(
    val side: Side,
    val correction: Correction,
)

/**
 * Korrekturen am Zähler, solange das Match läuft (02 §7.1/§7.4, §9.4): Aufschläger „ab Beginn“ im ersten Spiel bzw.
 * Satz (`INITIAL_SERVER`, rückwirkend), sonst „ab jetzt“ (`SERVER`); im Doppel zusätzlich Positionen tauschen.
 */
data class CorrectionsUi(
    val retroactive: Boolean,
    val players: List<PlayerUi>,
    val currentServer: PlayerId?,
    val swaps: List<PositionSwap>,
) {
    /** Payload für die Wahl von [player] als Aufschläger. */
    fun serverCorrection(player: PlayerId): Correction = if (retroactive) Correction.initialServer(player) else Correction.server(player)
}

/** Alles, was der Live-Screen (Zähler und Anzeige) rendert. */
data class LiveUi(
    val matchId: MatchId,
    val sport: Sport,
    val doubles: Boolean,
    val role: LiveRole,
    /** Wer zählt (Anzeige: „Handy zählt“). */
    val scorerKind: PeerKind?,
    val teams: SidePair<List<PlayerUi>>,
    val points: SidePair<PointLabel>,
    /** Satzstände fürs Mittelband (Tennis/Padel inkl. laufendem Satz, sonst nur abgeschlossene). */
    val sets: List<SetColumn>,
    /** Pickleball-Doppel: Ansage „4-2-1“ im Mittelband. */
    val scoreCall: String?,
    val hint: ScoreHint?,
    val serve: ServeUi?,
    val status: MatchStatus,
    val sync: SyncUi,
    /** Anzeige: Zähler > 30 s nicht erreichbar → Hinweis mit „Zählen übernehmen“. */
    val takeOverOffered: Boolean,
    /** Ignorierte Eingaben eines Nicht-Zählers („N Eingaben nicht übernommen“). */
    val rejectedInputs: Int,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val decision: DecisionUi?,
    val finished: FinishedUi?,
    val locked: Boolean,
    val overlay: LiveOverlay?,
    /** Nur Zähler bei laufendem Match, sonst `null`. */
    val corrections: CorrectionsUi?,
) {
    val isScorer: Boolean get() = role == LiveRole.SCORER
}

/** Reine Abbildung Session-Zustand → [LiveUi] (JVM-getestet). */
object LiveModel {
    /** Ab dieser Dauer ohne Verbindung zum Zähler bietet die Anzeige die Übernahme an (04 §3.2). */
    const val TAKE_OVER_AFTER_MS: Long = 30_000

    /**
     * Zeiten sind **Dauern** (monoton gemessen, keine Wanduhr-Differenzen):
     *
     * @param names Anzeigename je Spieler (`null` = Standardbezeichnung)
     * @param contactAgoMs seit wann kein neuer Abgleich/kein neues Event vom Zähler kam, `null` = noch keiner
     * @param disconnectedForMs wie lange der Zähler (aus Sicht dieser Anzeige) schon nicht verbunden ist
     */
    fun build(
        match: ActiveMatch,
        sync: SyncStatus,
        names: (PlayerId) -> String?,
        contactAgoMs: Long?,
        disconnectedForMs: Long?,
        locked: Boolean = false,
        overlay: LiveOverlay? = null,
    ): LiveUi {
        val state = match.state
        val role = if (match.isScorer) LiveRole.SCORER else LiveRole.DISPLAY
        val teams = SidePair.of { side -> match.lineup.players(side).mapIndexed { i, id -> PlayerUi(id, side, i, names(id)) } }

        fun player(id: PlayerId?): PlayerUi? {
            val side = id?.let { match.lineup.sideOf(it) } ?: return null
            return PlayerUi(id, side, match.lineup.players(side).indexOf(id), names(id))
        }
        val doubles = match.lineup.a.size > 1
        val serve =
            state.serve?.let { s ->
                ServeUi(
                    side = s.side,
                    court =
                        when (s.court) {
                            ServeCourt.RIGHT -> CourtLetter.R
                            ServeCourt.LEFT -> CourtLetter.L
                            else -> null
                        },
                    servesRemaining = s.servesRemaining,
                    serverNumber = s.serverNumber,
                    server = if (doubles) player(s.player) else null,
                    receiver = if (doubles) player(s.receiver) else null,
                )
            }
        val finished = state.status == MatchStatus.FINISHED
        return LiveUi(
            matchId = match.id,
            sport = match.format.sport,
            doubles = doubles,
            role = role,
            scorerKind = match.scorerKind,
            teams = teams,
            points = SidePair.of { LiveScoreRules.pointLabel(state, it) },
            // Mittelband: Tennis/Padel mit laufendem Satz (Spiele), sonst nur abgeschlossene Sätze (Punkte stehen groß da)
            sets = LiveScoreRules.setColumns(state).filter { match.format.sport.isTennisLike || !it.isCurrent },
            scoreCall = state.scoreCall.takeIf { match.format.sport == Sport.PICKLEBALL && doubles && state.current != null },
            hint = LiveScoreRules.hint(snapshot(match)),
            serve = serve,
            status = state.status,
            sync = syncUi(role, sync, contactAgoMs),
            takeOverOffered =
                role == LiveRole.DISPLAY &&
                    !finished &&
                    !sync.scorerConnected &&
                    disconnectedForMs != null &&
                    disconnectedForMs >= TAKE_OVER_AFTER_MS,
            rejectedInputs = match.rejectedByRoleCount,
            canUndo = match.canUndo,
            canRedo = match.canRedo,
            decision = decision(match),
            finished = if (finished) finishedUi(match) else null,
            locked = locked && role == LiveRole.SCORER,
            overlay = overlay,
            corrections = if (role == LiveRole.SCORER) corrections(match, teams) else null,
        )
    }

    /** Sync-Anzeige: Zähler „synchron / N ausstehend“, Anzeige „Stand vor X s“. */
    fun syncUi(
        role: LiveRole,
        sync: SyncStatus,
        contactAgoMs: Long?,
    ): SyncUi {
        if (role == LiveRole.SCORER) {
            val connected = sync.peers.any { it.compatibility == CompatibilityStatus.COMPATIBLE }
            return when {
                sync.pendingToPeers > 0 -> SyncUi.Pending(sync.pendingToPeers)
                connected -> SyncUi.Synced
                else -> SyncUi.LocalOnly
            }
        }
        if (sync.scorerConnected) return SyncUi.Live
        return SyncUi.Stale(contactAgoMs?.let { it.coerceAtLeast(0) / 1_000 })
    }

    /** Zulässige Korrekturen (nur `IN_PROGRESS`/`PAUSED`; die UI erzeugt keine ungültigen Events, 02 §7.3). */
    private fun corrections(
        match: ActiveMatch,
        teams: SidePair<List<PlayerUi>>,
    ): CorrectionsUi? {
        val state = match.state
        if (state.status != MatchStatus.IN_PROGRESS && state.status != MatchStatus.PAUSED) return null
        val current = state.current ?: return null
        val sport = match.format.sport
        // wie am Handy (02 §9.4): rückwirkend nur im ersten Spiel bzw. ersten Satz
        val firstGame =
            state.sets.isEmpty() &&
                (!sport.isTennisLike || (current.games?.let { it.a + it.b == 0 } == true && current.gameKind == GameKind.STANDARD))
        val serve = state.serve
        val swaps = mutableListOf<PositionSwap>()
        if (match.lineup.a.size > 1 && serve != null) {
            when (sport) {
                // Badminton-Doppel: rechts/links einer Side sofort tauschen (Law 12)
                Sport.BADMINTON -> {
                    state.positions?.let { positions ->
                        Side.entries.forEach { side ->
                            positions[side].left?.let { swaps += PositionSwap(side, Correction.receivePositions(side, it)) }
                        }
                    }
                }

                // Tennis/Padel-Doppel: Rückschlagseite der rückschlagenden Side (ohne Punkt sofort, sonst ab nächstem Spiel)
                Sport.TENNIS, Sport.PADEL -> {
                    val receiving = serve.side.other
                    val receiver = serve.receiver
                    val partner = match.lineup.players(receiving).firstOrNull { it != receiver }
                    val newRight =
                        when (serve.court) {
                            ServeCourt.RIGHT -> partner
                            ServeCourt.LEFT -> receiver
                            else -> null
                        }
                    newRight?.let { swaps += PositionSwap(receiving, Correction.receivePositions(receiving, it)) }
                }

                // Tischtennis-Doppel: anderer Rückschläger (SERVER mit receiver, 02 §7.4)
                Sport.TABLE_TENNIS -> {
                    val receiving = serve.side.other
                    match.lineup
                        .players(receiving)
                        .firstOrNull { it != serve.receiver }
                        ?.let { swaps += PositionSwap(receiving, Correction.server(serve.player, receiver = it)) }
                }

                Sport.PICKLEBALL -> {
                    Unit
                }
            }
        }
        return CorrectionsUi(firstGame, teams.a + teams.b, serve?.player, swaps)
    }

    /** Stand als kurzer Text für Undo-Toast und Ongoing Activity, z. B. „30:15“, „AD:40“, ohne laufenden Satz „2:1“. */
    fun scoreText(state: MatchState): String {
        fun label(side: Side) =
            when (val p = LiveScoreRules.pointLabel(state, side)) {
                is PointLabel.Number -> p.value
                PointLabel.Advantage -> "AD"
            }
        return "${label(Side.A)}:${label(Side.B)}"
    }

    /**
     * Kurzstand für die Ongoing Activity auf dem Zifferblatt: Tennis/Padel „6:4 3:2 · 30:15“ (Sätze/Spiele · Punkte),
     * Rally-Sportarten „11:9 5:3“ (Satzpunkte), ohne Match leer.
     */
    fun ongoingText(match: ActiveMatch?): String {
        val state = match?.state ?: return ""
        val sets = setsText(LiveScoreRules.setColumns(state))
        val current = state.current
        return if (current?.games != null && state.status != MatchStatus.FINISHED) "$sets · ${scoreText(state)}" else sets
    }

    /** Satzstände als Text (Wir:Gegner), z. B. „6:4 3:2“; bei vorzeitigem Ende inkl. eingefrorenem Satz. */
    fun setsText(summary: ScoreSummary): String = (summary.sets + listOfNotNull(summary.current)).joinToString(" ") { "${it.a}:${it.b}" }

    /** Satzspalten als Text (Wir:Gegner), z. B. „6:4 3:2“. */
    fun setsText(columns: List<SetColumn>): String = columns.joinToString(" ") { "${it.values.a}:${it.values.b}" }

    private fun decision(match: ActiveMatch): DecisionUi? {
        val state = match.state
        if (state.status != MatchStatus.AWAITING_DECISION) return null
        val last = state.sets.lastOrNull() ?: return null
        val score =
            when (last.kind) {
                SetKind.REGULAR -> last.games
                SetKind.MATCH_TIEBREAK -> last.tiebreak ?: last.points
                SetKind.PB_GAME, SetKind.RALLY_GAME -> last.points
            } ?: SidePair(0, 0)
        return DecisionUi(state.sets.size, score, last.winner, offerMatchTiebreak = match.format.matchTiebreak != null)
    }

    private fun finishedUi(match: ActiveMatch): FinishedUi {
        val result = match.state.result
        val reason = result?.reason ?: ResultReason.ABANDONED
        return FinishedUi(
            reason = reason,
            winner = result?.winner,
            sets = LiveScoreRules.setColumns(match.state),
            canFollowUp = match.isScorer && reason == ResultReason.COMPLETED && match.format.match.mode == MatchMode.BEST_OF,
        )
    }

    private fun snapshot(match: ActiveMatch): LiveMatchSnapshot =
        LiveMatchSnapshot(
            matchId = match.id,
            setup = MatchSetup(match.format, match.lineup, match.meta, players = emptyList(), previousMatchId = match.previousMatchId),
            state = match.state,
            canUndo = match.canUndo,
            canRedo = match.canRedo,
            startedAtMs = match.createdAtMs,
            finishedAtMs = null,
            followUpMatchId = null,
            revision = match.eventCount.toLong(),
            pointsPlayed = match.pointsPlayed,
        )
}
