package eu.advantly.presentation.match

import eu.advantly.core.model.DeviceId
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
import eu.advantly.core.scoring.ScoreEvent
import eu.advantly.core.scoring.SetRecord
import eu.advantly.core.scoring.SidePair
import kotlinx.coroutines.flow.StateFlow

/*
 * Port-Schnittstellen der Phone-UI (W1-C). Die Screens kennen nur diese Interfaces; heute erfüllt sie die
 * engine-gestützte In-Memory-Implementierung (`InMemoryMatchStore`), später `shared/match-session`
 * (Persistenz, Sync, Mehrgeräte). Vertrag für alle Implementierungen:
 *
 * - **Perspektive:** Side A ist immer das Team des Geräte-Besitzers („Wir“), Side B die Gegner (04 §3.2).
 * - **Local-first, synchron:** Ein Kommando (z. B. [LiveMatchPort.recordPoint]) wirkt sofort lokal; kehrt es mit
 *   `true` zurück, zeigt [LiveMatchPort.active] bereits den neuen Zustand. Die UI liest den Wert direkt nach dem
 *   Aufruf (Haptik und Animation im selben Frame, Latenzziel S4). Änderungen von anderen Geräten kommen nur über
 *   den Flow.
 * - **Keine ungültigen Events:** Kommandos, deren Vorbedingung (02 §7) nicht erfüllt ist, liefern `false` und
 *   schreiben nichts ins Log (02 §7.3 Schritt 4: „Die UI DARF solche Events nicht erzeugen“).
 * - Alle Methoden werden nur vom UI-Thread aufgerufen.
 */

/**
 * Alles, was vor dem ersten Punkt feststeht: Genesis `MatchCreated(config, lineup, meta)` plus Anzeigenamen.
 *
 * @property players alle Spieler aus [lineup] mit Anzeigenamen (der eigene Spieler darf einen leeren Namen haben,
 *   die UI zeigt dann „Du“)
 * @property previousMatchId verknüpftes Vor-Match bei „Noch einer in gleicher Besetzung?“ (02 §5.2)
 */
data class MatchSetup(
    val format: MatchFormat,
    val lineup: Lineup,
    val meta: MatchMeta = MatchMeta(),
    val players: List<Player>,
    val previousMatchId: MatchId? = null,
) {
    val sport: Sport get() = format.sport

    /** Spieler zur ID oder `null`. */
    fun player(id: PlayerId): Player? = players.firstOrNull { it.id == id }

    /** Spieler der Side [side] in Lineup-Reihenfolge. */
    fun team(side: Side): List<Player> = lineup.players(side).mapNotNull(::player)
}

/**
 * Momentaufnahme des laufenden Matches für die UI.
 *
 * @property canUndo es gibt ein UI-Undo-Ziel (02 §7.3: letztes wirksames PointWon/ContinueDecision/Correction/MatchEnded)
 * @property canRedo der Redo-Stapel ist nicht leer (Redo = Undo auf das letzte Undo)
 * @property startedAtMs Erstellzeit (Wanduhr der Genesis)
 * @property finishedAtMs Wanduhr des Events, das das Match beendet hat; `null`, solange es läuft
 * @property followUpMatchId bereits angelegtes Folge-Match (02 §9.1: UI warnt vor Undo)
 * @property revision steigt mit jeder Änderung (auch bei gleichem Zustand, z. B. Redo-Stapel)
 * @property pointsPlayed wirksam angewendete Ballwechsel (`PointWon`, nicht rückgängig gemacht, nicht absorbiert,
 *   nicht übersprungen). Auch Ballwechsel ohne Punkt zählen (Pickleball Side-Out) – maßgeblich für `WALKOVER` (02 §9.8)
 * @property sync Verbindungszustand zu anderen Geräten (Sync-Pill, 04 §3.2)
 * @property conflicts aktive Widersprüche: gleichzeitige Punkte für A und B (01 §4.4) – UI zeigt Banner mit 1-Tap-Undo
 * @property absorbedTaps Anzahl als Doppel-Tap zusammengeführter Eingaben (01 §4.4); steigt sie, zeigt die UI einen Hinweis
 * @property openForks offene Verzweigungen nach Offline-Zählen auf mehreren Geräten, Auflösung über [LiveMatchPort.resolveFork]
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
    val sync: SyncInfo = SyncInfo.LOCAL_ONLY,
    val conflicts: List<ConflictInfo> = emptyList(),
    val absorbedTaps: Int = 0,
    val openForks: List<ForkInfo> = emptyList(),
)

/** Verbindungszustand des aktiven Matches (Sync-Pill: verbunden · nur lokal · Konflikt). */
enum class SyncState {
    /** Mindestens ein weiteres Gerät ist verbunden und auf dem gleichen Stand. */
    CONNECTED,

    /** Nur dieses Gerät; Events werden nachsynchronisiert. */
    LOCAL_ONLY,

    /** Widerspruch oder offene Verzweigung, Nutzer muss entscheiden. */
    CONFLICT,
}

/**
 * @property peers verbundene weitere Geräte (Uhr, zweites Handy)
 */
data class SyncInfo(
    val state: SyncState,
    val peers: Int,
) {
    companion object {
        val LOCAL_ONLY: SyncInfo = SyncInfo(SyncState.LOCAL_ONLY, 0)
    }
}

/**
 * Widerspruch (01 §4.4): zwei wirksame `PointWon` mit gleichem `parentId`, einer für A, einer für B.
 *
 * @property pointA Event-ID des Punkts für A
 * @property pointB Event-ID des Punkts für B
 */
data class ConflictInfo(
    val id: String,
    val pointA: EventId,
    val pointB: EventId,
)

/** Art des Geräts eines Zweigs (für „Version Uhr / Version Handy“). */
enum class DeviceKind { PHONE, WATCH, OTHER }

/**
 * Ein Zweig einer Verzweigung.
 *
 * @property preview Zustand, wenn nur dieser Zweig gilt (Vorschau aus der Engine)
 */
data class ForkBranch(
    val deviceId: DeviceId,
    val device: DeviceKind,
    val preview: MatchState,
)

/** Offene Verzweigung: zwei Geräte haben unabhängig weitergezählt. Auflösung als repliziertes Event `ForkResolved`. */
data class ForkInfo(
    val forkId: String,
    val branchA: ForkBranch,
    val branchB: ForkBranch,
    /** Zustand, wenn beide Zweige zusammengeführt werden. */
    val mergedPreview: MatchState,
)

/** Wahl bei der Auflösung einer Verzweigung (`ForkResolved.keep`). */
enum class ForkChoice { BRANCH_A, BRANCH_B, BOTH }

/** Live-Zählen des aktiven Matches. Es gibt höchstens ein aktives Match pro Gerät. */
interface LiveMatchPort {
    /** Aktives Match (läuft, wartet auf Entscheidung oder ist beendet, aber noch nicht geschlossen); `null` = keins. */
    val active: StateFlow<LiveMatchSnapshot?>

    /**
     * Legt ein Match an und macht es aktiv. Ein noch nicht beendetes aktives Match wird vorher mit
     * `MatchEnded(ABANDONED)` abgeschlossen (die UI fragt vorher nach).
     *
     * @throws IllegalArgumentException wenn Format oder Lineup ungültig sind (`MatchFormatValidator`, `LineupValidator`)
     */
    fun start(setup: MatchSetup): MatchId

    /** `PointWon(side)`; nur bei `IN_PROGRESS`/`PAUSED`. */
    fun recordPoint(side: Side): Boolean

    /** `Undo` auf das UI-Undo-Ziel. */
    fun undo(): Boolean

    /**
     * Nimmt das letzte Undo zurück (Undo auf Undo). Den Redo-Stapel leeren **nur** neue Events, die selbst UI-Undo-Ziel
     * sind (02 §7.3): Punkt, Satzende-Entscheidung, Korrektur, vorzeitiges Ende. Pause/Fortsetzen, Konflikt- und
     * Verzweigungsauflösung lassen ihn stehen (wie Engine und Session).
     */
    fun redo(): Boolean

    /** Antwort auf „Noch ein Satz?“ (nur `AWAITING_DECISION`); [matchTiebreak] nur, wenn `config.matchTiebreak ≠ null`. */
    fun continueDecision(
        anotherSet: Boolean,
        matchTiebreak: Boolean = false,
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
     * `state.continuation`). Das neue Match wird aktiv; `null`, wenn nicht möglich.
     */
    fun createFollowUp(): MatchId?

    /** Schließt ein beendetes aktives Match (es bleibt im Verlauf). `false`, solange es noch läuft. */
    fun dismiss(): Boolean

    /** Löst die Verzweigung [forkId] auf (repliziertes Event `ForkResolved(forkId, keep)`); `false`, wenn unbekannt. */
    fun resolveFork(
        forkId: String,
        keep: ForkChoice,
    ): Boolean

    /** Widerspruch [conflictId] bestätigen: beide Punkte bleiben, der Hinweis verschwindet. */
    fun dismissConflict(conflictId: String): Boolean

    /** 1-Tap-Korrektur eines Widerspruchs: nimmt den Punkt für [side] zurück (`Undo` auf dessen Event). */
    fun undoConflictPoint(
        conflictId: String,
        side: Side,
    ): Boolean
}

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
}
