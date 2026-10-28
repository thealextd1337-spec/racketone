package eu.advantly.core.scoring

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.EventId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchFormat
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Hülle eines Events im append-only Log (01 §3.2). Ordnung: `(lamport, deviceId, id)`, siehe [EventOrdering].
 *
 * @property deviceSeq lückenlose Folge je `(matchId, deviceId)` – nur für Anti-Entropy im Sync, ohne Wirkung im Fold
 * @property wallClockMs nur Anzeige/Statistik und Doppel-Tap-Erkennung, **nie** für die Ordnung
 * @property parentId Log-Kopf, den das Gerät beim Erzeugen gesehen hat (Doppel-Tap-/Konflikterkennung, 01 §4.4)
 */
@Serializable
data class ScoreEvent(
    val id: EventId,
    val matchId: MatchId,
    val deviceId: DeviceId,
    val deviceSeq: Long,
    val lamport: Long,
    val wallClockMs: Long,
    val parentId: EventId?,
    val payload: EventPayload,
)

/**
 * Nutzdaten eines Events (02 §7). JSON: flaches Objekt mit Diskriminator `type` (z. B. `{"type":"PointWon","side":"A"}`),
 * wie in den Testvektoren.
 */
@Serializable
sealed interface EventPayload

/**
 * Genesis: erstes Event im Log, legt Format, Besetzung, Metadaten und den Zähler fest.
 *
 * @property createdBy Ersteller-Self für Personen-Zuweisungen, unabhängig von Lineup und Zähler; Alt-Logs: A[0].
 * @property scorerDeviceId Gerät, das zählt (Single-Scorer, 02 §7.5). Fehlt es (Alt-Logs), zählt das Gerät der Genesis.
 */
@Serializable
@SerialName("MatchCreated")
data class MatchCreated(
    val config: MatchFormat,
    val lineup: Lineup,
    val meta: MatchMeta = MatchMeta(),
    val scorerDeviceId: DeviceId? = null,
    val createdBy: PlayerId? = null,
) : EventPayload

/**
 * Zählerwechsel („Zählen übernehmen“, 02 §7.5): Ab seiner Position in der Ordnung zählt [scorerDeviceId]. Darf von
 * jedem Gerät kommen und unterliegt selbst keiner Rollenprüfung; Undo darauf ist ungültig (Rückgabe = erneut übernehmen).
 */
@Serializable
@SerialName("ScorerChanged")
data class ScorerChanged(
    val scorerDeviceId: DeviceId,
) : EventPayload

/** Side [side] hat den Ballwechsel gewonnen – das einzige Zähl-Event. */
@Serializable
@SerialName("PointWon")
data class PointWon(
    val side: Side,
    val detail: PointDetail? = null,
) : EventPayload

/** Macht das Event [target] unwirksam; Redo = Undo auf ein Undo (02 §7.3). */
@Serializable
@SerialName("Undo")
data class Undo(
    val target: EventId,
) : EventPayload

/**
 * Art des nächsten Satzes im Satzende-Dialog (OPEN, 02 §5.2). [TIEBREAK_7] und [MATCH_TIEBREAK_10] sind neu
 * (Entscheidung 2026-10-15) und gelten nur für Tennis/Padel; das JSON-Feld bleibt `nextSetKind` (abwärtskompatibel).
 */
@Serializable
enum class NextSetKind {
    /** Normaler Satz (bisheriger Default). */
    REGULAR,

    /** Match-Tiebreak nach `config.matchTiebreak` (nur, wenn das Format einen kennt) – bisherige Wire-Form. */
    MATCH_TIEBREAK,

    /** Tiebreak bis 7 (2 Vorsprung, Wechsel alle 6 Punkte) als eigener Satz, unabhängig von `config.matchTiebreak`. */
    TIEBREAK_7,

    /** Champions-Tiebreak bis 10 (2 Vorsprung, Wechsel alle 6 Punkte) als eigener Satz, unabhängig von `config.matchTiebreak`. */
    MATCH_TIEBREAK_10,
}

/** Antwort auf „Noch ein Satz?“ (nur bei `AWAITING_DECISION`). */
@Serializable
@SerialName("ContinueDecision")
data class ContinueDecision(
    val anotherSet: Boolean,
    val nextSetKind: NextSetKind = NextSetKind.REGULAR,
) : EventPayload

/** Art einer Korrektur (02 §7.1, §7.4). */
@Serializable
enum class CorrectionKind {
    /** Rückwirkend: Erstaufschläger des Matches. */
    INITIAL_SERVER,

    /** Ab jetzt: Aufschläger des laufenden Spiels bzw. Punktes. */
    SERVER,

    /** Neue Teamreihenfolge bzw. Startaufstellung zu Satz-/Spielbeginn. */
    SERVING_ORDER,

    /** Neue Rückschlagposition (Tennis/Padel-Doppel: ab nächstem Spiel; Badminton-Doppel: sofort). */
    RECEIVE_POSITIONS,
}

/**
 * Korrektur von Aufschläger, Reihenfolge oder Positionen (02 §7.1, §7.4). Flache Form: [kind] plus die je Art
 * benötigten Felder; fehlt ein Pflichtfeld, wird das Event übersprungen (`ignoredEvents`).
 *
 * | kind | Felder |
 * |---|---|
 * | `INITIAL_SERVER` | [player] |
 * | `SERVER` | [player], optional [serverNumber] (Pickleball), optional [receiver] (Tischtennis-Doppel) |
 * | `SERVING_ORDER` | [side], [players] (Permutation des Teams) |
 * | `RECEIVE_POSITIONS` | [side], [rightPlayer] |
 */
@Serializable
@SerialName("Correction")
data class Correction(
    val kind: CorrectionKind,
    val player: PlayerId? = null,
    val serverNumber: Int? = null,
    val receiver: PlayerId? = null,
    val side: Side? = null,
    val players: List<PlayerId>? = null,
    val rightPlayer: PlayerId? = null,
) : EventPayload {
    companion object {
        /** Rückwirkende Korrektur des Erstaufschlägers. */
        fun initialServer(player: PlayerId): Correction = Correction(CorrectionKind.INITIAL_SERVER, player = player)

        /** Aufschläger ab jetzt (optional Pickleball-Aufschlägernummer bzw. Tischtennis-Rückschläger). */
        fun server(
            player: PlayerId,
            serverNumber: Int? = null,
            receiver: PlayerId? = null,
        ): Correction = Correction(CorrectionKind.SERVER, player = player, serverNumber = serverNumber, receiver = receiver)

        /** Neue Teamreihenfolge für [side]. */
        fun servingOrder(
            side: Side,
            players: List<PlayerId>,
        ): Correction = Correction(CorrectionKind.SERVING_ORDER, side = side, players = players)

        /** Neue Rückschlagposition: [rightPlayer] steht rechts. */
        fun receivePositions(
            side: Side,
            rightPlayer: PlayerId,
        ): Correction = Correction(CorrectionKind.RECEIVE_POSITIONS, side = side, rightPlayer = rightPlayer)
    }
}

/**
 * Wahl des Aufschlägers im Doppel (Tennis/Padel, 02 §6.5.5, Entscheidung 2026-10-15): legt die teaminterne
 * Aufschlagreihenfolge von [side] für den Satz [set] fest ([player] schlägt das erste Aufschlagspiel des Teams auf).
 *
 * - Vor dem ersten Ballwechsel des Satzes („Game 1“) darf jeder der vier Spieler gewählt werden; gehört er zur
 *   anderen Side, eröffnet diese den Satz.
 * - Danach nur für die Side, die im Satz noch nicht aufgeschlagen hat („Game 2“).
 * - Ungültig (→ `ignoredEvents`): andere Sportart, Einzel, [set] ≠ laufender Satz, [player] nicht in [side], kein
 *   laufender Satz bzw. Status nicht `IN_PROGRESS`/`PAUSED`.
 *
 * Kein UI-Undo-Ziel; eine erneute Wahl im selben Fenster überschreibt (letzte gilt).
 *
 * @property set 0-basierter Satzindex (`current.setIndex`), schützt vor veralteten Wahlen nach Undo/Merge
 */
@Serializable
@SerialName("ServerChosen")
data class ServerChosen(
    val set: Int,
    val side: Side,
    val player: PlayerId,
) : EventPayload

/** Grund eines vorzeitigen Matchendes (02 §7 `MatchEnded`). */
@Serializable
enum class EndReason {
    /** Aufgabe von `side`; Sieger = Gegenseite. */
    RETIRED,

    /** `side` nicht angetreten (nur ohne wirksamen Ballwechsel); Sieger = Gegenseite. */
    WALKOVER,

    /** Abbruch ohne Wertung: kein Sieger, zählt nicht in Siege/Statistik (02 §9.9, F21). */
    ABANDONED,

    /**
     * „Beenden, wie es steht“ (02 §9.27, F21, neu in Protokoll 3): Das Ergebnis gilt mit dem aktuellen Stand. Sieger ist
     * die führende Side nach Sätzen, dann Spielen im laufenden Satz, dann Punkten im laufenden Spiel bzw. Satz; bei
     * Gleichstand kein Sieger. `side` wird ignoriert.
     */
    STOPPED,
}

/**
 * Match endet vorzeitig. [side] ist die aufgebende bzw. nicht angetretene Side (Pflicht bei `RETIRED`/`WALKOVER`,
 * bei `ABANDONED`/`STOPPED` ohne Bedeutung).
 */
@Serializable
@SerialName("MatchEnded")
data class MatchEnded(
    val reason: EndReason,
    val side: Side? = null,
) : EventPayload

/** Unterbrechung (Regen, Verletzung, Licht) – ohne Wirkung auf den Score. */
@Serializable
@SerialName("Paused")
data object Paused : EventPayload

/** Fortsetzung nach [Paused]. */
@Serializable
@SerialName("Resumed")
data object Resumed : EventPayload

/** Setzt bzw. löscht (`null`) nachträglich das Detail eines wirksamen `PointWon` – ohne Score-Wirkung. */
@Serializable
@SerialName("PointDetailSet")
data class PointDetailSet(
    val target: EventId,
    val detail: PointDetail?,
) : EventPayload

/** Art eines Punkt-Details (02 §7.2). */
@Serializable
enum class PointDetailKind {
    ACE,
    SERVICE_WINNER,
    DOUBLE_FAULT,
    WINNER,
    FORCED_ERROR,
    UNFORCED_ERROR,
    SERVICE_FAULT,
}

/** Aufschlag-Info eines Punktes (Tennis/Padel, 02 §7.2, Entscheidung 2026-10-15). */
@Serializable
enum class ServeOutcome {
    /** Der 1. Aufschlag war gut. */
    FIRST_SERVE_IN,

    /** Der 1. Aufschlag war ein Fehler, der 2. gut. */
    SECOND_SERVE_IN,

    /** Beide Aufschläge waren Fehler – nur zulässig, wenn die Rückschlagseite den Punkt gewonnen hat. */
    DOUBLE_FAULT,
}

/**
 * Optionales Punkt-Detail (02 §7.2, P3). Inkonsistente Details verwirft nur die Statistik
 * ([PointDetailRules.isConsistent]); der Score bleibt unberührt.
 *
 * @property kind Art des Punktendes; seit 2026-10-15 optional (ein Detail darf nur die Aufschlag-Info tragen). Alte
 *   Logs enthalten es immer.
 * @property serve Aufschlag-Info (nur Tennis/Padel); `null` = unbekannt. Ein `PointDetailSet` mit `DOUBLE_FAULT` auf
 *   einen Punkt der aufschlagenden Side ist ungültig (`ignoredEvents`, 02 §7.3).
 */
@Serializable
data class PointDetail(
    val kind: PointDetailKind? = null,
    val by: PlayerId? = null,
    val secondServe: Boolean? = null,
    val serve: ServeOutcome? = null,
)

/** Personenzuweisung eines unbesetzten Platzes (F20). Keine Zähloperation; nicht rückgängig machbar. */
@Serializable(with = PlayerAssignedSerializer::class)
@SerialName("PlayerAssigned")
data class PlayerAssigned(
    val slot: PlayerId,
    val player: Player?,
    val assignedBy: PlayerId,
    val legacySlot: Boolean = false,
    val creatorAlias: PlayerId? = null,
    val assignmentVersion: Int = 1,
    val playerId: PlayerId? = null,
) : EventPayload {
    companion object {
        /** Namensfreie Referenz; `null` hebt die aktuelle Zuweisung auf. */
        fun reference(
            slot: PlayerId,
            playerId: PlayerId?,
            assignedBy: PlayerId,
            legacySlot: Boolean = false,
            creatorAlias: PlayerId? = null,
        ): PlayerAssigned = PlayerAssigned(slot, null, assignedBy, legacySlot, creatorAlias, 2, playerId)
    }
}

/**
 * Ein Satz im korrigierten Ergebnis ([ResultCorrected]). [score] = Spiele bei `REGULAR`, sonst Punkte (Match-Tiebreak,
 * Pickleball-Spiel, Rally-Satz), je 0…99. [tiebreak] = Tiebreak-Punkte eines `REGULAR`-Satzes, der mit Tiebreak endete
 * (dann Pflicht, sonst verboten). [incomplete] = unvollständiger letzter Satz bei Aufgabe, Abbruch oder „Beenden, wie es
 * steht“ (nur der letzte Satz, ohne Tiebreak-Punkte; Tennis/Padel in ganzen Spielen).
 */
@Serializable
data class CorrectedSet(
    val kind: SetKind,
    val score: SidePair<Int>,
    val tiebreak: SidePair<Int>? = null,
    val incomplete: Boolean = false,
)

/**
 * Ergebnis nach Matchende korrigieren (neu in Protokoll 5): [sets] ersetzt die gezählten Sätze vollständig; Sieger und
 * Grund leitet [ResultCorrection] daraus ab, `result.corrected = true`. Die Punkte bleiben unverändert (Statistik).
 *
 * - Gültig nur bei `status = FINISHED` an seiner Position und mit plausiblen Sätzen ([ResultCorrection.check]), sonst
 *   `ignoredEvents`. Wird das Matchende später per Undo aufgehoben, verfällt die Korrektur damit (Position vor dem Undo).
 * - Das letzte gültige gilt (vollständiger Stand, keine Differenz); leere [sets] stellen das gezählte Ergebnis wieder her.
 * - Kein UI-Undo-Ziel; ein ausdrückliches `Undo` darauf macht es wie jedes Event unwirksam. Nur der Zähler (02 §7.5).
 */
@Serializable
@SerialName("ResultCorrected")
data class ResultCorrected(
    val sets: List<CorrectedSet>,
) : EventPayload

/**
 * Aufschläger eines Standardspiels (Tennis/Padel): [set] und [game] 0-basiert im (ggf. korrigierten) Ergebnis, [game] =
 * Spielstand `A + B` vor dem Spiel; Tiebreaks haben keinen Eintrag. [side] = Side von [player] (redundant, damit der
 * Export ohne Spieler-ID die Seite kennt; muss passen, sonst ist das Event ungültig).
 */
@Serializable
data class GameServer(
    val set: Int,
    val game: Int,
    val player: PlayerId,
    val side: Side,
)

/**
 * Aufschläger je Spiel nach Matchende korrigieren (neu in Protokoll 5, nur Tennis/Padel). [servers] ersetzt alle
 * bisherigen Angaben (leer = keine). Wirkt nicht auf den Score, nur auf die Zuordnung der Punkte in
 * [ScoringEngine.pointHistory] und [ScoringEngine.serviceGames] (Aufschlag-Statistik, Breakbälle).
 *
 * Gültig nur bei `status = FINISHED`, Tennis/Padel, Spieler aus dem Lineup, jedes Spiel höchstens einmal und vorhanden
 * ([ScoringEngine.serviceGames]); sonst `ignoredEvents`. Letztes gültiges gilt; Undo wie [ResultCorrected].
 */
@Serializable
@SerialName("GameServersCorrected")
data class GameServersCorrected(
    val servers: List<GameServer>,
) : EventPayload
