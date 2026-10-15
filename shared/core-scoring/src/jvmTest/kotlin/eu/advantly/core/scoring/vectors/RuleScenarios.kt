package eu.advantly.core.scoring.vectors

import eu.advantly.core.model.DeuceMode
import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.Discipline
import eu.advantly.core.model.EventId
import eu.advantly.core.model.GameFormat
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchFormat
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchSettings
import eu.advantly.core.model.PickleballFormat
import eu.advantly.core.model.PickleballScoring
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.scoring.ContinueDecision
import eu.advantly.core.scoring.Correction
import eu.advantly.core.scoring.EndReason
import eu.advantly.core.scoring.MatchDriver
import eu.advantly.core.scoring.MatchEnded
import eu.advantly.core.scoring.NextSetKind
import eu.advantly.core.scoring.Paused
import eu.advantly.core.scoring.PointDetail
import eu.advantly.core.scoring.PointDetailKind
import eu.advantly.core.scoring.PointDetailSet
import eu.advantly.core.scoring.PointWon
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.ScoreEvent
import eu.advantly.core.scoring.ScorerChanged
import eu.advantly.core.scoring.ScoringEngine
import eu.advantly.core.scoring.ServeOutcome
import eu.advantly.core.scoring.ServerChosen
import eu.advantly.core.scoring.Undo
import eu.advantly.core.scoring.lineupFor
import eu.advantly.core.scoring.p

/**
 * Regel-Szenario für den Export als Golden-Vektor (01 §8.1 „handgeschriebene Regel-Tests als Vektor“).
 * Entweder linear über einen [MatchDriver] ([script]) oder mit expliziten Hüllen mehrerer Geräte ([events]).
 */
class RuleScenario(
    val sport: Sport,
    val name: String,
    val description: String,
    val format: MatchFormat,
    val lineup: Lineup = lineupFor(format.discipline),
    val script: (MatchDriver.() -> Unit)? = null,
    val events: List<ScoreEvent>? = null,
    val scorer: DeviceId? = null,
)

/** Katalog der Szenarien – spiegelt die Regel-Tabellen-Tests aus `commonTest` (TennisRulesTest usw.). */
object RuleScenarios {
    private fun MatchDriver.win(
        side: Side,
        games: Int = 1,
    ) = repeat(games) { points(if (side == Side.A) "AAAA" else "BBBB") }

    private fun MatchDriver.rallySet(
        side: Side,
        points: Int,
    ) = points((if (side == Side.A) "A" else "B").repeat(points))

    private fun ev(
        id: String,
        lamport: Long,
        device: String,
        parent: String?,
        wallClockMs: Long,
        payload: eu.advantly.core.scoring.EventPayload,
    ) = ScoreEvent(EventId(id), MatchId("m"), DeviceId(device), lamport, lamport, wallClockMs, parent?.let { EventId(it) }, payload)

    private fun s(
        sport: Sport,
        name: String,
        description: String,
        format: MatchFormat,
        script: MatchDriver.() -> Unit,
    ) = RuleScenario(sport, name, description, format, script = script)

    @Suppress("LongMethod")
    fun all(): List<RuleScenario> {
        val tennis = Presets.TENNIS_SINGLES_BO3.format()
        val tennisDoubles = Presets.TENNIS_BO3_MTB10.format(Discipline.DOUBLES)
        val pbDoubles = Presets.PICKLEBALL_DOUBLES_11_SIDEOUT.format()
        val pbSingles = { pb: PickleballFormat ->
            MatchFormat(sport = Sport.PICKLEBALL, discipline = Discipline.SINGLES, match = MatchSettings.bestOf(1), pickleball = pb)
        }
        return listOf(
            // --- Tennis ---
            s(Sport.TENNIS, "deuce-advantage", "Anzeige 0-15-30-40, Einstand 1/2, Vorteil Rückschläger/Aufschläger, Spielgewinn", tennis) {
                points("AABBABBAAA")
            },
            s(Sport.TENNIS, "break-points", "0:40 = drei Breakbälle, 15:40 = zwei", tennis) { points("BBBAB") },
            s(
                Sport.TENNIS,
                "no-ad-deciding-point",
                "No-Ad im Doppel: Entscheidungspunkt, RECEIVER_CHOICE",
                Presets.TENNIS_DOUBLES_MTB10.format(),
            ) {
                points("AAABBBB")
            },
            s(Sport.TENNIS, "tiebreak-serve-and-ends", "Tiebreak 1-2-2 mit Seite, Wechsel alle 6 Punkte, Satz 7:6(6), Folgesatz", tennis) {
                holdGames(12)
                points("ABABABABABABAA")
            },
            s(Sport.TENNIS, "doubles-rotation-tiebreak", "Doppel A1-B1-A2-B2 und Tiebreak-Rotation im Doppel", tennisDoubles) {
                holdGames(12)
                points("ABABABABA")
            },
            s(
                Sport.TENNIS,
                "match-tiebreak",
                "Match-Tiebreak bis 10 statt 3. Satz, Wechsel alle 6, Protokoll [10:8]",
                Presets.TENNIS_BO3_MTB10.format(),
            ) {
                win(Side.A, 6)
                win(Side.B, 6)
                points("ABABABABABABABABAA")
            },
            s(
                Sport.TENNIS,
                "final-set-tiebreak-10",
                "Best of 5, Entscheidungssatz-Tiebreak bis 10",
                Presets.TENNIS_SINGLES_BO5_FS10.format(),
            ) {
                win(Side.A, 6)
                win(Side.B, 6)
                win(Side.A, 6)
                win(Side.B, 6)
                holdGames(12)
                points("AAAAAAAAAA")
            },
            s(Sport.TENNIS, "advantage-final-set", "Vorteilssatz ohne Tiebreak, 8:6", Presets.TENNIS_BO3_ADV_FINAL.format()) {
                win(Side.A, 6)
                win(Side.B, 6)
                holdGames(12)
                win(Side.A, 2)
            },
            s(
                Sport.TENNIS,
                "short-set-tiebreak-5",
                "Kurzsatz, Tiebreak bei 3:3 bis 5 mit winBy 1, nur ein Wechsel nach 4",
                Presets.TENNIS_SHORT_SETS_3ALL.format(),
            ) {
                holdGames(6)
                points("ABABABABB")
            },
            s(Sport.TENNIS, "correction-server", "Correction(SERVER) im Standardspiel und im Tiebreak", tennis) {
                send(Correction.server(p("B1")))
                win(Side.B)
                holdGames(11)
                point(Side.A)
                send(Correction.server(p("A1")))
                points("AA")
            },
            s(Sport.TENNIS, "correction-serving-order", "Correction(SERVING_ORDER) nur vor dem ersten Aufschlag des Teams", tennisDoubles) {
                send(Correction.servingOrder(Side.A, listOf(p("A2"), p("A1"))))
                point(Side.A)
                send(Correction.servingOrder(Side.A, listOf(p("A1"), p("A2"))))
                send(Correction.servingOrder(Side.B, listOf(p("B2"), p("B1"))))
                points("AAA")
            },
            s(Sport.TENNIS, "correction-initial-server", "Correction(INITIAL_SERVER) rückwirkend, Undo der Korrektur", tennis) {
                points("AA")
                send(Correction.initialServer(p("B1")))
                point(Side.B)
                undo()
            },
            s(
                Sport.TENNIS,
                "correction-receive-positions",
                "Correction(RECEIVE_POSITIONS) ab nächstem Spiel bzw. sofort vor dem ersten Punkt",
                tennisDoubles,
            ) {
                point(Side.A)
                send(Correction.receivePositions(Side.B, p("B2")))
                points("AAA")
                send(Correction.receivePositions(Side.A, p("A2")))
                holdGames(2)
            },
            s(
                Sport.TENNIS,
                "open-match-tiebreak-draw",
                "OPEN: Satzende-Frage, Match-Tiebreak, doppelte Antwort ignoriert, DRAW",
                Presets.TENNIS_OPEN.format(),
            ) {
                win(Side.A, 6)
                point(Side.B)
                send(ContinueDecision(true, NextSetKind.MATCH_TIEBREAK))
                send(ContinueDecision(true))
                points("BBBBBBBBBB")
                send(ContinueDecision(false))
            },
            s(
                Sport.TENNIS,
                "walkover-retired",
                "WALKOVER nach Punkten ignoriert, RETIRED ohne side ignoriert, Aufgabe friert ein",
                tennis,
            ) {
                point(Side.A)
                send(MatchEnded(EndReason.WALKOVER, Side.B))
                send(MatchEnded(EndReason.RETIRED))
                send(MatchEnded(EndReason.RETIRED, Side.A))
                point(Side.A)
                undo()
            },
            s(
                Sport.TENNIS,
                "stopped-as-it-stands",
                "Beenden, wie es steht (STOPPED, 02 §9.27): Spiele, dann Punkte, Gleichstand ohne Sieger, Sätze vor Spielen",
                tennis,
            ) {
                win(Side.A)
                send(MatchEnded(EndReason.STOPPED))
                undo()
                win(Side.B)
                point(Side.B)
                send(MatchEnded(EndReason.STOPPED))
                undo()
                point(Side.A)
                send(MatchEnded(EndReason.STOPPED, Side.B))
                undo()
                points("AAA")
                win(Side.A, 4)
                win(Side.B, 2)
                send(MatchEnded(EndReason.STOPPED))
                point(Side.A)
            },
            s(Sport.TENNIS, "undo-across-set", "Undo des Satzballs stellt Satz, Seiten und Aufschlag wieder her; Redo", tennis) {
                win(Side.A, 5)
                win(Side.B)
                points("AAA")
                point(Side.A)
                val u = undo()
                send(Undo(u))
            },
            s(
                Sport.TENNIS,
                "undo-middle-event",
                "Undo des Satzballs nach ContinueDecision: Antwort wird übersprungen",
                Presets.TENNIS_OPEN.format(),
            ) {
                win(Side.A, 5)
                points("AAA")
                val setBall = point(Side.A)
                send(ContinueDecision(true))
                point(Side.B)
                send(Undo(setBall))
            },
            RuleScenario(
                Sport.TENNIS,
                "double-tap-and-conflict",
                "Zwei Geräte: Doppel-Tap (gleicher Kopf, ≤ 5 s) wird absorbiert, Widerspruch zählt beide, Undo löst ihn",
                tennis,
                events =
                    listOf(
                        ev("w1", 1, "watch", null, 10_000, PointWon(Side.A)),
                        ev("p1", 2, "phone", "w1", 20_000, PointWon(Side.A)),
                        ev("w2", 2, "watch", "w1", 22_000, PointWon(Side.A)),
                        ev("p2", 3, "phone", "w2", 40_000, PointWon(Side.B)),
                        ev("w3", 3, "watch", "w2", 40_500, PointWon(Side.A)),
                        ev("p3", 4, "phone", "w3", 60_000, Undo(EventId("p2"))),
                    ),
            ),
            RuleScenario(
                Sport.TENNIS,
                "forward-undo-ignored",
                "Undo mit Ziel, das in der Ordnung später steht, wird übersprungen (02 §7.3 Schritt 2)",
                tennis,
                events =
                    listOf(
                        ev("u", 1, "phone", null, 10_000, Undo(EventId("x"))),
                        ev("x", 2, "watch", null, 12_000, PointWon(Side.A)),
                        ev("y", 3, "watch", "x", 20_000, Undo(EventId("x"))),
                    ),
            ),
            // --- Single-Scorer (02 §7.5) ---
            RuleScenario(
                Sport.TENNIS,
                "scorer-handover-mid-set",
                "Zählerübergabe mitten im Satz: Eingaben des Nicht-Zählers werden ignoriert (rejectedByRole), " +
                    "Undo auf ScorerChanged ist ungültig",
                tennis,
                scorer = DeviceId("watch"),
                events =
                    listOf(
                        ev("e1", 1, "watch", null, 10_000, PointWon(Side.A)),
                        ev("e2", 2, "watch", "e1", 20_000, PointWon(Side.A)),
                        ev("e3", 3, "phone", "e2", 25_000, PointWon(Side.B)),
                        ev("e4", 4, "phone", "e3", 30_000, ScorerChanged(DeviceId("phone"))),
                        ev("e5", 5, "phone", "e4", 40_000, PointWon(Side.B)),
                        ev("e6", 6, "watch", "e5", 45_000, PointWon(Side.A)),
                        ev("e7", 7, "watch", "e6", 46_000, Undo(EventId("e5"))),
                        ev("e8", 8, "phone", "e7", 50_000, Undo(EventId("e5"))),
                        ev("e9", 9, "watch", "e8", 60_000, ScorerChanged(DeviceId("watch"))),
                        ev("e10", 10, "watch", "e9", 70_000, PointWon(Side.A)),
                        ev("e11", 11, "watch", "e10", 71_000, Undo(EventId("e9"))),
                    ),
            ),
            RuleScenario(
                Sport.TENNIS,
                "scorer-offline-old-scorer",
                "Altzähler zählt offline weiter: seine nach dem ScorerChanged geordneten Events werden ignoriert",
                tennis,
                scorer = DeviceId("watch"),
                events =
                    listOf(
                        ev("e1", 1, "watch", null, 10_000, PointWon(Side.A)),
                        ev("e2", 2, "watch", "e1", 20_000, PointWon(Side.A)),
                        ev("w3", 3, "watch", "e2", 30_000, PointWon(Side.A)),
                        ev("w4", 4, "watch", "w3", 40_000, PointWon(Side.B)),
                        ev("p1", 3, "phone", "e2", 31_000, ScorerChanged(DeviceId("phone"))),
                        ev("p2", 4, "phone", "p1", 41_000, PointWon(Side.B)),
                    ),
            ),
            // --- Bedienung & Statistik (Entscheidung 2026-10-15) ---
            s(
                Sport.TENNIS,
                "remove-last-point-of-side",
                "Letzten Punkt einer Seite abziehen (Undo auf den letzten wirksamen Punkt) über die Satzgrenze, Redo",
                tennis,
            ) {
                win(Side.A, 6)
                points("BB")
                send(Undo(checkNotNull(ScoringEngine.lastEffectivePointOf(engineState, Side.A))))
                val redo = send(Undo(checkNotNull(ScoringEngine.lastEffectivePointOf(engineState, Side.B))))
                send(Undo(redo))
                points("AA")
            },
            s(
                Sport.TENNIS,
                "doubles-server-choice",
                "Aufschlägerwahl im Doppel: Game 1 aus allen vier, Game 2 anderes Team, ungültige Wahl, neuer Satz",
                tennisDoubles,
            ) {
                send(ServerChosen(0, Side.B, p("B2")))
                win(Side.B)
                send(ServerChosen(0, Side.B, p("B1")))
                send(ServerChosen(0, Side.A, p("A2")))
                holdGames(3)
                win(Side.A, 2)
                send(ServerChosen(1, Side.A, p("A1")))
                points("AB")
            },
            s(Sport.TENNIS, "serve-info", "Aufschlag-Info je Punkt, ungültiger Doppelfehler, letztes gilt, Ziel unwirksam", tennis) {
                val first = point(Side.A)
                send(PointDetailSet(first, PointDetail(serve = ServeOutcome.FIRST_SERVE_IN)))
                send(PointDetailSet(first, PointDetail(serve = ServeOutcome.DOUBLE_FAULT)))
                val second = point(Side.B)
                send(PointDetailSet(second, PointDetail(serve = ServeOutcome.DOUBLE_FAULT)))
                send(PointDetailSet(second, PointDetail(PointDetailKind.UNFORCED_ERROR, serve = ServeOutcome.SECOND_SERVE_IN)))
                send(Undo(second))
                send(PointDetailSet(second, PointDetail(serve = ServeOutcome.FIRST_SERVE_IN)))
            },
            s(
                Sport.TENNIS,
                "games-per-set-1",
                "Games pro Satz = 1: 1:0 kein Satz, 2:0 Satz, Tiebreak bei 1:1",
                Presets.withGamesPerSet(tennis, 1),
            ) {
                win(Side.A, 2)
                win(Side.A)
                win(Side.B)
                points("ABABABABA")
            },
            s(
                Sport.TENNIS,
                "open-tiebreak-sets",
                "Offener Modus: Tiebreak bis 7 und Champions-Tiebreak bis 10 als Sätze, ungültig bei fehlender Frage",
                Presets.TENNIS_OPEN.format(),
            ) {
                win(Side.A, 6)
                send(ContinueDecision(true, NextSetKind.TIEBREAK_7))
                send(ContinueDecision(true, NextSetKind.MATCH_TIEBREAK_10))
                points("ABABABABABBB")
                send(ContinueDecision(true, NextSetKind.MATCH_TIEBREAK_10))
                points("AAAAAAAAAA")
                send(ContinueDecision(false))
            },
            // --- Padel ---
            s(
                Sport.PADEL,
                "server-choice-correction",
                "Korrektur nach der Aufschlägerwahl hebt die Wahl auf, erneute Wahl wirkt (Review-Runde 1)",
                Presets.PADEL_GOLDEN_BO3.format(),
            ) {
                send(ServerChosen(0, Side.A, p("A2")))
                send(Correction.servingOrder(Side.A, listOf(p("A1"), p("A2"))))
                send(ServerChosen(0, Side.A, p("A2")))
                win(Side.A)
                send(ServerChosen(0, Side.B, p("B2")))
                send(Correction.server(p("B1")))
                points("BB")
            },
            s(
                Sport.PADEL,
                "tiebreak-only-server-choice",
                "Nur Tiebreak bis 7 im Doppel mit Aufschlägerwahl (erster Punkt / erster Aufschlag des anderen Teams)",
                Presets.PADEL_TIEBREAK_ONLY_7.format(),
            ) {
                send(ServerChosen(0, Side.B, p("B1")))
                point(Side.A)
                send(ServerChosen(0, Side.A, p("A2")))
                points("BABABABABAA")
            },
            RuleScenario(
                Sport.PADEL,
                "director-phone-scorer",
                "Director-Modus: das Handy zählt, Eingaben der Uhr (Punkt, Undo, Pause) werden ignoriert",
                Presets.PADEL_PREMIER_2026.format(),
                scorer = DeviceId("phone"),
                events =
                    listOf(
                        ev("e1", 1, "phone", null, 10_000, PointWon(Side.A)),
                        ev("e2", 2, "watch", "e1", 11_000, PointWon(Side.A)),
                        ev("e3", 3, "phone", "e2", 20_000, PointWon(Side.B)),
                        ev("e4", 4, "watch", "e3", 21_000, Undo(EventId("e3"))),
                        ev("e5", 5, "phone", "e4", 30_000, PointWon(Side.A)),
                        ev("e6", 6, "watch", "e5", 31_000, Paused),
                    ),
            ),
            s(
                Sport.PADEL,
                "star-point",
                "Einstand 1 → Vorteil 1 → Einstand 2 → Vorteil 2 → Star Point",
                Presets.PADEL_PREMIER_2026.format(),
            ) {
                points("AAABBBABBAB")
            },
            s(
                Sport.PADEL,
                "star-point-one-advantage",
                "Star Point mit maxAdvantages 1",
                Presets.PADEL_PREMIER_2026.format().copy(game = GameFormat(DeuceMode.STAR_POINT, 1)),
            ) { points("AAABBBABA") },
            s(Sport.PADEL, "golden-point", "Golden Point bei 40:40, Break, Rotation", Presets.PADEL_GOLDEN_BO3.format()) {
                points("ABABABBBBBBAAAA")
            },
            s(Sport.PADEL, "mini-sets-tiebreak", "Mini-Satz bis 4, Tiebreak bei 4:4", Presets.PADEL_MINI_SETS.format()) {
                holdGames(8)
                points("AAAAAAA")
            },
            // --- Pickleball ---
            s(Sport.PICKLEBALL, "doubles-server-1-2", "0-0-2, Side Out, Aufschläger 1/2, Positionen nach Score-Parität", pbDoubles) {
                points("BBAABA")
            },
            s(
                Sport.PICKLEBALL,
                "doubles-no-single-server",
                "Start mit Aufschläger 1 (firstServiceTurnSingleServer = false)",
                pbDoubles.copy(pickleball = pbDoubles.pickleball!!.copy(firstServiceTurnSingleServer = false)),
            ) { points("BAAB") },
            s(
                Sport.PICKLEBALL,
                "singles-side-out",
                "Einzel: Feld nach eigenem Score, Side Out",
                Presets.PICKLEBALL_SINGLES_11_SIDEOUT.format(),
            ) {
                points("AABBAB")
            },
            s(
                Sport.PICKLEBALL,
                "rally-doubles",
                "Rally Scoring: Rückschlagteam punktet und tauscht Positionen",
                Presets.PICKLEBALL_DOUBLES_RALLY_21.format(),
            ) {
                points("BBAAB")
            },
            s(
                Sport.PICKLEBALL,
                "rally-freeze",
                "Rally vor 2026: Spielpunkt nur bei eigenem Aufschlag",
                pbSingles(PickleballFormat(PickleballScoring.RALLY, 7, 2, gameWinningPointOnServeOnly = true)),
            ) { points("BBBBBBABB") },
            s(Sport.PICKLEBALL, "team-win-by-one", "Team-Play: Sieg mit einem Punkt (11:10)", Presets.PICKLEBALL_TEAM_WIN_BY_1.format()) {
                points("A".repeat(10) + "B".repeat(12))
            },
            s(
                Sport.PICKLEBALL,
                "point-cap",
                "Freizeit-Deckel: bis 11, Deckel 15",
                pbSingles(PickleballFormat(PickleballScoring.RALLY, 11, 2, pointCap = 15)),
            ) { points("AB".repeat(14) + "A") },
            s(
                Sport.PICKLEBALL,
                "best-of-3-deciding-game",
                "Wechsel bei 6 nur im 3. Spiel, Wechsel des ersten Aufschlags, SERVING_ORDER",
                Presets.PICKLEBALL_DOUBLES_BO3_11.format(),
            ) {
                points("AAAAAAAAAAA")
                send(Correction.servingOrder(Side.B, listOf(p("B2"), p("B1"))))
                points("BBBBBBBBBBB")
                points("AAAAAA")
            },
            s(
                Sport.PICKLEBALL,
                "open-every-game",
                "OPEN: Wechsel bei 6 in jedem Spiel, Wechsel am Spielende erst mit ContinueDecision",
                Presets.PICKLEBALL_OPEN.format(),
            ) {
                points("AAAAAAAAAAA")
                send(ContinueDecision(true))
                points("BBBBBB")
            },
            s(Sport.PICKLEBALL, "correction-server", "Correction(SERVER) mit Aufschlägernummer", pbDoubles) {
                send(Correction.server(p("B2"), serverNumber = 2))
                points("BA")
            },
            // --- Badminton ---
            s(
                Sport.BADMINTON,
                "cap-21-and-mid-change",
                "Einzelsatz bis 15: Wechsel bei 8, Verlängerung ab 14:14, Deckel 20:20",
                Presets.BADMINTON_1GAME_15.format(),
            ) {
                points("AB".repeat(20) + "A")
            },
            s(
                Sport.BADMINTON,
                "best-of-5-win-by-one",
                "5×11: Entscheidungspunkt bei 10:10, Satzsieger eröffnet",
                Presets.BADMINTON_BO5_11.format(),
            ) {
                points("AB".repeat(10) + "B")
            },
            s(
                Sport.BADMINTON,
                "doubles-corrections",
                "Doppel: SERVING_ORDER zu Satzbeginn, RECEIVE_POSITIONS (Law 12), SERVER",
                Presets.BADMINTON_DOUBLES_BO3_15.format(),
            ) {
                send(Correction.servingOrder(Side.A, listOf(p("A2"), p("A1"))))
                point(Side.A)
                send(Correction.servingOrder(Side.B, listOf(p("B2"), p("B1"))))
                send(Correction.receivePositions(Side.B, p("B2")))
                send(Correction.server(p("A1")))
                points("BBA")
            },
            s(
                Sport.BADMINTON,
                "doubles-deciding-game",
                "Doppel 3×15: Satzwechsel, Entscheidungssatz mit Wechsel bei 8",
                Presets.BADMINTON_DOUBLES_BO3_15.format(),
            ) {
                rallySet(Side.A, 15)
                rallySet(Side.B, 15)
                points("ABABABABABABABA")
            },
            s(
                Sport.BADMINTON,
                "open-and-retire",
                "OPEN ohne Wechsel in der Satzmitte, Satzende-Frage, Aufgabe",
                Presets.BADMINTON_OPEN.format(),
            ) {
                rallySet(Side.A, 15)
                send(ContinueDecision(true, NextSetKind.MATCH_TIEBREAK))
                send(ContinueDecision(true))
                points("AAB")
                send(MatchEnded(EndReason.RETIRED, Side.A))
            },
            // --- Tischtennis ---
            s(
                Sport.TABLE_TENNIS,
                "serve-rotation-deuce",
                "Aufschlagwechsel alle 2, ab 10:10 jeder Punkt, 13:11",
                Presets.TABLE_TENNIS_SINGLES_BO5.format(),
            ) {
                points("AB".repeat(10) + "BAAA")
            },
            s(
                Sport.TABLE_TENNIS,
                "best-of-7-deciding",
                "Best of 7: Wechsel bei 5 nur im 7. Satz, Einzel ohne Rückschlagtausch",
                Presets.TABLE_TENNIS_SINGLES_BO7.format(),
            ) {
                repeat(3) {
                    rallySet(Side.A, 11)
                    rallySet(Side.B, 11)
                }
                points("AAAAA")
            },
            s(
                Sport.TABLE_TENNIS,
                "leisure-21",
                "Alte Zählweise: Aufschlag alle 5, ab 20:20 jeder Punkt, Wechsel bei 10 im 3. Satz",
                Presets.TABLE_TENNIS_LEISURE_21.format(),
            ) {
                points("AAAAABBBBB" + "AB".repeat(15) + "AA")
                rallySet(Side.B, 21)
                points("AAAAAAAAAA")
            },
            s(
                Sport.TABLE_TENNIS,
                "doubles-serving-order",
                "Doppel: SERVING_ORDER im 1. Satz für beide Paare, ab Satz 2 nur Aufschlagpaar",
                Presets.TABLE_TENNIS_DOUBLES_BO3.format(),
            ) {
                send(Correction.servingOrder(Side.B, listOf(p("B2"), p("B1"))))
                send(Correction.servingOrder(Side.A, listOf(p("A2"), p("A1"))))
                rallySet(Side.A, 11)
                send(Correction.servingOrder(Side.A, listOf(p("A1"), p("A2"))))
                send(Correction.servingOrder(Side.B, listOf(p("B1"), p("B2"))))
                points("AB")
            },
            s(
                Sport.TABLE_TENNIS,
                "doubles-server-with-receiver",
                "Correction(SERVER, receiver) baut den Zyklus neu",
                Presets.TABLE_TENNIS_DOUBLES_BO5.format(),
            ) {
                send(Correction.server(p("B2"), receiver = p("A2")))
                points("AB")
                send(Correction.server(p("A1"), receiver = p("A2")))
                points("AB")
            },
            s(
                Sport.TABLE_TENNIS,
                "open-doubles",
                "OPEN: kein Wechsel bei 5, kein Rückschlagtausch, Pause",
                Presets.TABLE_TENNIS_OPEN.format(Discipline.DOUBLES),
            ) {
                rallySet(Side.A, 11)
                send(ContinueDecision(true))
                send(Paused)
                points("AAAAA")
            },
        )
    }
}
