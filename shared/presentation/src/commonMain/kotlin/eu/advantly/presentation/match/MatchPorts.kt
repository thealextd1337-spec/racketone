package eu.advantly.presentation.match

import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchFormat
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
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
)

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

    /** Nimmt das letzte Undo zurück (Undo auf Undo). Jedes neue Kommando außer Undo/Redo leert den Redo-Stapel. */
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
}

/**
 * Eintrag im Verlauf: ein beendetes Match (`status = FINISHED`).
 *
 * @property sets abgeschlossene Sätze (02 §8 `sets`)
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
