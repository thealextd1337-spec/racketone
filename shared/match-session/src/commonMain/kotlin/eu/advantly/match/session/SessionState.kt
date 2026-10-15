package eu.advantly.match.session

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.EventId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchFormat
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.MatchState
import eu.advantly.core.scoring.NextSetKind
import eu.advantly.core.scoring.ServeOutcome
import eu.advantly.core.scoring.ServerChoice
import eu.advantly.core.scoring.SidePair
import eu.advantly.core.scoring.TapConflict
import eu.advantly.device.link.LinkState
import eu.advantly.sync.protocol.CompatibilityStatus
import eu.advantly.sync.protocol.PeerKind

/**
 * Gesamtzustand einer [MatchSession] – genau das, was eine UI zum Rendern braucht.
 *
 * @property match offenes Match oder `null` (Startbildschirm)
 * @property dataRevision steigt, wenn sich gespeicherte Daten außerhalb des offenen Matches ändern (Löschung,
 *   Match-Kopf/-Stand eines anderen Matches, Spieler, Favoriten, Identität) – Signal für Listen-UIs (Verlauf), neu zu laden
 */
data class SessionState(
    val match: ActiveMatch?,
    val sync: SyncStatus,
    val dataRevision: Long = 0,
)

/**
 * Das offene Match.
 *
 * @property state kanonischer Engine-Zustand (02 §8): Stand, Aufschlag, Flags, `pending`, Effekte des letzten Events
 * @property pointsPlayed wirksam gezählte Ballwechsel
 * @property scorerDeviceId Gerät, das gerade zählt (02 §7.5); [isScorer] = dieses Gerät
 * @property scorerKind Art des zählenden Geräts (soweit bekannt)
 * @property rejectedByRoleCount Eingaben von Nicht-Zählern, die ignoriert wurden („N Eingaben nicht übernommen“)
 * @property canUndo dieses Gerät zählt und es gibt ein Undo-Ziel (02 §7.3)
 * @property canRedo das zuletzt auf diesem Gerät rückgängig gemachte Event lässt sich wiederherstellen
 * @property conflicts aktive Widersprüche (nur noch innerhalb eines Geräts möglich, 01 §4.4)
 * @property absorbedTaps als Doppel-Tap zusammengeführte Punkte
 * @property lastPoint zuletzt wirksam gezählter Ballwechsel – Ziel der Aufschlag-Leiste ([MatchSession.setServeInfo])
 * @property serveInfoEnabled Aufschlag-Info erfassbar: Tennis/Padel und Match-Option „Aufschlag-Statistik erfassen“
 * @property canRemovePoint je Seite: dieses Gerät zählt und es gibt einen wirksamen Punkt der Seite
 *   ([MatchSession.removeLastPointOf])
 * @property nextSetOptions bei `AWAITING_DECISION` die wählbaren Satzarten für [MatchSession.startNextSet], sonst leer
 */
data class ActiveMatch(
    val id: MatchId,
    val format: MatchFormat,
    val lineup: Lineup,
    val meta: MatchMeta,
    val createdAtMs: Long,
    val previousMatchId: MatchId?,
    val state: MatchState,
    val pointsPlayed: Int,
    val scorerDeviceId: DeviceId?,
    val scorerKind: PeerKind?,
    val isScorer: Boolean,
    val rejectedByRoleCount: Int,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val conflicts: List<TapConflict>,
    val absorbedTaps: List<EventId>,
    val eventCount: Int,
    val lastPoint: LastPoint? = null,
    val serveInfoEnabled: Boolean = false,
    val canRemovePoint: SidePair<Boolean> = SidePair(false, false),
    val nextSetOptions: List<NextSetKind> = emptyList(),
) {
    /**
     * Offene Aufschlägerwahl im Doppel (02 §6.5.5) aus dem Engine-Zustand: `game` 1/2, Side, wählbare Spieler, bereits
     * gewählter Spieler. `null` = kein Wahlfenster.
     */
    val serverChoice: ServerChoice? get() = state.serverChoice

    /** Eine Aufschlägerwahl ist offen und noch nicht getroffen – die UI fragt (ohne das Zählen zu blockieren). */
    val awaitingServerChoice: Boolean get() = state.serverChoice?.let { it.chosen == null } == true
}

/**
 * Der zuletzt gezählte Ballwechsel (für die Aufschlag-Leiste direkt nach dem Punkt).
 *
 * @property side Gewinner
 * @property servingSide aufschlagende Side vor dem Punkt
 * @property server Aufschläger (im Doppel nach Wahl bzw. Rotation)
 * @property serve aktuell erfasste Aufschlag-Info oder `null`
 * @property doubleFaultAllowed „Doppelfehler“ ist wählbar (die Rückschlagseite hat den Punkt gewonnen)
 */
data class LastPoint(
    val id: EventId,
    val side: Side,
    val servingSide: Side,
    val server: PlayerId,
    val serve: ServeOutcome?,
    val doubleFaultAllowed: Boolean,
)

/** Anzeige des Sync-Indikators (04 §3.2): verbunden · nur lokal (wird nachsynchronisiert) · Konflikt. */
enum class SyncIndicator {
    CONNECTED,
    LOCAL_ONLY,
    CONFLICT,
}

/**
 * Sync-Status für die Sync-Pill.
 *
 * @property link Zustand des Geräte-Links (`UNAVAILABLE` auch ohne Link, z. B. Standalone)
 * @property peers live erreichbare Gegenstellen
 * @property scorerConnected dieses Gerät zählt selbst oder der Zähler ist eine verbundene, kompatible Gegenstelle
 * @property pendingToPeers Ausgangs-Rückstand über **alle** Matches: Events, die noch keine Gegenstelle bestätigt hat
 *   (0 = „synchron“, sonst „N ausstehend“). Bestätigung = Ack der `Events`-Nachricht, `Summary` der Gegenstelle oder
 *   Empfang von dort; persistent im Event-Store (übersteht App-Neustarts).
 * @property lastEventReceivedAtMs Wanduhrzeit, zu der zuletzt ein neues Event empfangen wurde (Anzeige: „Stand vor X s“)
 * @property lastSyncAtMs Wanduhrzeit des letzten empfangenen Abgleichs (Summary/Events), sonst `null`
 */
data class SyncStatus(
    val link: LinkState,
    val peers: List<PeerInfo>,
    val indicator: SyncIndicator,
    val scorerConnected: Boolean,
    val pendingToPeers: Int,
    val lastEventReceivedAtMs: Long?,
    val lastSyncAtMs: Long?,
)

/**
 * Gegenstelle des Geräte-Links.
 *
 * @property peer Link-Adresse (Wear: Node-ID, Apple: `watch`/`phone`)
 * @property deviceId aus dem `Hello`, `null` bis es angekommen ist
 * @property kind aus dem `Hello`, `null` bis es angekommen ist
 * @property compatibility Ergebnis der Versionsprüfung, `null` bis zum `Hello`
 */
data class PeerInfo(
    val peer: String,
    val deviceId: DeviceId?,
    val kind: PeerKind?,
    val compatibility: CompatibilityStatus?,
)

/** Wer ein neues Match zählt (02 §7.5): dieses Gerät oder ein gekoppeltes (z. B. die Uhr). */
sealed interface ScorerChoice {
    data object ThisDevice : ScorerChoice

    data class Device(
        val deviceId: DeviceId,
    ) : ScorerChoice

    /**
     * Die eigene Uhr – auch ohne Live-Verbindung (W1-I): verbundene Uhr, sonst die einzige bekannte Uhr (aus einem
     * früheren `Hello`), sonst Platzhalter [MatchSession.PENDING_WATCH]. Beim Platzhalter übergibt die Session beim
     * ersten `Hello` einer Uhr per `ScorerChanged` an sie; bis dahin zählt niemand (Anzeige „Warte auf Uhr …“, Ausweg
     * `takeOverScoring()`).
     */
    data object MyWatch : ScorerChoice
}

/** Callback-Variante von [MatchSession.state] (für die Swift-Facade ohne Flows). Aufruf auf dem Scheduler-Thread. */
fun interface SessionListener {
    fun onStateChanged(state: SessionState)
}
