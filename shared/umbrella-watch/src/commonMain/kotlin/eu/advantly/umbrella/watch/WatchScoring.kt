package eu.advantly.umbrella.watch

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.Discipline
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchFormat
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.MatchMode
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.scoring.Callout
import eu.advantly.core.scoring.Effect
import eu.advantly.core.scoring.FlagLevel
import eu.advantly.core.scoring.GameKind
import eu.advantly.core.scoring.MatchFormatValidator
import eu.advantly.core.scoring.MatchState
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.core.scoring.Preset
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.SetKind
import eu.advantly.data.LocalData
import eu.advantly.match.session.ActiveMatch
import eu.advantly.match.session.IdGenerator
import eu.advantly.match.session.MatchSession
import eu.advantly.match.session.RandomIdGenerator
import eu.advantly.match.session.ScorerChoice
import eu.advantly.match.session.SessionListener
import eu.advantly.match.session.SessionState
import eu.advantly.match.session.SessionThreadViolation
import eu.advantly.match.session.SyncIndicator
import eu.advantly.match.session.SyncStatus
import eu.advantly.match.session.SystemWallClock
import eu.advantly.match.session.WallClock
import eu.advantly.sync.protocol.SyncCodec

/**
 * Swift-Facade für das Zählen auf der Apple Watch (W1-E) über die gemeinsame [MatchSession] (`shared/match-session`,
 * Gerätart `WATCH`). Ohne SKIE (CLAUDE.md Regel 4): an der Grenze nur String/Int/Long/Boolean, Listen und flache Klassen;
 * Fehler laufen nie als Exception nach Swift, sondern als `null`/`false` plus [lastError].
 *
 * - **Single-Scorer** (02 §7.5): Ein auf der Uhr gestartetes Match zählt die Uhr. Hat ein anderes Gerät (z. B. das Handy
 *   im Director-Modus) den Zähler, ist die Uhr **Anzeige** ([WatchScoreSnapshot.isScorer] = `false`): Zähl-Befehle
 *   liefern `null` mit `NOT_SCORER`; [takeOverScoring] macht die Uhr nach Bestätigung in der UI zum Zähler.
 * - **Speicher:** Events, Match-Köpfe und Einstellungen liegen in der gemeinsamen [LocalData] (`advantly.db`, Schema aus
 *   `shared/data`). Das geöffnete Match wird gemerkt und nach einem App-Neustart mit [restore] wieder geöffnet.
 *   **Dev-Daten-Reset:** Das Schema hat sich mit PR #13 ohne Migration geändert (Schema-Version bleibt 1). Eine Uhr- oder
 *   Simulator-Installation aus der Zeit davor kann `advantly.db` nicht lesen → [isAvailable] = `false`, alle Befehle
 *   `STORE_READ`; Abhilfe: Watch-App löschen und neu installieren (Simulator: `xcrun simctl uninstall`).
 * - **Sync:** Die Session sendet jedes Event sofort an das gekoppelte iPhone (WatchConnectivity aus `device-link`),
 *   puffert offline und liefert bei Wiederverbindung nach; der Snapshot trägt den Sync-Status.
 * - **Perspektive:** Im Snapshot und bei [point] ist „A“ immer „Wir“ = die Seite des Besitzers (`LocalData.isSelf`,
 *   inkl. vom iPhone übernommenem Self und Aliasen), „B“ die Gegner. Steht der Besitzer in der Engine auf Side B
 *   (z. B. vom iPhone so angelegt), dreht die Facade die Seiten; ohne Besitzer im Match gilt Engine-A = „Wir“.
 * - **Self:** Neue Matches lesen den Self jedes Mal frisch aus `players` – nach dem ersten `Hello` übernimmt die Session
 *   den Self des iPhones (`adoptSelf`), der alte Uhr-Self wird Alias.
 * - **Workout-Wunsch:** `MatchMeta.watchWorkout` – auf der Uhr gestartete Matches schreiben den Wunsch der Uhr hinein,
 *   vom iPhone gestartete bringen dessen Wahl mit ([WatchScoreSnapshot.watchWorkout]).
 * - Nur auf der Main-Queue aufrufen; [WatchScoringListener] wird synchron auf der Main-Queue benachrichtigt (auch bei
 *   Änderungen durch das Handy).
 *
 * Swift: `let scoring = AdvantlyWatch.shared.scoring()`, dann z. B.
 * `scoring.startMatch(presetId: "TENNIS_SINGLES_BO3", doubles: false, weServeFirst: true, watchWorkout: true)`.
 */
class WatchScoring internal constructor(
    private val open: () -> Backend,
    private val clock: WallClock = SystemWallClock,
    private val ids: IdGenerator = RandomIdGenerator(),
    private val demoEnabled: Boolean = platformDemoEnabled(),
) {
    /** Session und Datenbank; die Fabrik läuft beim ersten Zugriff (Fehler → [isAvailable] = `false`). */
    internal class Backend(
        val session: MatchSession,
        val data: LocalData,
    )

    private var listener: WatchScoringListener? = null
    private var error: String = ""
    private var backend: Backend? = null
    private var openFailed = false
    private var current: SessionState? = null
    private var lastOpenId: String? = null
    private var scorerLostAtMs: Long = 0

    private val sessionListener = SessionListener { state -> onState(state) }

    /** `false`, wenn Datenbank oder Session nicht geöffnet werden konnten (siehe Dev-Daten-Reset oben). */
    fun isAvailable(): Boolean = backend() != null

    /** Ein Listener; `null` meldet ab. Bekommt jeden neuen Zustand, `null` = kein Match offen (Startliste). */
    fun setListener(listener: WatchScoringListener?) {
        this.listener = listener
    }

    /**
     * Grund, warum der letzte Befehl `null`/`false` geliefert hat, sonst `""` (wird bei jedem Befehl zurückgesetzt):
     * - `STORE_WRITE`: Speichern/Senden fehlgeschlagen – Zustand unverändert, derselbe Befehl kann wiederholt werden
     * - `STORE_READ`: Datenbank nicht lesbar (z. B. veraltetes Schema) – Zeiger auf das Match bleibt erhalten
     * - `LOG_INVALID`: gemerktes Match nicht (mehr) vorhanden oder nicht auswertbar – Zeiger wird verworfen
     * - `INVALID_INPUT`: unbekanntes Preset, falsche Disziplin oder ungültiges Format
     * - `NOT_SCORER`: dieses Gerät ist Anzeige, ein anderes Gerät zählt
     * - `NOT_ALLOWED`: Befehl passt nicht zum Zustand (z. B. Punkt im Satzende-Dialog, nichts rückgängig zu machen)
     * - `NO_MATCH`: kein Match geöffnet
     * - `THREAD_VIOLATION`: Aufruf außerhalb des Session-/Main-Threads; Programmierfehler, kein Speicherfehler
     */
    fun lastError(): String = error

    /** Stabile Installations-ID der Uhr (UUIDv7 aus der Session), `""` ohne Datenbank. */
    fun deviceId(): String =
        backend()
            ?.session
            ?.deviceId
            ?.value
            .orEmpty()

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
        val parts = setting(KEY_LAST_SETUP)?.split(';') ?: return null
        if (parts.size != 3) return null
        val preset = Presets.byId(parts[0]) ?: return null
        return WatchMatchSetup(preset.id, preset.sport.name, parts[1] == "1", parts[2] == "1")
    }

    // --- Lebenszyklus ---------------------------------------------------------------------------------------------

    /**
     * Startet ein neues Match aus einem Preset; **die Uhr zählt** (Standard). Besetzung: A = Self-Player der Uhr (+ Partner),
     * B = Gegner; bei „Wie letztes Mal“ derselben Sportart/Disziplin dieselben Spieler-IDs wie zuletzt.
     * [weServeFirst] wählt den Erstaufschläger (A bzw. B, jeweils Nummer 1). [watchWorkout] landet als Wunsch
     * „Workout auf meiner Uhr“ im `MatchMeta` (geht mit dem Match ans iPhone).
     *
     * @return Zustand nach dem Start oder `null` (`INVALID_INPUT`, `STORE_WRITE`, `STORE_READ`)
     */
    fun startMatch(
        presetId: String,
        doubles: Boolean,
        weServeFirst: Boolean,
        watchWorkout: Boolean,
    ): WatchScoreSnapshot? {
        error = ""
        val preset = Presets.byId(presetId) ?: return fail(INVALID_INPUT)
        val discipline = if (doubles) Discipline.DOUBLES else Discipline.SINGLES
        if (discipline !in preset.disciplines) return fail(INVALID_INPUT)
        val snapshot = begin(preset.format(discipline), weServeFirst, ScorerChoice.ThisDevice, watchWorkout) ?: return null
        putSetting(KEY_LAST_SETUP, listOf(preset.id, flag(doubles), flag(weServeFirst)).joinToString(";"))
        return snapshot
    }

    /**
     * Startet ein Match aus einem `MatchFormat`-JSON (02 §3.2), z. B. vom Handy-Wizard; die Uhr zählt.
     * Ungültiges JSON oder Format → `null` (`INVALID_INPUT`).
     */
    fun startMatchFromFormatJson(
        formatJson: String,
        weServeFirst: Boolean,
        watchWorkout: Boolean,
    ): WatchScoreSnapshot? {
        error = ""
        val format =
            try {
                SyncCodec.json.decodeFromString(MatchFormat.serializer(), formatJson)
            } catch (e: IllegalArgumentException) {
                // SerializationException ist eine IllegalArgumentException
                return fail(INVALID_INPUT)
            }
        if (MatchFormatValidator.validate(format).errors.isNotEmpty()) return fail(INVALID_INPUT)
        return begin(format, weServeFirst, ScorerChoice.ThisDevice, watchWorkout)
    }

    /**
     * Öffnet nach einem App-Neustart das zuletzt geöffnete Match wieder (Replay aus dem Event-Log). Ist schon ein Match
     * offen (z. B. weil die Session einem vom Handy gestarteten Match gefolgt ist), wird dessen Zustand geliefert.
     * `null` ohne gemerktes Match (`""`), bei `STORE_READ` (Zeiger bleibt) oder `LOG_INVALID` (Zeiger wird verworfen).
     */
    fun restore(): WatchScoreSnapshot? {
        error = ""
        val b = backend() ?: return fail(STORE_READ)
        snapshot()?.let { return it }
        val id = setting(KEY_OPEN_MATCH) ?: return null
        val ok =
            try {
                b.session.resume(MatchId(id))
            } catch (e: Exception) {
                return fail(STORE_READ)
            }
        if (!ok) {
            removeSetting(KEY_OPEN_MATCH)
            return fail(LOG_INVALID)
        }
        return snapshot()
    }

    /** `true`, solange ein Match geöffnet bzw. zum Wiederöffnen gemerkt ist (auch beendet, bis [close]). */
    fun hasActiveMatch(): Boolean = current?.match != null || setting(KEY_OPEN_MATCH) != null

    /** ID des geöffneten (bzw. gemerkten) Matches oder `""`. */
    fun activeMatchId(): String = current?.match?.id?.value ?: setting(KEY_OPEN_MATCH).orEmpty()

    /** Aktueller Zustand des geöffneten Matches oder `null`. */
    fun snapshot(): WatchScoreSnapshot? {
        backend() ?: return null
        val state = current ?: return null
        return state.match?.let { snapshotOf(it, state.sync) }
    }

    /** Schließt das Match in der Anzeige (z. B. nach der Matchende-Ansicht). Es bleibt gespeichert und wird weiter synchronisiert. */
    fun close() {
        error = ""
        val b = backend() ?: return
        call { b.session.closeMatch() }
        removeSetting(KEY_OPEN_MATCH)
        lastOpenId = null
    }

    // --- Befehle (nur der Zähler) ---------------------------------------------------------------------------------

    /** Punkt für „Wir“ (`sideA = true`, Seite des Besitzers) bzw. die Gegner. Nur im laufenden bzw. pausierten Satz. */
    fun point(sideA: Boolean): WatchScoreSnapshot? =
        command { session, m ->
            val status = m.state.status
            if (status != MatchStatus.IN_PROGRESS && status != MatchStatus.PAUSED) return@command false
            val us = usSide(m)
            session.recordPoint(if (sideA) us else us.other)
        }

    /** Macht das letzte wirksame Zähl-Event rückgängig (auch über Satz- und Matchende hinweg). */
    fun undo(): WatchScoreSnapshot? = command { session, m -> m.canUndo && session.undo() }

    /** Stellt das zuletzt auf dieser Uhr rückgängig gemachte Event wieder her. */
    fun redo(): WatchScoreSnapshot? = command { session, m -> m.canRedo && session.redo() }

    /**
     * Antwort auf „Noch ein Satz?“ (nur bei `pendingDecision`). [matchTiebreak] wählt einen Match-Tiebreak statt eines
     * regulären Satzes, sofern das Format ihn anbietet ([WatchScoreSnapshot.offerMatchTiebreak]); sonst regulär.
     */
    fun continueDecision(
        anotherSet: Boolean,
        matchTiebreak: Boolean,
    ): WatchScoreSnapshot? =
        command { session, m ->
            if (m.state.status != MatchStatus.AWAITING_DECISION) return@command false
            session.continueDecision(anotherSet, anotherSet && matchTiebreak && m.format.matchTiebreak != null)
        }

    /**
     * Beendet das Match: beim offenen Satzende-Dialog wie „Nein“ (reguläres Ergebnis), sonst vorzeitig (`ABANDONED`).
     * Bereits beendet → aktueller Zustand. Rückgängig machbar über [undo].
     */
    fun end(): WatchScoreSnapshot? {
        val m = current?.match
        if (m != null && m.isScorer && m.state.status == MatchStatus.FINISHED) {
            error = ""
            return snapshot()
        }
        return command { session, _ -> session.endMatch() }
    }

    /**
     * Unterbrechung (Regen, Verletzung; 02 §7 `Paused`) ohne Wirkung auf den Score, nur im laufenden Satz. Die Uhr
     * pausiert dazu die Workout-Session. Seitenwechsel und Satzende-Dialog sind **keine** Pause.
     */
    fun pause(): WatchScoreSnapshot? = command { session, m -> m.state.status == MatchStatus.IN_PROGRESS && session.pause() }

    /** Fortsetzung nach [pause]. Ein Punkt während der Pause setzt ebenfalls fort (Engine-Regel). */
    fun resumePlay(): WatchScoreSnapshot? = command { session, m -> m.state.status == MatchStatus.PAUSED && session.resumePlay() }

    /**
     * „Zählen übernehmen“ (02 §7.5, nach Bestätigung in der UI): die Uhr wird Zähler (`ScorerChanged`). Eingaben des
     * bisherigen Zählers, die danach noch ankommen, verwirft die Engine (`rejectedByRoleCount`).
     * `null`, wenn kein Match offen ist, es beendet ist (`NOT_ALLOWED`) oder die Uhr schon zählt (`NOT_ALLOWED`).
     */
    fun takeOverScoring(): WatchScoreSnapshot? {
        error = ""
        val b = backend() ?: return fail(STORE_READ)
        val m = current?.match ?: return fail(NO_MATCH)
        if (m.isScorer || m.state.status == MatchStatus.FINISHED) return fail(NOT_ALLOWED)
        val ok = call { b.session.takeOverScoring() } ?: return null
        return if (ok) snapshot() else fail(NOT_ALLOWED)
    }

    /** Stößt sofort einen Abgleich mit dem iPhone an (z. B. App wieder im Vordergrund). */
    fun syncNow() {
        val b = backend() ?: return
        call { b.session.syncNow() }
    }

    /**
     * Nur für CI-Screenshots (Debug-Demo, Etappe „display“): legt ein Match an, das ein anderes Gerät (Handy) zählt –
     * die Uhr ist damit Anzeige. Nur freigegeben bei Debug-Kotlin-Binary UND `-advantlyDemoStage` (Swift zusätzlich
     * `#if DEBUG`); sonst `NOT_ALLOWED` ohne Datenbankzugriff. Nicht für die App-Logik gedacht.
     */
    fun startDisplayDemo(presetId: String): WatchScoreSnapshot? {
        error = ""
        if (!demoEnabled) return fail(NOT_ALLOWED)
        val b = backend() ?: return fail(STORE_READ)
        val preset = Presets.byId(presetId) ?: return fail(INVALID_INPUT)
        // Session-interner Schlüssel der Gerätart, damit die Anzeige „zählt: Handy“ zeigt
        call { b.data.settings.put("session.peerKind.$DEMO_SCORER", "PHONE") } ?: return null
        return begin(preset.format(), weServeFirst = true, scorer = ScorerChoice.Device(DeviceId(DEMO_SCORER)), watchWorkout = false)
    }

    // --- intern ---------------------------------------------------------------------------------------------------

    private fun backend(): Backend? {
        backend?.let { return it }
        if (openFailed) return null
        return try {
            open().also {
                backend = it
                it.session.addListener(sessionListener)
            }
        } catch (e: Exception) {
            openFailed = true
            null
        }
    }

    private fun onState(state: SessionState) {
        current = state
        val match = state.match
        val now = clock.nowMs()
        // Seit wann ist der fremde Zähler nicht erreichbar? (Anzeige: Angebot „Zählen übernehmen“ nach > 30 s)
        scorerLostAtMs =
            when {
                match == null || match.isScorer || state.sync.scorerConnected -> 0
                scorerLostAtMs == 0L -> now
                else -> scorerLostAtMs
            }
        // geöffnetes Match für den Neustart merken; verschwindet es (geschlossen, remote gelöscht), Zeiger entfernen
        val id = match?.id?.value
        if (id != null && id != lastOpenId) putSetting(KEY_OPEN_MATCH, id)
        if (id == null && lastOpenId != null) removeSetting(KEY_OPEN_MATCH)
        lastOpenId = id
        listener?.onSnapshot(match?.let { snapshotOf(it, state.sync) })
    }

    private fun snapshotOf(
        match: ActiveMatch,
        sync: SyncStatus,
    ): WatchScoreSnapshot? {
        val pending =
            try {
                backend
                    ?.data
                    ?.events
                    ?.unconfirmedEvents(match.id.value)
                    ?.size ?: return fail(STORE_READ)
            } catch (e: Exception) {
                return fail(STORE_READ)
            }
        return buildSnapshot(match, sync, scorerLostAtMs, usSide(match), pending)
    }

    private fun begin(
        format: MatchFormat,
        weServeFirst: Boolean,
        scorer: ScorerChoice,
        watchWorkout: Boolean,
    ): WatchScoreSnapshot? {
        val b = backend() ?: return fail(STORE_READ)
        val lineup =
            try {
                lineupFor(b.data, format, weServeFirst)
            } catch (e: Exception) {
                return fail(STORE_WRITE)
            }
        try {
            b.session.create(format, lineup, MatchMeta(watchWorkout = watchWorkout), scorer)
        } catch (e: SessionThreadViolation) {
            return fail(THREAD_VIOLATION)
        } catch (e: IllegalArgumentException) {
            return fail(INVALID_INPUT)
        } catch (e: Exception) {
            return fail(STORE_WRITE)
        }
        return snapshot()
    }

    /**
     * Gemeinsamer Ablauf der Zähl-Befehle: Rolle prüfen, Session-Befehl ausführen (Ausnahmen abfangen), neuen Zustand
     * liefern. [action] gibt `false` zurück, wenn der Befehl nicht zum Zustand passt.
     */
    private fun command(action: (MatchSession, ActiveMatch) -> Boolean): WatchScoreSnapshot? {
        error = ""
        val b = backend() ?: return fail(STORE_READ)
        val m = current?.match ?: return fail(NO_MATCH)
        if (!m.isScorer) return fail(NOT_SCORER)
        val ok = call { action(b.session, m) } ?: return null
        return if (ok) snapshot() else fail(NOT_ALLOWED)
    }

    /** Führt [block] aus; SQLite-/Treiber-Ausnahmen dürfen nie über die Swift-Grenze laufen (→ `null`). */
    private fun <T> call(block: () -> T): T? =
        try {
            block()
        } catch (e: SessionThreadViolation) {
            error = THREAD_VIOLATION
            null
        } catch (e: Exception) {
            error = STORE_WRITE
            null
        }

    /**
     * Besetzung: A = Self-Player der Uhr (+ Partner), B = Gegner. Gab es diese Sportart/Disziplin schon mit dem
     * Self-Player auf einer der beiden Seiten, werden dieselben Spieler-IDs aus Besitzerperspektive wiederverwendet
     * (Statistik bleibt zusammen); Self/Aliase dürfen auch an zweiter Stelle im Doppel stehen.
     */
    private fun lineupFor(
        data: LocalData,
        format: MatchFormat,
        weServeFirst: Boolean,
    ): Lineup {
        val now = clock.nowMs()
        // jedes Mal frisch: nach dem ersten Hello kann die Session den Self des iPhones übernommen haben
        val self = data.players.ensureSelf("", now) { PlayerId(ids.newUuid7(now).value) }.id
        val size = format.discipline.playersPerSide
        val last = data.matches.lastLineup(format.sport, format.discipline)
        val ours = last?.let { lineup -> listOf(lineup.a, lineup.b).firstOrNull { team -> team.any { data.isSelf(it) } } }
        val (a, b) =
            if (last != null && last.a.size == size && last.b.size == size && ours != null) {
                // dieselben Mitspieler wie zuletzt; ein alter Uhr-Self (jetzt Alias) wird durch den aktuellen ersetzt
                ours.map { if (data.isSelf(it)) self else it } to if (ours == last.a) last.b else last.a
            } else {
                val partner = (2..size).map { PlayerId(ids.newUuid7(now).value) }
                (listOf(self) + partner) to (1..size).map { PlayerId(ids.newUuid7(now).value) }
            }
        return Lineup(a, b, firstServer = if (weServeFirst) a.first() else b.first())
    }

    /**
     * Engine-Side des Besitzers („Wir“): B, wenn ein Self/Alias nur auf B steht, sonst A (auch ohne Besitzer im Match,
     * z. B. Director-Modus am iPhone).
     */
    private fun usSide(match: ActiveMatch): Side {
        val data = backend?.data ?: return Side.A
        val selfOnB = call { match.lineup.b.any { data.isSelf(it) } && match.lineup.a.none { data.isSelf(it) } } ?: false
        return if (selfOnB) Side.B else Side.A
    }

    private fun fail(code: String): WatchScoreSnapshot? {
        error = code
        return null
    }

    private fun setting(key: String): String? = backend()?.let { b -> call { b.data.settings.get(key) } }

    private fun putSetting(
        key: String,
        value: String,
    ) {
        backend()?.let { b -> call { b.data.settings.put(key, value) } }
    }

    private fun removeSetting(key: String) {
        backend()?.let { b -> call { b.data.settings.remove(key) } }
    }

    internal companion object {
        const val KEY_OPEN_MATCH = "watch.openMatchId"
        const val KEY_LAST_SETUP = "watch.lastSetup"
        const val DEMO_SCORER = "demo-phone"

        const val STORE_WRITE = "STORE_WRITE"
        const val STORE_READ = "STORE_READ"
        const val LOG_INVALID = "LOG_INVALID"
        const val INVALID_INPUT = "INVALID_INPUT"
        const val NOT_SCORER = "NOT_SCORER"
        const val NOT_ALLOWED = "NOT_ALLOWED"
        const val NO_MATCH = "NO_MATCH"
        const val THREAD_VIOLATION = "THREAD_VIOLATION"

        private fun flag(value: Boolean) = if (value) "1" else "0"

        private fun sportOf(name: String): Sport? = Sport.entries.firstOrNull { it.name == name }

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
}

/** Callback statt Flow (CLAUDE.md Regel 4). `null` = kein Match offen (Startliste). */
interface WatchScoringListener {
    fun onSnapshot(snapshot: WatchScoreSnapshot?)
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
 * Flacher Anzeige-Zustand (aus 02 §8 abgeleitet), **aus Sicht des Besitzers**: Felder mit „A“ (`pointsA`, `gamesA`,
 * `setScoresA`, `rightNoA` …) und Side-Codes `A`/`B` (`winner`, `serveSide`, `hintSide`, `setWinners`) meinen „Wir“ bzw.
 * „Gegner“, nicht die Engine-Side (siehe [perspectiveSwapped]). Nur Daten und Codes, keine UI-Texte – Swift übersetzt über
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
 * @property server Spieler-ID (UUID) des Aufschlägers oder `""`
 * @property receiver Spieler-ID (UUID) des Rückschlägers oder `""` (offene Wahl/kein Aufschlag)
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
 * @property canUndo die Uhr zählt und es gibt ein Ziel für [WatchScoring.undo]
 * @property canRedo die Uhr zählt und das zuletzt hier rückgängig gemachte Event lässt sich wiederherstellen
 * @property eventCount Anzahl der Events inkl. Genesis
 * @property startedAtMs Wanduhrzeit des Matchstarts (Dauer-Anzeige)
 * @property isScorer die Uhr zählt (02 §7.5); sonst ist sie Anzeige (keine Tap-Flächen, kein Undo)
 * @property scorerKind Art des zählenden Geräts: `WATCH`, `PHONE`, `OTHER` oder `""` (unbekannt)
 * @property rejectedByRoleCount Eingaben von Nicht-Zählern, die die Engine verworfen hat („N Eingaben nicht übernommen“)
 * @property pointsPlayed wirksam gezählte Ballwechsel
 * @property linkConnected mindestens eine kompatible Gegenstelle (iPhone) ist verbunden
 * @property scorerConnected die Uhr zählt selbst oder der Zähler ist verbunden
 * @property pendingToPeers Events des offenen Matches, die noch keine Gegenstelle bestätigt hat
 *   (0 = „synchron“, sonst „N ausstehend“); nicht der globale Rückstand der Session
 * @property lastEventReceivedAtMs Wanduhrzeit des zuletzt empfangenen Events (Anzeige: „Stand vor X s“), sonst `0`
 * @property lastSyncAtMs Wanduhrzeit des letzten Abgleichs mit einer Gegenstelle, sonst `0`
 * @property scorerDisconnectedSinceMs Anzeige: seit wann der Zähler nicht erreichbar ist (nach > 30 s Angebot
 *   „Zählen übernehmen“), sonst `0`
 * @property scorerPending das iPhone hat „Meine Uhr“ gewählt, aber noch keine Uhr gekannt (`PENDING_WATCH`): beim
 *   ersten `Hello` übergibt es per `ScorerChanged` an diese Uhr; bis dahin zählt niemand
 * @property watchWorkout Wunsch „Workout auf meiner Uhr mitlaufen lassen“ aus `MatchMeta.watchWorkout`
 * @property perspectiveSwapped der Besitzer steht in der Engine auf Side B; die Facade hat die Seiten gedreht
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
    val canRedo: Boolean,
    val eventCount: Int,
    val startedAtMs: Long,
    val isScorer: Boolean,
    val scorerKind: String,
    val rejectedByRoleCount: Int,
    val pointsPlayed: Int,
    val linkConnected: Boolean,
    val scorerConnected: Boolean,
    val pendingToPeers: Int,
    val lastEventReceivedAtMs: Long,
    val lastSyncAtMs: Long,
    val scorerDisconnectedSinceMs: Long,
    val scorerPending: Boolean,
    val watchWorkout: Boolean,
    val perspectiveSwapped: Boolean,
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
    match: ActiveMatch,
    sync: SyncStatus,
    scorerDisconnectedSinceMs: Long,
    us: Side = Side.A,
    pendingToPeers: Int = sync.pendingToPeers,
): WatchScoreSnapshot {
    val them = us.other

    // Engine-Side → Snapshot-Code aus Sicht des Besitzers
    fun code(side: Side): String = if (side == us) "A" else "B"

    val state = match.state
    val format = match.format
    val lineup = match.lineup
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
        val side = lineup.sideOf(player) ?: return 0
        return lineup.players(side).indexOf(player) + 1
    }
    val positions = state.positions
    return WatchScoreSnapshot(
        matchId = match.id.value,
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
                ?.let { code(it) }
                .orEmpty(),
        resultReason =
            state.result
                ?.reason
                ?.name
                .orEmpty(),
        pointsA = current?.display?.get(us).orEmpty(),
        pointsB = current?.display?.get(them).orEmpty(),
        gamesA = current?.games?.get(us) ?: -1,
        gamesB = current?.games?.get(them) ?: -1,
        setIndex = current?.setIndex ?: sets.size,
        setKind = current?.setKind?.name.orEmpty(),
        gameKind = current?.gameKind?.name.orEmpty(),
        setsWonA = state.setsWon[us],
        setsWonB = state.setsWon[them],
        setScoresA = sets.indices.map { setScore(us, it) },
        setScoresB = sets.indices.map { setScore(them, it) },
        setTiebreaksA = sets.indices.map { tiebreak(us, it) },
        setTiebreaksB = sets.indices.map { tiebreak(them, it) },
        setWinners = sets.map { code(it.winner) },
        paused = state.status == MatchStatus.PAUSED,
        serveSide = serve?.side?.let { code(it) }.orEmpty(),
        serveCourt = serve?.court?.name.orEmpty(),
        server = serve?.player?.value.orEmpty(),
        receiver = serve?.receiver?.value.orEmpty(),
        serverNo = numberOf(serve?.player),
        receiverNo = numberOf(serve?.receiver),
        rightNoA = numberOf(positions?.get(us)?.right),
        leftNoA = numberOf(positions?.get(us)?.left),
        rightNoB = numberOf(positions?.get(them)?.right),
        leftNoB = numberOf(positions?.get(them)?.left),
        receiveOrderSwapped = Effect.RECEIVE_ORDER_SWAPPED in state.effects,
        serverNumber = serve?.serverNumber ?: 0,
        servesRemaining = serve?.servesRemaining ?: 0,
        scoreCall = state.scoreCall.orEmpty(),
        hintCode = hint?.code.orEmpty(),
        hintSide =
            hint
                ?.side
                ?.takeIf { it.isNotEmpty() }
                ?.let { code(Side.valueOf(it)) }
                .orEmpty(),
        hintCount = hint?.count ?: 0,
        callout = current?.callout?.name.orEmpty(),
        deuceCount = current?.deuceCount ?: 0,
        effects = state.effects.map { it.name },
        changeEnds = Effect.CHANGE_ENDS in state.effects,
        canUndo = match.canUndo,
        canRedo = match.canRedo,
        eventCount = match.eventCount,
        startedAtMs = match.createdAtMs,
        isScorer = match.isScorer,
        scorerKind = match.scorerKind?.name.orEmpty(),
        rejectedByRoleCount = match.rejectedByRoleCount,
        pointsPlayed = match.pointsPlayed,
        linkConnected = sync.indicator != SyncIndicator.LOCAL_ONLY,
        scorerConnected = sync.scorerConnected,
        pendingToPeers = pendingToPeers,
        lastEventReceivedAtMs = sync.lastEventReceivedAtMs ?: 0,
        lastSyncAtMs = sync.lastSyncAtMs ?: 0,
        scorerDisconnectedSinceMs = scorerDisconnectedSinceMs,
        scorerPending = match.scorerDeviceId == MatchSession.PENDING_WATCH,
        watchWorkout = match.meta.watchWorkout,
        perspectiveSwapped = us == Side.B,
    )
}
