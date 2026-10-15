package eu.advantly.presentation.match

import eu.advantly.core.model.EventId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchFormat
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.scoring.CurrentSet
import eu.advantly.core.scoring.EndReason
import eu.advantly.core.scoring.MatchResult
import eu.advantly.core.scoring.MatchState
import eu.advantly.core.scoring.NextSetKind
import eu.advantly.core.scoring.ScoreEvent
import eu.advantly.core.scoring.ServeOutcome
import eu.advantly.core.scoring.SetRecord
import eu.advantly.core.scoring.SidePair
import kotlinx.coroutines.flow.StateFlow

/*
 * Port-Schnittstellen der Phone-UI. Die Screens kennen nur diese Interfaces; in den Apps erfüllt sie der
 * Session-Adapter `SessionMatchStore` (Persistenz, Sync, Single-Scorer über `shared/match-session`), in Tests und
 * Previews die engine-gestützte In-Memory-Implementierung `InMemoryMatchStore`. Vertrag für alle Implementierungen:
 *
 * - **Perspektive:** Side A ist immer das Team des Geräte-Besitzers („Wir“), Side B die Gegner (04 §3.2).
 * - **Local-first, synchron:** Ein Kommando (z. B. [LiveMatchPort.recordPoint]) wirkt sofort lokal; kehrt es mit
 *   `true` zurück, zeigt [LiveMatchPort.active] bereits den neuen Zustand. Die UI liest den Wert direkt nach dem
 *   Aufruf (Haptik und Animation im selben Frame, Latenzziel S4). Änderungen von anderen Geräten kommen nur über
 *   den Flow.
 * - **Keine ungültigen Events:** Kommandos, deren Vorbedingung (02 §7) nicht erfüllt ist, liefern `false` und
 *   schreiben nichts ins Log (02 §7.3 Schritt 4: „Die UI DARF solche Events nicht erzeugen“).
 * - **Single-Scorer (02 §7.5):** Zähl-Kommandos wirken nur auf dem Zähler; auf Anzeige-Geräten liefern sie `false`.
 * - Alle Methoden werden nur vom UI-Thread aufgerufen.
 */

/**
 * Alles, was vor dem ersten Punkt feststeht: Genesis `MatchCreated(config, lineup, meta)` plus Anzeigenamen.
 *
 * @property players alle Spieler aus [lineup] mit Anzeigenamen (der eigene Spieler darf einen leeren Namen haben,
 *   die UI zeigt dann „Du“)
 * @property assignedPlayers ursprüngliche Platz-ID → zugewiesene Person; Score und Rotation behalten die Platz-ID
 * @property previousMatchId verknüpftes Vor-Match bei „Noch einer in gleicher Besetzung?“ (02 §5.2)
 */
data class MatchSetup(
    val format: MatchFormat,
    val lineup: Lineup,
    val meta: MatchMeta = MatchMeta(),
    val players: List<Player>,
    val previousMatchId: MatchId? = null,
    val assignedPlayers: Map<PlayerId, Player> = emptyMap(),
) {
    val sport: Sport get() = format.sport

    /** Spieler zur ID oder `null`. */
    fun player(id: PlayerId): Player? =
        assignedPlayers[id]?.copy(id = id) ?: players.firstOrNull { it.id == id } ?: assignedPlayers.values.firstOrNull { it.id == id }

    /** Spieler der Side [side] in Lineup-Reihenfolge. */
    fun team(side: Side): List<Player> = lineup.players(side).mapNotNull(::player)
}

/** Rolle dieses Geräts im aktiven Match (Single-Scorer, 02 §7.5). */
enum class LiveRole {
    /** Dieses Gerät zählt (Handy im Director-Modus bzw. allein). */
    SCORER,

    /** Ein anderes Gerät zählt; dieses zeigt nur an (kein Zählen, kein Undo, 04 §4.1 H6a). */
    DISPLAY,
}

/**
 * Gekoppeltes Gerät mit anderer Protokollversion (Hello/`negotiate`, seit W2-A Protokoll 2): Die Geräte tauschen nichts
 * aus, bis aktualisiert ist. UI: „Bitte Uhr/Handy aktualisieren“.
 */
enum class UpdateRequired {
    /** Das andere Gerät (meist die Uhr) hat einen älteren App-Stand. */
    OTHER_DEVICE,

    /** Dieses Gerät hat einen älteren App-Stand als das gekoppelte. */
    THIS_DEVICE,
}

/** Art des zählenden Geräts (Anzeige „Es zählt: Uhr von …“). */
enum class DeviceKind { PHONE, WATCH, OTHER }

/**
 * Ist die eigene Uhr als Zähler wählbar? Maßgeblich ist, ob Advantly auf einer gekoppelten Uhr **installiert** ist
 * (Wear `CapabilityClient` bzw. `WCSession`), nicht ob sie gerade verbunden ist (Feldtest W1-I).
 */
enum class WatchStatus {
    /** Nicht feststellbar (Desktop, Play-Dienste fehlen) und keine Uhr verbunden. */
    UNKNOWN,

    /** Keine Uhr gekoppelt. */
    NO_WATCH,

    /** Uhr gekoppelt, Advantly dort nicht installiert. */
    APP_NOT_INSTALLED,

    /** Advantly ist auf der Uhr installiert (läuft evtl. gerade nicht) oder die Uhr ist verbunden. */
    AVAILABLE,
}

/** Wer zählt ein neues Match (04 §3.1 „Wer zählt?“, P1: nur eigene Geräte). */
enum class ScorerOption {
    /** Die eigene, gekoppelte Uhr zählt; das Handy zeigt an. */
    MY_WATCH,

    /** Dieses Handy zählt (Director-Modus); Uhren zeigen an. */
    THIS_PHONE,
}

/**
 * Start-Optionen, die nicht zum Setup gehören – Presets und Favoriten vergeben keine Zählrechte (04 §3.1).
 *
 * @property watchWorkout „Workout auf meiner Uhr mitlaufen lassen“ (Standard an, unabhängig vom Zähler)
 */
data class StartOptions(
    val scorer: ScorerOption = ScorerOption.THIS_PHONE,
    val watchWorkout: Boolean = true,
)

/**
 * Momentaufnahme des laufenden Matches für die UI.
 *
 * @property canUndo dieses Gerät zählt und es gibt ein UI-Undo-Ziel (02 §7.3: letztes wirksames
 *   PointWon/ContinueDecision/Correction/MatchEnded)
 * @property canRedo das zuletzt auf diesem Gerät rückgängig gemachte Event lässt sich wiederherstellen
 * @property startedAtMs Erstellzeit (Wanduhr der Genesis)
 * @property finishedAtMs Wanduhr des Events, das das Match beendet hat; `null`, solange es läuft
 * @property followUpMatchId bereits angelegtes Folge-Match (02 §9.1: UI warnt vor Undo)
 * @property revision steigt mit jeder Änderung (auch bei gleichem Zustand, z. B. Redo-Stapel)
 * @property pointsPlayed wirksam angewendete Ballwechsel (`PointWon`, nicht rückgängig gemacht, nicht absorbiert,
 *   nicht übersprungen). Auch Ballwechsel ohne Punkt zählen (Pickleball Side-Out) – maßgeblich für `WALKOVER` (02 §9.8)
 * @property role zählt dieses Gerät ([LiveRole.SCORER]) oder zeigt es nur an ([LiveRole.DISPLAY])
 * @property scorerName Anzeigename der Person, deren Gerät zählt (P1: der Besitzer; leer = „Du“), `null` = unbekannt
 * @property scorerKind Art des zählenden Geräts
 * @property rejectedByRoleCount ignorierte Eingaben eines Nicht-Zählers (02 §7.5: „N Eingaben nicht übernommen“)
 * @property sync Verbindungszustand (Sync-Pill, Staleness, 04 §3.2)
 * @property absorbedTaps Anzahl als Doppel-Tap zusammengeführter Eingaben (01 §4.4); steigt sie, zeigt die UI einen Hinweis
 * @property waitingForScorer das Match ist an die Uhr gesendet, sie hat sich dafür aber noch nicht gemeldet (keine
 *   Verbindung, noch keine Eingabe) – UI: „Warte auf Uhr …“ mit „Doch am Handy zählen“
 * @property lastPoint zuletzt wirksam gezählter Ballwechsel – Ziel der Aufschlag-Leiste ([LiveMatchPort.setServeInfo])
 * @property serveInfoEnabled Aufschlag-Leiste anbieten: Tennis/Padel und `meta.serveStats` (gilt auch auf Anzeige-Geräten
 *   für die Anzeige; erfassen kann nur der Zähler)
 * @property canRemovePoint je Seite: „Letzten Punkt abziehen“ möglich ([LiveMatchPort.removeLastPointOf])
 * @property nextSetOptions bei `AWAITING_DECISION` die Satzarten für [LiveMatchPort.startNextSet], sonst leer
 *
 * Die offene Aufschlägerwahl im Doppel steht im Engine-Zustand: `state.serverChoice` (02 §6.5.5).
 */
data class LiveMatchSnapshot(
    val matchId: MatchId,
    val setup: MatchSetup,
    val state: MatchState,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val startedAtMs: Long,
    val finishedAtMs: Long?,
    val followUpMatchId: MatchId?,
    val revision: Long,
    val pointsPlayed: Int = 0,
    val role: LiveRole = LiveRole.SCORER,
    val scorerName: String? = null,
    val scorerKind: DeviceKind = DeviceKind.PHONE,
    val rejectedByRoleCount: Int = 0,
    val sync: SyncInfo = SyncInfo.LOCAL_ONLY,
    val absorbedTaps: Int = 0,
    val waitingForScorer: Boolean = false,
    val lastPoint: LastPointInfo? = null,
    val serveInfoEnabled: Boolean = false,
    val canRemovePoint: SidePair<Boolean> = SidePair(false, false),
    val nextSetOptions: List<NextSetKind> = emptyList(),
) {
    val isScorer: Boolean get() = role == LiveRole.SCORER

    /** Aufschlägerwahl offen und noch nicht getroffen (Hinweis „Wer schlägt auf?“, blockiert das Zählen nicht). */
    val awaitingServerChoice: Boolean get() = state.serverChoice?.let { it.chosen == null } == true
}

/**
 * Der zuletzt gezählte Ballwechsel für die Aufschlag-Leiste (Feldtest 2026-10-15).
 *
 * @property side Gewinner
 * @property servingSide aufschlagende Side vor dem Punkt
 * @property server Aufschläger (Doppel: nach Wahl bzw. Rotation)
 * @property serve erfasste Aufschlag-Info oder `null`
 * @property doubleFaultAllowed „Doppelfehler“ wählbar (Rückschlagseite hat gewonnen)
 */
data class LastPointInfo(
    val id: EventId,
    val side: Side,
    val servingSide: Side,
    val server: PlayerId,
    val serve: ServeOutcome?,
    val doubleFaultAllowed: Boolean,
)

/**
 * Verbindungszustand für die Sync-Pill (04 §3.2, Entscheidung „Sync Zähler → Handy“): Zähler zeigen „synchron /
 * N ausstehend“, Anzeige-Geräte „Stand vor X s“ und nach mehr als [STALE_AFTER_MS] ohne Verbindung das
 * Übernahmeangebot. Alle Spannen laufen über eine monotone Uhr ([MonotonicClock]); die UI übergibt deren `nowMs`.
 *
 * @property peers verbundene, kompatible weitere Geräte (Uhr, zweites Handy)
 * @property scorerConnected dieses Gerät zählt selbst oder der Zähler ist gerade verbunden
 * @property pendingToPeers Events, die noch keine Gegenstelle bestätigt hat (über alle Matches, persistent)
 * @property lastEventReceivedAtMs Wanduhr des zuletzt empfangenen neuen Events, `null` = noch keins
 * @property lastScorerContactMono monotone Zeit des letzten Kontakts zum Zähler (auch über App-Neustarts gemerkt);
 *   `null` = unbekannt – die UI zeigt dann „Stand unbekannt“ statt eines erfundenen Alters
 * @property withoutScorerSinceMono monotone Zeit, seit der dieses Gerät das Match ohne Zähler-Kontakt beobachtet;
 *   Untergrenze für das Übernahmeangebot, wenn der letzte Kontakt unbekannt ist
 * @property updateRequired Versionsunterschied zu einem gekoppelten Gerät (siehe [UpdateRequired]), sonst `null`
 */
data class SyncInfo(
    val peers: Int,
    val scorerConnected: Boolean,
    val pendingToPeers: Int = 0,
    val lastEventReceivedAtMs: Long? = null,
    val lastScorerContactMono: Long? = null,
    val withoutScorerSinceMono: Long? = null,
    val updateRequired: UpdateRequired? = null,
) {
    /** Millisekunden seit dem letzten Kontakt zum Zähler (nie negativ), `null` = unbekannt. */
    fun staleForMs(nowMono: Long): Long? = lastScorerContactMono?.let { (nowMono - it).coerceAtLeast(0) }

    /**
     * Ohne Verbindung zum Zähler seit mehr als [STALE_AFTER_MS] → Hinweis + „Zählen übernehmen“ (04 §3.2). Bei
     * unbekanntem letztem Kontakt zählt die selbst beobachtete Zeit ohne Zähler.
     */
    fun offersTakeOver(nowMono: Long): Boolean {
        if (scorerConnected) return false
        val stale = staleForMs(nowMono) ?: withoutScorerSinceMono?.let { (nowMono - it).coerceAtLeast(0) } ?: return false
        return stale > STALE_AFTER_MS
    }

    companion object {
        /** Nur dieses Gerät, es zählt selbst. */
        val LOCAL_ONLY: SyncInfo = SyncInfo(peers = 0, scorerConnected = true)

        /** Schwelle für das Übernahmeangebot (04 §3.2: „nach > 30 s ohne Verbindung“, Feinschliff PLAN F15). */
        const val STALE_AFTER_MS: Long = 30_000
    }
}

/** Live-Zählen des aktiven Matches. Es gibt höchstens ein aktives Match pro Gerät. */
interface LiveMatchPort {
    /** Aktives Match (läuft, wartet auf Entscheidung oder ist beendet, aber noch nicht geschlossen); `null` = keins. */
    val active: StateFlow<LiveMatchSnapshot?>

    /** Die eigene Uhr kann als Zähler gewählt werden (= [watchStatus] ist `AVAILABLE`); `false` = nur Director. */
    val watchAvailable: StateFlow<Boolean>

    /** Stand der eigenen Uhr für „Wer zählt?“ (auch ohne Live-Verbindung, reagiert auf Änderungen). */
    val watchStatus: StateFlow<WatchStatus>

    /**
     * Versionsunterschied zu einem gekoppelten Gerät, auch ohne aktives Match (z. B. Schritt „Wer zählt?“); `null` = keiner.
     * Die In-Memory-Implementierung liefert immer `null`.
     */
    val updateRequired: StateFlow<UpdateRequired?>

    /** Stand der Uhr sofort neu prüfen (Schritt „Wer zählt?“, App im Vordergrund, „Erneut prüfen“). */
    fun refreshWatchStatus()

    /**
     * Legt ein Match an und macht es aktiv. Ein noch nicht beendetes aktives Match wird vorher mit
     * `MatchEnded(ABANDONED)` abgeschlossen (die UI fragt vorher nach).
     *
     * @param options wer zählt (ohne verfügbare Uhr immer dieses Handy) und Workout-Wunsch
     * @throws IllegalArgumentException wenn Format oder Lineup ungültig sind (`MatchFormatValidator`, `LineupValidator`)
     */
    fun start(
        setup: MatchSetup,
        options: StartOptions = StartOptions(),
    ): MatchId

    /** `PointWon(side)`; nur bei `IN_PROGRESS`/`PAUSED` und nur auf dem Zähler. */
    fun recordPoint(side: Side): Boolean

    /** `Undo` auf das UI-Undo-Ziel (nur Zähler). */
    fun undo(): Boolean

    /**
     * Nimmt das letzte Undo zurück (Undo auf Undo). Den Redo-Stapel leeren **nur** neue Events, die selbst UI-Undo-Ziel
     * sind (02 §7.3): Punkt, Satzende-Entscheidung, Korrektur, vorzeitiges Ende. Pause/Fortsetzen lassen ihn stehen
     * (wie Engine und Session). Der Stapel ist gerätelokal.
     */
    fun redo(): Boolean

    /** Antwort auf „Noch ein Satz?“ (nur `AWAITING_DECISION`); [matchTiebreak] nur, wenn `config.matchTiebreak ≠ null`. */
    fun continueDecision(
        anotherSet: Boolean,
        matchTiebreak: Boolean = false,
    ): Boolean

    /**
     * „Noch ein Satz?“ → weiter mit [kind] aus [LiveMatchSnapshot.nextSetOptions] (normaler Satz, Tiebreak bis 7,
     * Champions-Tiebreak bis 10; 02 §5.2). „Beenden“ bleibt `continueDecision(false)` bzw. [endMatch]. Nur Zähler.
     */
    fun startNextSet(kind: NextSetKind): Boolean

    /**
     * „Letzten Punkt von [side] abziehen“ (02 §7.3): Undo auf den letzten wirksamen Punkt dieser Seite, auch über
     * Satzgrenzen; [redo] stellt ihn wieder her. `false` ohne Punkt der Seite. Nur Zähler.
     */
    fun removeLastPointOf(side: Side): Boolean

    /** Aufschlägerwahl im Doppel (02 §6.5.5): [player] aus `state.serverChoice.players`. Nur Zähler. */
    fun chooseServer(player: PlayerId): Boolean

    /**
     * Aufschlag-Info für den Punkt [pointId] setzen/ändern/löschen (`null`), 02 §7.2: nur bei
     * [LiveMatchSnapshot.serveInfoEnabled], `DOUBLE_FAULT` nur für Punkte der Rückschlagseite. Nur Zähler.
     */
    fun setServeInfo(
        pointId: EventId,
        value: ServeOutcome?,
    ): Boolean

    /** Vorzeitiges Ende (02 §7 `MatchEnded`): [side] = aufgebende bzw. nicht angetretene Side, bei `ABANDONED` `null`. */
    fun endMatch(
        reason: EndReason,
        side: Side? = null,
    ): Boolean

    /** Aufschläger korrigieren: [retroactive] = `Correction(INITIAL_SERVER)` (ab Beginn), sonst `Correction(SERVER)` (ab jetzt). */
    fun correctServer(
        player: PlayerId,
        retroactive: Boolean,
    ): Boolean

    /** Unterbrechung (`Paused`, nur `IN_PROGRESS`). */
    fun pause(): Boolean

    /** Fortsetzung (`Resumed`, nur `PAUSED`). */
    fun resume(): Boolean

    /**
     * „Noch einer in gleicher Besetzung?“ nach einem regulär beendeten `BEST_OF`-Match (02 §5.2, U3): neues,
     * verknüpftes Match (`previousMatchId`, gleiche Besetzung und Satzformat, `mode = OPEN`, Erstaufschläger aus
     * `state.continuation`, gleicher Zähler). Das neue Match wird aktiv; `null`, wenn nicht möglich.
     */
    fun createFollowUp(): MatchId?

    /** Schließt ein beendetes aktives Match (es bleibt im Verlauf). `false`, solange es noch läuft. */
    fun dismiss(): Boolean

    /**
     * „Zählen übernehmen“ (02 §7.5, 04 §3.2): dieses Gerät wird Zähler (repliziertes `ScorerChanged`). Die UI fragt
     * vorher nach. `false`, wenn kein Match aktiv ist, es beendet ist oder dieses Gerät schon zählt.
     */
    fun takeOverScoring(): Boolean

    /** „Eingaben ansehen“: Eingaben des aktiven Matches, die die Rollenprüfung verworfen hat (02 §7.5), in Log-Ordnung. */
    fun rejectedInputs(): List<RejectedInput>
}

/** Art einer verworfenen Eingabe. */
enum class InputKind { POINT, UNDO, SET_DECISION, CORRECTION, PAUSE, RESUME, END, DETAIL }

/**
 * Eine wegen der Zählerrolle verworfene Eingabe (02 §7.5 Nr. 4) für „Eingaben ansehen“.
 *
 * @property matchTimeMs Zeitpunkt relativ zum Matchbeginn (Wanduhr des Geräts, nur Anzeige)
 * @property device Gerät, das die Eingabe gemacht hat
 * @property side Seite bei Punkten bzw. Aufgabe, sonst `null`
 */
data class RejectedInput(
    val eventId: EventId,
    val matchTimeMs: Long,
    val device: DeviceKind,
    val kind: InputKind,
    val side: Side?,
)

/**
 * Eintrag im Verlauf: ein beendetes Match (`status = FINISHED`).
 *
 * @property sets abgeschlossene Sätze (02 §8 `sets`)
 * @property interruptedSet bei vorzeitigem Ende (Aufgabe, Walkover, Abbruch) der eingefrorene laufende Satz, damit
 *   „6:4 2:1 (30:0) Aufg.“ angezeigt werden kann (02 §9.2/§9.22); sonst `null`
 * @property durationMs Wanduhr-Dauer von der Genesis bis zum beendenden Event
 */
data class MatchSummary(
    val matchId: MatchId,
    val setup: MatchSetup,
    val result: MatchResult,
    val sets: List<SetRecord>,
    val setsWon: SidePair<Int>,
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val interruptedSet: CurrentSet? = null,
) {
    val durationMs: Long get() = (finishedAtMs - startedAtMs).coerceAtLeast(0)
}

/** Vollständiger Datensatz eines Matches: Zusammenfassung, Endzustand und das Event-Log (für die Statistik). */
data class MatchRecord(
    val summary: MatchSummary,
    val finalState: MatchState,
    /** Genesis (`MatchCreated`) zuerst, danach alle Events in Log-Ordnung. */
    val events: List<ScoreEvent>,
)

/** Match-Historie (Verlauf, H9) und Match-Details (H8). */
interface MatchHistoryPort {
    /** F20: Ersteller darf Personen auch nach Matchende zuweisen; unabhängig von der Zählerrolle. */
    fun canAssignPlayer(matchId: MatchId): Boolean = false

    fun assignPlayer(
        matchId: MatchId,
        slot: PlayerId,
        player: PlayerId,
    ): Boolean = false

    /** Beendete Matches, neueste zuerst. Ein per Undo wieder geöffnetes Match verschwindet, bis es erneut endet. */
    val matches: StateFlow<List<MatchSummary>>

    /** Datensatz zu [matchId] (auch für das aktive Match), `null` wenn unbekannt. */
    fun record(matchId: MatchId): MatchRecord?

    /**
     * Löscht ein beendetes Match samt Event-Log endgültig von diesem Gerät (Datenschutzerklärung: Nutzer können
     * Matches löschen). Ist es das aktive (beendete, noch nicht geschlossene) Match, schließt die Implementierung es
     * **vorher** wie [LiveMatchPort.dismiss] – der Session-Adapter muss das ebenso tun.
     * `false` für unbekannte oder noch laufende Matches.
     */
    fun delete(matchId: MatchId): Boolean
}

/**
 * Gespeicherte Besetzung + Format für den Schnellstart (Start-Tab, „Revanche“).
 *
 * @property setup ohne `previousMatchId`
 */
data class FavoriteLineup(
    val id: String,
    val setup: MatchSetup,
)

/** Spieler und Schnellstart: eigener Spieler, Gäste, Favoriten und „Wie letztes Mal“. */
interface PlayersPort {
    /** Der Geräte-Besitzer (Side A, „Du“). Leerer Name = noch kein Profil. */
    val self: StateFlow<Player>

    /** Angelegte Gäste (nur Name, später mit Konten verknüpfbar), zuletzt verwendete zuerst. */
    val guests: StateFlow<List<Player>>

    /** Mitspieler aus den letzten Matches, eigene Sportart zuerst; ohne Besitzer und Duplikate. */
    fun recentPlayers(sport: Sport): List<Player>

    /** Favoriten-Besetzungen für den Schnellstart. */
    val favorites: StateFlow<List<FavoriteLineup>>

    /** Setup des zuletzt gestarteten Matches („Wie letztes Mal“), ohne `previousMatchId`; `null` = noch keins. */
    val lastSetup: StateFlow<MatchSetup?>

    /** Legt einen Gast an ([name] wird getrimmt, darf nicht leer sein). */
    fun addGuest(name: String): Player

    /** Speichert [setup] als Favoriten (ohne `previousMatchId`). */
    fun saveFavorite(setup: MatchSetup): FavoriteLineup

    /** Entfernt den Favoriten [id]; unbekannte IDs sind ein No-op. */
    fun removeFavorite(id: String)

    /**
     * Darf dieses Gerät [id] umbenennen? Nur Dummy-/Gastspieler und nur durch den Anleger (00 „Aliase“); den eigenen
     * Spieler (Alias, ab P2 im Profil) nie.
     */
    fun canRename(id: PlayerId): Boolean

    /** Benennt einen Dummy-/Gastspieler um ([name] getrimmt, nicht leer); `false`, wenn nicht erlaubt. */
    fun renameGuest(
        id: PlayerId,
        name: String,
    ): Boolean
}

/** Alle drei Ports aus einer Quelle (In-Memory für Tests/Previews, Session-Adapter in den Apps). */
interface MatchStore :
    LiveMatchPort,
    MatchHistoryPort,
    PlayersPort
