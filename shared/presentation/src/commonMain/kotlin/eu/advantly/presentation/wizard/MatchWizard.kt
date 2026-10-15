package eu.advantly.presentation.wizard

import eu.advantly.core.model.DeuceMode
import eu.advantly.core.model.Discipline
import eu.advantly.core.model.EndChange
import eu.advantly.core.model.Environment
import eu.advantly.core.model.GameFormat
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchFormat
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.MatchMode
import eu.advantly.core.model.MatchTiebreakFormat
import eu.advantly.core.model.MidGameEndChange
import eu.advantly.core.model.PickleballFormat
import eu.advantly.core.model.PickleballScoring
import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.RegularSet
import eu.advantly.core.model.SetFormat
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.model.Surface
import eu.advantly.core.model.TiebreakFormat
import eu.advantly.core.scoring.LineupValidator
import eu.advantly.core.scoring.MatchFormatValidator
import eu.advantly.core.scoring.Presets
import eu.advantly.presentation.match.LocalDay
import eu.advantly.presentation.match.MatchSetup
import eu.advantly.presentation.match.PlayersPort
import eu.advantly.presentation.match.ScorerOption
import eu.advantly.presentation.match.WatchStatus
import eu.advantly.presentation.match.systemLocalDay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

/**
 * Match-Assistent (04 §3.1, H2–H5): Sportart → Belag/Umgebung (Tennis, Badminton) → Spieler → Format → Wer zählt?
 * → Start.
 * Bildet die Eingaben nach 02 §5.1/§5.1.1 auf ein `MatchFormat` ab und prüft es mit `MatchFormatValidator` und
 * `LineupValidator`. Defaults: Tennis `TENNIS_SINGLES_BO3`, Padel Golden Point (U1), Pickleball Doppel bis 11,
 * Badminton nach Datum (U11), Tischtennis ohne Satzanzahl (Pflichtauswahl, U14).
 *
 * Nur vom UI-Thread benutzen.
 *
 * @param watchStatus Stand der eigenen Uhr (`LiveMatchPort.watchStatus`); die UI meldet Änderungen über
 *   [setWatchStatus], damit der Standard „Meine Uhr“ nachzieht
 * @param refreshWatch fragt den Stand der Uhr neu ab (`LiveMatchPort.refreshWatchStatus`)
 */
class MatchWizard(
    private val players: PlayersPort,
    private val today: () -> LocalDay = ::systemLocalDay,
    private val random: Random = Random.Default,
    val watchStatus: StateFlow<WatchStatus> = MutableStateFlow(WatchStatus.UNKNOWN),
    private val refreshWatch: () -> Unit = {},
) {
    private val mutableState = MutableStateFlow(derive(initialInput()))
    val state: StateFlow<WizardUiState> = mutableState.asStateFlow()

    private val input: WizardInput get() = mutableState.value.input

    /** Bekannte Gäste (zuletzt verwendete zuerst) für die Spielerauswahl. */
    val guests: StateFlow<List<Player>> get() = players.guests

    /** Setzt den Assistenten auf Schritt 1 zurück. */
    fun reset() = set(initialInput())

    // --- Schritt 1: Sportart ---

    /** Wählt [sport] mit Defaults und springt weiter (Tap = Auswahl + weiter, H2). */
    fun selectSport(sport: Sport) {
        val base = initialInput().copy(sport = sport)
        val withDefaults =
            when (sport) {
                Sport.TENNIS -> {
                    base.copy(environment = Environment.OUTDOOR, sets = SetsChoice.BestOf(3), gamesPerSet = 6)
                }

                Sport.PADEL -> {
                    base.copy(sets = SetsChoice.BestOf(3), gamesPerSet = 6, deuceMode = DeuceMode.DECIDING_POINT).withDiscipline(
                        Discipline.DOUBLES,
                    )
                }

                Sport.PICKLEBALL -> {
                    base.copy(sets = SetsChoice.BestOf(1), pointsToWin = 11).withDiscipline(Discipline.DOUBLES)
                }

                Sport.BADMINTON -> {
                    val day = today()
                    base.copy(
                        environment = Environment.INDOOR,
                        sets = SetsChoice.BestOf(3),
                        pointsToWin = Presets.badmintonDefaultPointsToWin(day.year, day.month, day.day),
                    )
                }

                Sport.TABLE_TENNIS -> {
                    base.copy(sets = null, pointsToWin = 11)
                }
            }
        val steps = stepsFor(sport)
        set(withDefaults.copy(step = steps[1]))
    }

    // --- Schritt 2: Belag / Umgebung ---

    /** Belag wählen (nur Tennis) und weiter. */
    fun selectSurface(surface: Surface?) {
        update { it.copy(surface = surface) }
        next()
    }

    fun setEnvironment(environment: Environment?) = update { it.copy(environment = environment) }

    // --- Schritt 3: Spieler ---

    fun setDiscipline(discipline: Discipline) {
        if (input.sport == Sport.PADEL && discipline != Discipline.DOUBLES) return
        update { it.withDiscipline(discipline) }
    }

    /** Belegt den nächsten freien Slot (Gegner zuerst, dann Partner) mit [player]; schon gesetzte Spieler: No-op. */
    fun pickPlayer(player: Player) {
        if (input.players.any { it.id == player.id }) return
        val slot =
            listOf(Side.B to 0, Side.A to 1, Side.B to 1).firstOrNull { (side, index) ->
                index < input.team(side).size && input.team(side)[index] == null
            } ?: return
        setSlot(slot.first, slot.second, player)
    }

    /** Legt einen Gast an und setzt ihn in den nächsten freien Slot. Leere Namen werden ignoriert. */
    fun addGuest(name: String): Player? {
        if (name.isBlank()) return null
        val guest = players.addGuest(name)
        pickPlayer(guest)
        return guest
    }

    /** Leert den Slot (der eigene Slot A0 bleibt belegt). */
    fun clearSlot(
        side: Side,
        index: Int,
    ) {
        if (side == Side.A && index == 0) return
        setSlot(side, index, null)
    }

    /** Tauscht die Reihenfolge eines Doppel-Teams (Aufschlagreihenfolge bzw. Startaufstellung, 02 §3.3). */
    fun swapOrder(side: Side) =
        update {
            if (it.team(side).size < 2) {
                it
            } else if (side == Side.A) {
                it.copy(teamA = it.teamA.reversed())
            } else {
                it.copy(teamB = it.teamB.reversed())
            }
        }

    fun setFirstServer(player: PlayerId) = update { it.copy(firstServer = player) }

    /** Münzwurf: zufällige Side, deren erster Spieler aufschlägt. */
    fun coinToss(): PlayerId? {
        val side = if (random.nextBoolean()) Side.A else Side.B
        val player = input.team(side).firstOrNull() ?: return null
        setFirstServer(player.id)
        return player.id
    }

    fun setMixed(mixed: Boolean) = update { it.copy(mixed = mixed) }

    // --- Schritt 4: Format ---

    fun setSets(choice: SetsChoice) =
        update {
            // Badminton 11 Punkte gibt es nur als Best of 5 (02 §5.1.1); andere Satzanzahl → Punkte nach Datum
            if (it.sport == Sport.BADMINTON && it.pointsToWin == 11 && choice != SetsChoice.BestOf(5)) {
                val day = today()
                return@update it.copy(sets = choice, pointsToWin = Presets.badmintonDefaultPointsToWin(day.year, day.month, day.day))
            }
            val pbMid =
                if (it.sport == Sport.PICKLEBALL && !it.midGameTouched) {
                    if (choice == SetsChoice.Open) MidGameEndChange.EVERY_GAME else MidGameEndChange.DECIDING_GAME_ONLY
                } else {
                    it.pickleballMidGame
                }
            it.copy(sets = choice, pickleballMidGame = pbMid)
        }

    /** Games pro Satz; der Tiebreak folgt automatisch (n:n, 02 §5.1 „abhängige Defaults“). */
    fun setGamesPerSet(games: Int) = update { it.copy(gamesPerSet = games, tiebreakAt = TiebreakAtChoice.AT_GAMES) }

    fun setTiebreakAt(choice: TiebreakAtChoice) = update { it.copy(tiebreakAt = choice) }

    fun setDeuceMode(mode: DeuceMode) = update { it.copy(deuceMode = mode, deuceTouched = true) }

    /** Entscheidungssatz; bei Tennis-Doppel + Match-Tiebreak wird No-Ad vorgeschlagen, nicht erzwungen (02 §5.1). */
    fun setDecidingSet(choice: DecidingSetChoice) =
        update {
            val suggestNoAd =
                choice == DecidingSetChoice.MATCH_TIEBREAK &&
                    it.sport == Sport.TENNIS &&
                    it.discipline == Discipline.DOUBLES &&
                    !it.deuceTouched
            it.copy(decidingSet = choice, deuceMode = if (suggestNoAd) DeuceMode.DECIDING_POINT else it.deuceMode)
        }

    /** Punkte pro Spiel/Satz. Badminton 11 gibt es nur als Best of 5 (02 §5.1.1). */
    fun setPoints(points: Int) =
        update {
            val sets =
                when {
                    it.sport == Sport.BADMINTON && points == 11 -> SetsChoice.BestOf(5)
                    it.sport == Sport.BADMINTON && it.pointsToWin == 11 -> SetsChoice.BestOf(3)
                    else -> it.sets
                }
            it.copy(pointsToWin = points, sets = sets)
        }

    fun setPickleballScoring(scoring: PickleballScoring) = update { it.copy(pickleballScoring = scoring) }

    fun setPickleballWinBy(winBy: Int) = update { it.copy(pickleballWinBy = winBy) }

    fun setPickleballMidGame(mid: MidGameEndChange) = update { it.copy(pickleballMidGame = mid, midGameTouched = true) }

    fun toggleAdvanced() = update { it.copy(advancedExpanded = !it.advancedExpanded) }

    fun setSaveAsFavorite(save: Boolean) = update { it.copy(saveAsFavorite = save) }

    // --- Schritt 5: Wer zählt? ---

    /** Zähler wählen; „Meine Uhr“ nur, wenn eine Uhr verbunden ist (sonst No-op). */
    fun setScorer(option: ScorerOption) {
        if (option == ScorerOption.MY_WATCH && !input.watchAvailable) return
        update { it.copy(scorer = option) }
    }

    fun setWatchWorkout(enabled: Boolean) = update { it.copy(watchWorkout = enabled) }

    /** Stand der Uhr geändert (aus [watchStatus]); eine explizite Wahl bleibt erhalten. */
    fun setWatchStatus(status: WatchStatus) {
        if (input.watchStatus != status) update { it.copy(watchStatus = status) }
    }

    /** Kurzform für Tests: Uhr verfügbar bzw. unbekannt. */
    fun setWatchAvailable(available: Boolean) = setWatchStatus(if (available) WatchStatus.AVAILABLE else WatchStatus.UNKNOWN)

    /** „Erneut prüfen“ bzw. Betreten des Schritts „Wer zählt?“: Stand der Uhr neu abfragen. */
    fun refreshWatchStatus() {
        refreshWatch()
        setWatchStatus(watchStatus.value)
    }

    // --- Navigation ---

    /** Nächster Schritt, wenn [WizardUiState.canContinue]; im letzten Schritt No-op. */
    fun next(): Boolean {
        val s = mutableState.value
        if (!s.canContinue) return false
        val nextStep = s.steps.getOrNull(s.stepIndex + 1) ?: return false
        update { it.copy(step = nextStep) }
        // „Wer zählt?“: Stand der Uhr bei jedem Betreten neu abfragen (Uhr-App evtl. inzwischen geöffnet)
        if (nextStep == WizardStep.SCORER) refreshWatchStatus()
        return true
    }

    /** Ein Schritt zurück; `false` im ersten Schritt (dann schließt die UI den Assistenten). */
    fun back(): Boolean {
        val s = mutableState.value
        val previous = s.steps.getOrNull(s.stepIndex - 1) ?: return false
        update { it.copy(step = previous) }
        return true
    }

    /** Fertiges Setup (oder `null`); speichert es auf Wunsch als Favoriten. */
    fun finish(): MatchSetup? {
        val setup = mutableState.value.setup ?: return null
        if (input.saveAsFavorite) players.saveFavorite(setup)
        return setup
    }

    // --- intern ---

    private fun initialInput(): WizardInput =
        WizardInput(teamA = listOf(players.self.value), teamB = listOf(null), watchStatus = watchStatus.value)

    private fun setSlot(
        side: Side,
        index: Int,
        player: Player?,
    ) = update {
        val team = it.team(side).toMutableList().also { t -> t[index] = player }
        if (side == Side.A) it.copy(teamA = team) else it.copy(teamB = team)
    }

    private fun update(block: (WizardInput) -> WizardInput) = set(block(input))

    private fun set(newInput: WizardInput) {
        mutableState.value = derive(newInput)
    }

    private fun derive(input: WizardInput): WizardUiState {
        val selfId = players.self.value.id
        val sport = input.sport
        val options =
            sport?.let(::optionsFor)?.let { o ->
                // Badminton 11 Punkte: nur Best of 5 wählbar (02 §5.1.1)
                if (sport == Sport.BADMINTON && input.pointsToWin == 11) o.copy(sets = listOf(SetsChoice.BestOf(5))) else o
            } ?: EMPTY_OPTIONS
        val format = sport?.let { buildFormat(input) }
        val validation = format?.let(MatchFormatValidator::validate)
        val lineup = if (format != null) buildLineup(input, selfId) else null
        val lineupErrors = if (format != null && lineup != null) LineupValidator.validate(format, lineup) else emptyList()
        val presetId = format?.let { f -> recognizePreset(f) }
        val setup =
            if (format != null && lineup != null && validation?.isValid == true && lineupErrors.isEmpty()) {
                MatchSetup(
                    format = format.copy(presetId = presetId),
                    lineup = lineup,
                    meta = MatchMeta(surface = input.surface, environment = input.environment, mixed = input.mixed),
                    players = input.players,
                )
            } else {
                null
            }
        return WizardUiState(
            input = input,
            steps = sport?.let(::stepsFor) ?: listOf(WizardStep.SPORT, WizardStep.PLAYERS, WizardStep.FORMAT, WizardStep.SCORER),
            options = options,
            format = format?.copy(presetId = presetId),
            validation = validation,
            lineupErrors = lineupErrors,
            presetId = presetId,
            setup = setup,
            selfId = selfId,
        )
    }

    companion object {
        private val EMPTY_OPTIONS =
            FormatOptions(
                sets = emptyList(),
                gamesPerSet = emptyList(),
                points = emptyList(),
                advancedPoints = emptyList(),
                deuceModes = emptyList(),
                decidingSets = emptyList(),
                tiebreakAt = emptyList(),
                pickleballScoring = emptyList(),
                pickleballWinBy = emptyList(),
                pickleballMidGame = emptyList(),
                disciplineSelectable = true,
                mixedSelectable = false,
            )

        private fun bestOf(vararg n: Int): List<SetsChoice> = n.map { SetsChoice.BestOf(it) }

        /** Schritte je Sportart: Belag nur bei Tennis, Umgebung (Halle/Draußen) bei Badminton; „Wer zählt?“ immer zuletzt. */
        fun stepsFor(sport: Sport): List<WizardStep> =
            if (sport == Sport.TENNIS || sport == Sport.BADMINTON) {
                listOf(WizardStep.SPORT, WizardStep.SURFACE, WizardStep.PLAYERS, WizardStep.FORMAT, WizardStep.SCORER)
            } else {
                listOf(WizardStep.SPORT, WizardStep.PLAYERS, WizardStep.FORMAT, WizardStep.SCORER)
            }

        /** Auswahl je Sportart nach 02 §5.1 und §5.1.1. */
        fun optionsFor(sport: Sport): FormatOptions =
            when (sport) {
                Sport.TENNIS -> {
                    EMPTY_OPTIONS.copy(
                        sets = bestOf(1, 3, 5) + SetsChoice.Open,
                        gamesPerSet = listOf(4, 6, 8, 10),
                        deuceModes = listOf(DeuceMode.ADVANTAGE, DeuceMode.DECIDING_POINT),
                        decidingSets = DecidingSetChoice.entries,
                        tiebreakAt = TiebreakAtChoice.entries,
                        mixedSelectable = true,
                    )
                }

                Sport.PADEL -> {
                    EMPTY_OPTIONS.copy(
                        sets = bestOf(1, 3) + SetsChoice.Open,
                        gamesPerSet = listOf(4, 6),
                        deuceModes = listOf(DeuceMode.DECIDING_POINT, DeuceMode.STAR_POINT, DeuceMode.ADVANTAGE),
                        decidingSets = listOf(DecidingSetChoice.NORMAL, DecidingSetChoice.MATCH_TIEBREAK, DecidingSetChoice.ADVANTAGE_SET),
                        disciplineSelectable = false,
                        mixedSelectable = true,
                    )
                }

                Sport.PICKLEBALL -> {
                    EMPTY_OPTIONS.copy(
                        sets = bestOf(1, 3, 5) + SetsChoice.Open,
                        points = listOf(11, 15, 21),
                        pickleballScoring = PickleballScoring.entries,
                        pickleballWinBy = listOf(2, 1),
                        pickleballMidGame = MidGameEndChange.entries,
                    )
                }

                Sport.BADMINTON -> {
                    EMPTY_OPTIONS.copy(
                        sets = bestOf(1, 3) + SetsChoice.Open,
                        points = listOf(15, 21),
                        advancedPoints = listOf(11),
                        mixedSelectable = true,
                    )
                }

                Sport.TABLE_TENNIS -> {
                    EMPTY_OPTIONS.copy(sets = bestOf(3, 5, 7) + SetsChoice.Open, points = listOf(11, 21))
                }
            }

        /** Baut das Format aus den Eingaben; `null`, solange eine Pflichtauswahl fehlt. */
        fun buildFormat(input: WizardInput): MatchFormat? {
            val sport = input.sport ?: return null
            val sets = input.sets ?: return null
            val d = input.discipline
            return when (sport) {
                Sport.TENNIS, Sport.PADEL -> {
                    tennisLikeFormat(sport, input, sets)
                }

                Sport.PICKLEBALL -> {
                    MatchFormat(
                        sport = sport,
                        discipline = d,
                        match = sets.toSettings(),
                        pickleball =
                            PickleballFormat(
                                scoring = input.pickleballScoring,
                                pointsToWin = input.pointsToWin,
                                winBy = input.pickleballWinBy,
                                midGameEndChange = input.pickleballMidGame,
                            ),
                    )
                }

                Sport.BADMINTON -> {
                    val preset =
                        when (input.pointsToWin) {
                            21 -> Presets.BADMINTON_1GAME_21
                            11 -> Presets.BADMINTON_BO5_11
                            else -> Presets.BADMINTON_1GAME_15
                        }
                    MatchFormat(sport = sport, discipline = d, match = sets.toSettings(), rally = preset.format(d).rally)
                }

                Sport.TABLE_TENNIS -> {
                    val preset = if (input.pointsToWin == 21) Presets.TABLE_TENNIS_LEISURE_21 else Presets.TABLE_TENNIS_OPEN
                    MatchFormat(sport = sport, discipline = d, match = sets.toSettings(), rally = preset.format(d).rally)
                }
            }
        }

        private fun tennisLikeFormat(
            sport: Sport,
            input: WizardInput,
            sets: SetsChoice,
        ): MatchFormat {
            val games = input.gamesPerSet
            val tiebreakAt =
                when (input.tiebreakAt) {
                    TiebreakAtChoice.AT_GAMES -> games
                    TiebreakAtChoice.AT_GAMES_MINUS_ONE -> games - 1
                    TiebreakAtChoice.NONE -> null
                }
            val set = RegularSet(games, tiebreakAt, TiebreakFormat.TB7)
            val open = sets == SetsChoice.Open
            val decidingSet: SetFormat? =
                if (open) {
                    null
                } else {
                    when (input.decidingSet) {
                        DecidingSetChoice.NORMAL -> null
                        DecidingSetChoice.MATCH_TIEBREAK -> MatchTiebreakFormat.MTB10
                        DecidingSetChoice.ADVANTAGE_SET -> RegularSet(games, null, TiebreakFormat.TB7)
                        DecidingSetChoice.TIEBREAK_TO_10 -> RegularSet(games, tiebreakAt ?: games, TiebreakFormat(10, 2, EndChange(6, 6)))
                    }
                }
            val mode = input.deuceMode
            return MatchFormat(
                sport = sport,
                discipline = input.discipline,
                match = sets.toSettings(),
                game = GameFormat(mode, if (mode == DeuceMode.STAR_POINT) GameFormat.DEFAULT_MAX_ADVANTAGES else null),
                set = set,
                decidingSet = decidingSet,
                // Offen: „Match-Tiebreak“ wird im Satzende-Dialog angeboten (02 §5.2)
                matchTiebreak = if (open) MatchTiebreakFormat.MTB10 else null,
            )
        }

        private fun buildLineup(
            input: WizardInput,
            selfId: PlayerId,
        ): Lineup? {
            val a = input.teamA.map { it?.id ?: return null }
            val b = input.teamB.map { it?.id ?: return null }
            val server = input.firstServer?.takeIf { it in a || it in b } ?: selfId.takeIf { it in a } ?: a.first()
            return Lineup(a = a, b = b, firstServer = server)
        }

        /** Erkennt ein Preset aus 02 §4 mit identischem Format (nur Anzeige/Statistik). */
        fun recognizePreset(format: MatchFormat): String? {
            val plain = format.copy(presetId = null)
            return Presets.all
                .firstOrNull { p ->
                    p.sport == format.sport && format.discipline in p.disciplines &&
                        p.format(format.discipline).copy(presetId = null) == plain
                }?.id
                ?: if (format.sport == Sport.BADMINTON && format.match.mode == MatchMode.OPEN) "BADMINTON_OPEN" else null
        }

        private fun WizardInput.withDiscipline(discipline: Discipline): WizardInput {
            val size = discipline.playersPerSide

            fun resize(team: List<Player?>) = List(size) { team.getOrNull(it) }
            val a = resize(teamA)
            val b = resize(teamB)
            val stillThere = firstServer?.takeIf { id -> (a + b).any { it?.id == id } }
            return copy(discipline = discipline, teamA = a, teamB = b, firstServer = stillThere, mixed = mixed && size == 2)
        }
    }
}
