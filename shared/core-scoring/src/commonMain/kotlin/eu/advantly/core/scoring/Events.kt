package eu.advantly.core.scoring

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.EventId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchFormat
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMeta
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

/** Genesis: erstes Event im Log, legt Format, Besetzung und Metadaten fest. */
@Serializable
@SerialName("MatchCreated")
data class MatchCreated(
    val config: MatchFormat,
    val lineup: Lineup,
    val meta: MatchMeta = MatchMeta(),
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

/** Art des nächsten Satzes im Satzende-Dialog (OPEN). */
@Serializable
enum class NextSetKind {
    REGULAR,
    MATCH_TIEBREAK,
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

/** Grund eines vorzeitigen Matchendes (02 §7 `MatchEnded`). */
@Serializable
enum class EndReason {
    RETIRED,
    WALKOVER,
    ABANDONED,
}

/**
 * Match endet vorzeitig. [side] ist die aufgebende bzw. nicht angetretene Side (Pflicht außer bei `ABANDONED`).
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

/**
 * Auflösung einer Gabelung (01 §4.4, 02 §7.5): Ab [ancestor] haben die Zweige mit den Wurzel-Events [branches]
 * unabhängig voneinander gezählt. [keep] = Wurzel des behaltenen Zweigs – die Events aller anderen gelisteten Zweige
 * werden unwirksam (wie durch Undo); `null` = alle Zweige behalten (Quittung „zusammenführen“).
 *
 * Mehrere Auflösungen derselben Gabelung (gleicher [ancestor], gleiche Menge [branches]): Es gilt die in der Ordnung
 * erste wirksame, spätere stehen in `ignoredEvents`. Undo auf eine Auflösung öffnet die Gabelung wieder.
 */
@Serializable
@SerialName("ForkResolved")
data class ForkResolved(
    val ancestor: EventId,
    val branches: List<EventId>,
    val keep: EventId? = null,
) : EventPayload

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

/**
 * Optionales Punkt-Detail (02 §7.2, P3). Inkonsistente Details verwirft nur die Statistik
 * ([PointDetailRules.isConsistent]); der Score bleibt unberührt.
 */
@Serializable
data class PointDetail(
    val kind: PointDetailKind,
    val by: PlayerId? = null,
    val secondServe: Boolean? = null,
)
