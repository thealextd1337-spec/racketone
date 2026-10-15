package eu.advantly.umbrella.watch

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.Discipline
import eu.advantly.core.model.EventId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchFormat
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMode
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.model.Uuid7
import eu.advantly.core.scoring.Callout
import eu.advantly.core.scoring.ContinueDecision
import eu.advantly.core.scoring.Effect
import eu.advantly.core.scoring.EndReason
import eu.advantly.core.scoring.EngineState
import eu.advantly.core.scoring.EventPayload
import eu.advantly.core.scoring.FlagLevel
import eu.advantly.core.scoring.GameKind
import eu.advantly.core.scoring.MatchCreated
import eu.advantly.core.scoring.MatchEnded
import eu.advantly.core.scoring.MatchFormatValidator
import eu.advantly.core.scoring.MatchState
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.core.scoring.NextSetKind
import eu.advantly.core.scoring.Paused
import eu.advantly.core.scoring.PointWon
import eu.advantly.core.scoring.Preset
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.Resumed
import eu.advantly.core.scoring.ScoreEvent
import eu.advantly.core.scoring.ScoringEngine
import eu.advantly.core.scoring.SetKind
import eu.advantly.core.scoring.Undo
import eu.advantly.data.AppendResult
import eu.advantly.data.EventStore
import eu.advantly.data.StoredEvent
import kotlinx.serialization.json.Json
import kotlin.random.Random

/**
 * Swift-Facade für das eigenständige Zählen auf der Apple Watch (W1-E). Ohne SKIE (CLAUDE.md Regel 4): an der Grenze nur
 * String/Int/Long/Boolean, Listen und flache Klassen; Fehler werden nie als Exception nach Swift geworfen, sondern als
 * `null`/`false` gemeldet.
 *
 * - Ein Match lebt ausschließlich im Event-Log ([EventStore], SQLite-Datei `advantly.db`): Genesis `MatchCreated`, danach
 *   `PointWon`/`Undo`/`ContinueDecision`/`MatchEnded`/`Paused`/`Resumed`. Nach einem App-Neustart stellt [restore] den Zustand per Replay wieder her.
 * - Side A = „Wir“ (Besitzer der Uhr), Side B = „Gegner“. Die Engine bleibt neutral (A/B), die Perspektive ist reine Anzeige.
 * - Nicht thread-sicher: Swift ruft alles auf der Main-Queue auf; [WatchScoringListener] wird synchron im selben Aufruf benachrichtigt.
 * - Sync mit dem Handy ist nicht Teil dieser Facade (folgt in der Integration über `sync-protocol`/`device-link`).
 *
 * Swift: `let scoring = AdvantlyWatch.shared.scoring(); scoring.startMatch(presetId: "TENNIS_SINGLES_BO3", doubles: false, weServeFirst: true)`.
 */
class WatchScoring internal constructor(
    private val store: EventStore,
    private val prefs: WatchPrefs,
    private val now: () -> Long = ::platformNowMs,
    private val random: Random = Random.Default,
) {
    private var listener: WatchScoringListener? = null
    private var active: ActiveMatch? = null
    private var error: String = ""

    private class ActiveMatch(
        val matchId: MatchId,
        var engine: EngineState,
        var nextDeviceSeq: Long,
        var lamport: Long,
        val startedAtMs: Long,
    )

    /** Ein Listener; `null` meldet ab. Wird nach jedem erfolgreichen Befehl mit dem neuen Zustand aufgerufen. */
    fun setListener(listener: WatchScoringListener?) {
        this.listener = listener
    }

    /**
     * Grund, warum der letzte Befehl `null` geliefert hat, sonst `""` (wird bei jedem Befehl zurückgesetzt):
     * - `STORE_WRITE`: Event konnte nicht gespeichert werden – Zustand unverändert, derselbe Befehl kann wiederholt werden
     * - `STORE_READ`: Log nicht lesbar (z. B. SQLite-Fehler) – Zeiger auf das Match bleibt, [restore] später erneut
     * - `LOG_INVALID`: Log vorhanden, aber nicht auswertbar – Match wird verworfen
     * - `INVALID_INPUT`: unbekanntes Preset, falsche Disziplin oder ungültiges Format
     * - `NOT_ALLOWED`: Befehl passt nicht zum Zustand (z. B. Punkt im Satzende-Dialog, nichts rückgängig zu machen)
     * - `NO_MATCH`: kein Match geöffnet
     */
    fun lastError(): String = error

    /** Stabile Installations-ID der Uhr (UUIDv7, beim ersten Aufruf erzeugt und gespeichert). */
    fun deviceId(): String = prefs.get(KEY_DEVICE_ID) ?: newUuid().value.also { prefs.set(KEY_DEVICE_ID, it) }

    // --- Auswahl (Start-Screen) -----------------------------------------------------------------------------------

    /** Die fünf Sportarten in Anzeige-Reihenfolge (`TENNIS`, `PADEL`, `PICKLEBALL`, `BADMINTON`, `TABLE_TENNIS`). */
    fun sports(): List<String> = Sport.entries.map { it.name }

    /**
     * Presets einer Sportart; das Wizard-Default steht vorn ([WatchPreset.isDefault]). Badminton wählt das Default nach
     * dem lokalen Datum (02 §4.4), deshalb kommen Jahr/Monat/Tag von Swift. Unbekannte Sportart → leere Liste.
     */
    fun presets(
        sport: String,
        year: Int,
        month: Int,
        day: Int,
    ): List<WatchPreset> {
        val s = sportOf(sport) ?: return emptyList()
        val defaultId = defaultPresetId(sport, year, month, day)
        return Presets.all
            .filter { it.sport == s }
            .map { it.toWatchPreset(isDefault = it.id == defaultId) }
            .sortedByDescending { it.isDefault }
    }

    /** Wizard-Default (02 §5.1, §4.4); Tischtennis hat bewusst keines (U14) → `""`. */
    fun defaultPresetId(
        sport: String,
        year: Int,
        month: Int,
        day: Int,
    ): String {
        val s = sportOf(sport) ?: return ""
        if (s == Sport.BADMINTON) {
            val points = Presets.badmintonDefaultPointsToWin(year, month, day)
            return if (points == 21) Presets.BADMINTON_SINGLES_BO3_21.id else Presets.BADMINTON_SINGLES_BO3_15.id
        }
        return Presets.wizardDefault(s)?.id.orEmpty()
    }

    /** Letzte Auswahl für „Wie letztes Mal“ oder `null`. */
    fun lastSetup(): WatchMatchSetup? {
        val parts = prefs.get(KEY_LAST_SETUP)?.split(';') ?: return null
        if (parts.size != 3) return null
        val preset = Presets.byId(parts[0]) ?: return null
        return WatchMatchSetup(preset.id, preset.sport.name, parts[1] == "1", parts[2] == "1")
    }

    // --- Lebenszyklus ---------------------------------------------------------------------------------------------

    /**
     * Startet ein neues Match aus einem Preset. Lineup: Einzel `A1` vs. `B1`, Doppel `A1,A2` vs. `B1,B2`; [weServeFirst]
     * wählt `A1` bzw. `B1` als Erstaufschläger. Ein noch laufendes Match wird damit verlassen (es bleibt im Log).
     *
     * @return Zustand nach dem Start oder `null` (unbekanntes Preset, Disziplin nicht erlaubt, Speicherfehler)
     */
    fun startMatch(
        presetId: String,
        doubles: Boolean,
        weServeFirst: Boolean,
    ): WatchScoreSnapshot? {
        error = ""
        val preset = Presets.byId(presetId) ?: return fail(INVALID_INPUT)
        val discipline = if (doubles) Discipline.DOUBLES else Discipline.SINGLES
        if (discipline !in preset.disciplines) return fail(INVALID_INPUT)
        val snapshot = begin(preset.format(discipline), weServeFirst) ?: return null
        prefs.set(KEY_LAST_SETUP, listOf(preset.id, flag(doubles), flag(weServeFirst)).joinToString(";"))
        return snapshot
    }

    /**
     * Startet ein Match aus einem `MatchFormat`-JSON (02 §3.2), z. B. vom Handy-Wizard. Die Disziplin steht im Format.
     * Ungültiges JSON oder Format → `null`.
     */
    fun startMatchFromFormatJson(
        formatJson: String,
        weServeFirst: Boolean,
    ): WatchScoreSnapshot? {
        error = ""
        val format =
            try {
                eventJson.decodeFromString(MatchFormat.serializer(), formatJson)
            } catch (e: IllegalArgumentException) {
                // SerializationException ist eine IllegalArgumentException
                return fail(INVALID_INPUT)
            }
        if (MatchFormatValidator.validate(format).errors.isNotEmpty()) return fail(INVALID_INPUT)
        return begin(format, weServeFirst)
    }

    /**
     * Stellt das zuletzt geöffnete Match nach einem App-Neustart wieder her (Replay des Event-Logs).
     * `null`, wenn es keines gibt (`""`), der Store nicht lesbar ist (`STORE_READ`, Zeiger bleibt für einen neuen Versuch)
     * oder das Log nicht auswertbar ist (`LOG_INVALID`, Zeiger wird verworfen).
     */
    fun restore(): WatchScoreSnapshot? {
        error = ""
        active?.let { return notify(it) }
        val matchId = prefs.get(KEY_ACTIVE_MATCH) ?: return null
        val stored =
            try {
                store.eventsFor(matchId)
            } catch (e: Exception) {
                return fail(STORE_READ)
            }
        val loaded = load(matchId, stored)
        if (loaded == null) {
            prefs.set(KEY_ACTIVE_MATCH, null)
            return fail(LOG_INVALID)
        }
        active = loaded
        return notify(loaded)
    }

    /** `true`, solange ein Match geöffnet ist (auch beendet, bis [close]). */
    fun hasActiveMatch(): Boolean = active != null || prefs.get(KEY_ACTIVE_MATCH) != null

    /** ID des geöffneten Matches oder `""`. */
    fun activeMatchId(): String = active?.matchId?.value ?: prefs.get(KEY_ACTIVE_MATCH).orEmpty()

    /** Aktueller Zustand des geöffneten Matches oder `null`. */
    fun snapshot(): WatchScoreSnapshot? = active?.let { snapshotOf(it) }

    /** Schließt das Match in der Anzeige (z. B. nach der Matchende-Ansicht). Das Log bleibt erhalten. */
    fun close() {
        active = null
        prefs.set(KEY_ACTIVE_MATCH, null)
    }

    // --- Befehle --------------------------------------------------------------------------------------------------

    /** Punkt für Side A (`true`, „Wir“) bzw. B. Nur im laufenden bzw. pausierten Satz; sonst `null`. */
    fun point(sideA: Boolean): WatchScoreSnapshot? {
        error = ""
        val match = active ?: return fail(NO_MATCH)
        val status = match.engine.state.status
        if (status != MatchStatus.IN_PROGRESS && status != MatchStatus.PAUSED) return fail(NOT_ALLOWED)
        return emit(match, PointWon(if (sideA) Side.A else Side.B))
    }

    /** Macht das letzte wirksame Zähl-Event rückgängig (auch über Satz- und Matchende hinweg). `null`, wenn nichts da ist. */
    fun undo(): WatchScoreSnapshot? {
        error = ""
        val match = active ?: return fail(NO_MATCH)
        val target = match.engine.undoTarget ?: return fail(NOT_ALLOWED)
        return emit(match, Undo(target))
    }

    /**
     * Antwort auf „Noch ein Satz?“ (nur bei `pendingDecision`). [matchTiebreak] wählt einen Match-Tiebreak statt eines
     * regulären Satzes, sofern das Format ihn anbietet ([WatchScoreSnapshot.offerMatchTiebreak]).
     */
    fun continueDecision(
        anotherSet: Boolean,
        matchTiebreak: Boolean,
    ): WatchScoreSnapshot? {
        error = ""
        val match = active ?: return fail(NO_MATCH)
        if (match.engine.state.status != MatchStatus.AWAITING_DECISION) return fail(NOT_ALLOWED)
        val kind =
            if (anotherSet && matchTiebreak && match.engine.format.matchTiebreak != null) {
                NextSetKind.MATCH_TIEBREAK
            } else {
                NextSetKind.REGULAR
            }
        return emit(match, ContinueDecision(anotherSet, kind))
    }

    /**
     * Beendet das Match: beim offenen Satzende-Dialog wie „Nein“ (reguläres Ergebnis), sonst vorzeitig (`ABANDONED`).
     * Bereits beendet → aktueller Zustand. Rückgängig machbar über [undo].
     */
    fun end(): WatchScoreSnapshot? {
        error = ""
        val match = active ?: return fail(NO_MATCH)
        return when (match.engine.state.status) {
            MatchStatus.FINISHED -> notify(match)
            MatchStatus.AWAITING_DECISION -> emit(match, ContinueDecision(anotherSet = false))
            else -> emit(match, MatchEnded(EndReason.ABANDONED))
        }
    }

    /**
     * Unterbrechung (Regen, Verletzung; 02 §7 `Paused`) ohne Wirkung auf den Score, nur im laufenden Satz. Die Uhr
     * pausiert dazu die Workout-Session. Seitenwechsel und Satzende-Dialog sind **keine** Pause.
     */
    fun pause(): WatchScoreSnapshot? {
        error = ""
        val match = active ?: return fail(NO_MATCH)
        if (match.engine.state.status != MatchStatus.IN_PROGRESS) return fail(NOT_ALLOWED)
        return emit(match, Paused)
    }

    /** Fortsetzung nach [pause]. Ein Punkt während der Pause setzt ebenfalls fort (Engine-Regel). */
    fun resumePlay(): WatchScoreSnapshot? {
        error = ""
        val match = active ?: return fail(NO_MATCH)
        if (match.engine.state.status != MatchStatus.PAUSED) return fail(NOT_ALLOWED)
        return emit(match, Resumed)
    }

    // --- intern ---------------------------------------------------------------------------------------------------

    private fun begin(
        format: MatchFormat,
        weServeFirst: Boolean,
    ): WatchScoreSnapshot? {
        val lineup = lineupFor(format.discipline, weServeFirst)
        val time = now()
        val matchId = MatchId(newUuid(time).value)
        val genesis =
            ScoreEvent(
                id = EventId(newUuid(time).value),
                matchId = matchId,
                deviceId = DeviceId(deviceId()),
                deviceSeq = 1,
                lamport = 1,
                wallClockMs = time,
                parentId = null,
                payload = MatchCreated(format, lineup),
            )
        val engine =
            try {
                ScoringEngine.replay(listOf(genesis))
            } catch (e: IllegalArgumentException) {
                return fail(INVALID_INPUT)
            }
        if (!append(genesis)) return fail(STORE_WRITE)
        val match = ActiveMatch(matchId, engine, nextDeviceSeq = 2, lamport = 1, startedAtMs = time)
        active = match
        prefs.set(KEY_ACTIVE_MATCH, matchId.value)
        return notify(match)
    }

    private fun emit(
        match: ActiveMatch,
        payload: EventPayload,
    ): WatchScoreSnapshot? {
        val time = now()
        val event =
            ScoreEvent(
                id = EventId(newUuid(time).value),
                matchId = match.matchId,
                deviceId = DeviceId(deviceId()),
                deviceSeq = match.nextDeviceSeq,
                lamport = match.lamport + 1,
                wallClockMs = time,
                parentId =
                    match.engine.events
                        .lastOrNull()
                        ?.id ?: match.engine.genesis?.id,
                payload = payload,
            )
        // Erst speichern, dann anwenden: Was die Anzeige zeigt, überlebt jeden Absturz. Schlägt das Speichern fehl,
        // bleibt der Zustand (inkl. deviceSeq/lamport) unverändert – derselbe Befehl kann wiederholt werden.
        if (!append(event)) return fail(STORE_WRITE)
        match.nextDeviceSeq += 1
        match.lamport = event.lamport
        match.engine =
            try {
                ScoringEngine.apply(match.engine, event)
            } catch (e: IllegalArgumentException) {
                // Bei eigenen Events nicht zu erwarten; das Event steht im Log und wirkt beim nächsten Replay
                return fail(LOG_INVALID)
            }
        return notify(match)
    }

    /** `true`, wenn neu gespeichert. SQLite-/Treiber-Ausnahmen dürfen nie über die Swift-Grenze laufen. */
    private fun append(event: ScoreEvent): Boolean =
        try {
            store.append(event.toStored()) == AppendResult.INSERTED
        } catch (e: Exception) {
            false
        }

    private fun fail(code: String): WatchScoreSnapshot? {
        error = code
        return null
    }

    private fun load(
        matchId: String,
        stored: List<StoredEvent>,
    ): ActiveMatch? {
        if (stored.isEmpty()) return null
        return try {
            val log = stored.map { eventJson.decodeFromString(ScoreEvent.serializer(), it.payload) }
            val engine = ScoringEngine.replay(log)
            val genesis = engine.genesis ?: return null
            val own = deviceId()
            ActiveMatch(
                matchId = MatchId(matchId),
                engine = engine,
                nextDeviceSeq = (stored.filter { it.deviceId == own }.maxOfOrNull { it.deviceSeq } ?: 0) + 1,
                lamport = stored.maxOf { it.lamport },
                startedAtMs = genesis.wallClockMs,
            )
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun notify(match: ActiveMatch): WatchScoreSnapshot {
        val snapshot = snapshotOf(match)
        listener?.onSnapshot(snapshot)
        return snapshot
    }

    private fun newUuid(time: Long = now()): Uuid7 = Uuid7.fromParts(time.coerceIn(0, (1L shl 48) - 1), random.nextInt(), random.nextLong())

    internal companion object {
        const val KEY_ACTIVE_MATCH = "advantly.watch.activeMatchId"
        const val KEY_DEVICE_ID = "advantly.watch.deviceId"
        const val KEY_LAST_SETUP = "advantly.watch.lastSetup"

        const val STORE_WRITE = "STORE_WRITE"
        const val STORE_READ = "STORE_READ"
        const val LOG_INVALID = "LOG_INVALID"
        const val INVALID_INPUT = "INVALID_INPUT"
        const val NOT_ALLOWED = "NOT_ALLOWED"
        const val NO_MATCH = "NO_MATCH"

        /** Payload-Format im lokalen Store: das ganze [ScoreEvent] als JSON (Diskriminator `type`, wie die Testvektoren). */
        val eventJson: Json =
            Json {
                ignoreUnknownKeys = true
                explicitNulls = true
                encodeDefaults = false
            }

        private fun flag(value: Boolean) = if (value) "1" else "0"

        private fun sportOf(name: String): Sport? = Sport.entries.firstOrNull { it.name == name }

        fun lineupFor(
            discipline: Discipline,
            weServeFirst: Boolean,
        ): Lineup {
            val a = (1..discipline.playersPerSide).map { PlayerId("A$it") }
            val b = (1..discipline.playersPerSide).map { PlayerId("B$it") }
            return Lineup(a, b, firstServer = if (weServeFirst) a.first() else b.first())
        }

        private fun ScoreEvent.toStored() =
            StoredEvent(
                id = id.value,
                matchId = matchId.value,
                deviceId = deviceId.value,
                deviceSeq = deviceSeq,
                lamport = lamport,
                payload = eventJson.encodeToString(ScoreEvent.serializer(), this),
            )

        private fun Preset.toWatchPreset(isDefault: Boolean): WatchPreset {
            val format = format()
            return WatchPreset(
                id = id,
                sport = sport.name,
                singles = Discipline.SINGLES in disciplines,
                doubles = Discipline.DOUBLES in disciplines,
                defaultDoubles = defaultDiscipline == Discipline.DOUBLES,
                openMode = format.match.mode == MatchMode.OPEN,
                bestOf = format.match.bestOf ?: 0,
                isDefault = isDefault,
            )
        }
    }

    private fun snapshotOf(match: ActiveMatch): WatchScoreSnapshot = buildSnapshot(match.matchId.value, match.engine, match.startedAtMs)
}

/** Callback statt Flow (CLAUDE.md Regel 4). */
interface WatchScoringListener {
    fun onSnapshot(snapshot: WatchScoreSnapshot)
}

/**
 * Ein Preset für die Auswahlliste. Anzeigetexte liegen in Swift (`preset.<id>` in Localizable.strings).
 *
 * @property bestOf `0` im offenen Modus
 */
class WatchPreset(
    val id: String,
    val sport: String,
    val singles: Boolean,
    val doubles: Boolean,
    val defaultDoubles: Boolean,
    val openMode: Boolean,
    val bestOf: Int,
    val isDefault: Boolean,
)

/** Letzte Auswahl für „Wie letztes Mal“. */
class WatchMatchSetup(
    val presetId: String,
    val sport: String,
    val doubles: Boolean,
    val weServeFirst: Boolean,
)

/**
 * Flacher Anzeige-Zustand (aus 02 §8 abgeleitet). Nur Daten und Codes, keine UI-Texte – Swift übersetzt über
 * Localizable.strings. Leerer String bzw. `-1`/`0` = „nicht vorhanden“ (statt Optionals, die ohne SKIE unhandlich sind).
 *
 * @property status `IN_PROGRESS`, `PAUSED`, `AWAITING_DECISION` oder `FINISHED`
 * @property winner `A`, `B` oder `""` (offen, Unentschieden, abgebrochen)
 * @property resultReason `COMPLETED`, `DRAW`, `RETIRED`, `WALKOVER`, `ABANDONED` oder `""`
 * @property pointsA Punktanzeige des laufenden Spiels/Satzes (`"0"`, `"15"`, `"AD"`, `"7"` …), `""` ohne laufenden Satz
 * @property gamesA Spiele im laufenden Satz (Tennis/Padel), sonst `-1`
 * @property setKind `REGULAR`, `MATCH_TIEBREAK`, `PB_GAME`, `RALLY_GAME` oder `""`
 * @property gameKind `STANDARD`, `TIEBREAK`, `MATCH_TIEBREAK`, `RALLY_GAME` oder `""`
 * @property setScoresA je abgeschlossenem Satz: Spiele (regulär), Tiebreak-Punkte (Match-Tiebreak) bzw. Punkte (PB/B/TT)
 * @property setTiebreaksA Tiebreak-Punkte eines regulären Satzes mit Tiebreak, sonst `-1`
 * @property setWinners Sieger je abgeschlossenem Satz (`A`/`B`)
 * @property serveSide `A`, `B` oder `""`
 * @property serveCourt `RIGHT`, `LEFT`, `RECEIVER_CHOICE` oder `""` (Tischtennis, kein Aufschlag)
 * @property paused Unterbrechung läuft (`status == PAUSED`)
 * @property server Spieler-ID des Aufschlägers (`A1`, `B2` …) oder `""`
 * @property serverNo Aufschläger als Nummer im Team (1/2, Reihenfolge der Besetzung), sonst `0` – Doppel „2 → 1“
 * @property receiverNo Rückschläger als Nummer im Team (1/2), `0` wenn offen (Entscheidungspunkt) oder ohne Aufschlag
 * @property rightNoA wer von A im rechten Feld steht (1/2; Pickleball/Badminton, 02 §8 `positions`), sonst `0`
 * @property leftNoA wer von A im linken Feld steht (1/2), `0` im Einzel bzw. ohne Positionen
 * @property receiveOrderSwapped Tischtennis-Doppel: Rückschlagreihenfolge hat gewechselt (Effekt `RECEIVE_ORDER_SWAPPED`)
 * @property serverNumber Pickleball-Doppel 1/2, sonst `0`
 * @property servesRemaining Tischtennis: verbleibende Aufschläge inkl. des nächsten, sonst `0`
 * @property scoreCall Ansage (Pickleball `"4-2-1"`), `""` ohne laufenden Satz
 * @property hintCode Hinweis-Pill (Vorrang nach 02 §8.2/04 §4.3.1): `DECIDING_POINT`, `STAR_POINT`, `MATCH_POINT`,
 *   `SET_POINT`, `BREAK_POINT`, `DEUCE`, `MATCH_TIEBREAK`, `TIEBREAK` oder `""`
 * @property hintSide Side der Ball-Hinweise (`A`/`B`), sonst `""`
 * @property hintCount Anzahl („3 Satzbälle“), sonst `0`
 * @property callout `DEUCE`, `ADVANTAGE_SERVER`, `ADVANTAGE_RECEIVER`, `DECIDING_POINT`, `STAR_POINT` oder `""`
 * @property deuceCount Nummer des Einstands (Star Point „Einstand 2“), sonst `0`
 * @property effects Übergänge durch das letzte Event (02 §8.3, Namen) – für Haptik und Toasts
 * @property canUndo es gibt ein Ziel für [WatchScoring.undo]
 * @property startedAtMs Wanduhrzeit des Matchstarts (Dauer-Anzeige)
 */
class WatchScoreSnapshot(
    val matchId: String,
    val sport: String,
    val presetId: String,
    val doubles: Boolean,
    val openMode: Boolean,
    val bestOf: Int,
    val offerMatchTiebreak: Boolean,
    val status: String,
    val pendingDecision: Boolean,
    val matchFinished: Boolean,
    val winner: String,
    val resultReason: String,
    val pointsA: String,
    val pointsB: String,
    val gamesA: Int,
    val gamesB: Int,
    val setIndex: Int,
    val setKind: String,
    val gameKind: String,
    val setsWonA: Int,
    val setsWonB: Int,
    val setScoresA: List<Int>,
    val setScoresB: List<Int>,
    val setTiebreaksA: List<Int>,
    val setTiebreaksB: List<Int>,
    val setWinners: List<String>,
    val paused: Boolean,
    val serveSide: String,
    val serveCourt: String,
    val server: String,
    val receiver: String,
    val serverNo: Int,
    val receiverNo: Int,
    val rightNoA: Int,
    val leftNoA: Int,
    val rightNoB: Int,
    val leftNoB: Int,
    val receiveOrderSwapped: Boolean,
    val serverNumber: Int,
    val servesRemaining: Int,
    val scoreCall: String,
    val hintCode: String,
    val hintSide: String,
    val hintCount: Int,
    val callout: String,
    val deuceCount: Int,
    val effects: List<String>,
    val changeEnds: Boolean,
    val canUndo: Boolean,
    val eventCount: Int,
    val startedAtMs: Long,
)

/** Hinweis-Pill: Code, Side (`A`/`B`/`""`), Anzahl. */
internal data class WatchHint(
    val code: String,
    val side: String,
    val count: Int,
)

/**
 * Hinweis-Pill nach 02 §8.2 („Watch-Anzeige“) und 04 §4.3.1: Entscheidungspunkt/Star Point vor Matchball › Satzball ›
 * Breakball (Spielball nur am Handy); danach Einstand bzw. Tiebreak als neutrale Hinweise.
 */
internal fun hintOf(state: MatchState): WatchHint? {
    val current = state.current ?: return null
    if (state.status != MatchStatus.IN_PROGRESS && state.status != MatchStatus.PAUSED) return null
    when (current.callout) {
        Callout.DECIDING_POINT -> return WatchHint("DECIDING_POINT", "", 0)
        Callout.STAR_POINT -> return WatchHint("STAR_POINT", "", 0)
        else -> Unit
    }
    val flag =
        state.statusFlags
            .filter { it.level != FlagLevel.GAME_POINT || it.breakPoint }
            .maxByOrNull { it.level.ordinal }
    if (flag != null) {
        val code =
            when (flag.level) {
                FlagLevel.MATCH_POINT -> "MATCH_POINT"
                FlagLevel.SET_POINT -> "SET_POINT"
                FlagLevel.GAME_POINT -> "BREAK_POINT"
            }
        return WatchHint(code, flag.side.name, flag.count)
    }
    if (current.callout == Callout.DEUCE) return WatchHint("DEUCE", "", 0)
    return when (current.gameKind) {
        GameKind.MATCH_TIEBREAK -> WatchHint("MATCH_TIEBREAK", "", 0)
        GameKind.TIEBREAK -> WatchHint("TIEBREAK", "", 0)
        else -> null
    }
}

internal fun buildSnapshot(
    matchId: String,
    engine: EngineState,
    startedAtMs: Long,
): WatchScoreSnapshot {
    val state = engine.state
    val format = engine.format
    val current = state.current
    val serve = state.serve
    val hint = hintOf(state)
    val sets = state.sets

    fun setScore(
        side: Side,
        index: Int,
    ): Int {
        val set = sets[index]
        return when (set.kind) {
            SetKind.REGULAR -> set.games?.get(side)
            SetKind.MATCH_TIEBREAK -> set.tiebreak?.get(side) ?: set.games?.get(side)
            SetKind.PB_GAME, SetKind.RALLY_GAME -> set.points?.get(side)
        } ?: 0
    }

    fun tiebreak(
        side: Side,
        index: Int,
    ): Int = sets[index].takeIf { it.kind == SetKind.REGULAR }?.tiebreak?.get(side) ?: -1

    // Nummer im Team laut Besetzung (stabile Identität, unabhängig von der Aufschlagreihenfolge der Engine)
    fun numberOf(player: PlayerId?): Int {
        if (player == null) return 0
        val side = engine.lineup.sideOf(player) ?: return 0
        return engine.lineup.players(side).indexOf(player) + 1
    }
    val positions = state.positions
    return WatchScoreSnapshot(
        matchId = matchId,
        sport = format.sport.name,
        presetId = format.presetId.orEmpty(),
        doubles = format.discipline == Discipline.DOUBLES,
        openMode = format.match.mode == MatchMode.OPEN,
        bestOf = format.match.bestOf ?: 0,
        offerMatchTiebreak = format.match.mode == MatchMode.OPEN && format.matchTiebreak != null,
        status = state.status.name,
        pendingDecision = state.status == MatchStatus.AWAITING_DECISION,
        matchFinished = state.status == MatchStatus.FINISHED,
        winner =
            state.result
                ?.winner
                ?.name
                .orEmpty(),
        resultReason =
            state.result
                ?.reason
                ?.name
                .orEmpty(),
        pointsA = current?.display?.a.orEmpty(),
        pointsB = current?.display?.b.orEmpty(),
        gamesA = current?.games?.a ?: -1,
        gamesB = current?.games?.b ?: -1,
        setIndex = current?.setIndex ?: sets.size,
        setKind = current?.setKind?.name.orEmpty(),
        gameKind = current?.gameKind?.name.orEmpty(),
        setsWonA = state.setsWon.a,
        setsWonB = state.setsWon.b,
        setScoresA = sets.indices.map { setScore(Side.A, it) },
        setScoresB = sets.indices.map { setScore(Side.B, it) },
        setTiebreaksA = sets.indices.map { tiebreak(Side.A, it) },
        setTiebreaksB = sets.indices.map { tiebreak(Side.B, it) },
        setWinners = sets.map { it.winner.name },
        paused = state.status == MatchStatus.PAUSED,
        serveSide = serve?.side?.name.orEmpty(),
        serveCourt = serve?.court?.name.orEmpty(),
        server = serve?.player?.value.orEmpty(),
        receiver = serve?.receiver?.value.orEmpty(),
        serverNo = numberOf(serve?.player),
        receiverNo = numberOf(serve?.receiver),
        rightNoA = numberOf(positions?.a?.right),
        leftNoA = numberOf(positions?.a?.left),
        rightNoB = numberOf(positions?.b?.right),
        leftNoB = numberOf(positions?.b?.left),
        receiveOrderSwapped = Effect.RECEIVE_ORDER_SWAPPED in state.effects,
        serverNumber = serve?.serverNumber ?: 0,
        servesRemaining = serve?.servesRemaining ?: 0,
        scoreCall = state.scoreCall.orEmpty(),
        hintCode = hint?.code.orEmpty(),
        hintSide = hint?.side.orEmpty(),
        hintCount = hint?.count ?: 0,
        callout = current?.callout?.name.orEmpty(),
        deuceCount = current?.deuceCount ?: 0,
        effects = state.effects.map { it.name },
        changeEnds = Effect.CHANGE_ENDS in state.effects,
        canUndo = engine.undoTarget != null,
        eventCount = engine.events.size,
        startedAtMs = startedAtMs,
    )
}
