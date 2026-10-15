package eu.advantly.match.session

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.EventId
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchFormat
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.scoring.MatchState
import eu.advantly.core.scoring.TapConflict
import eu.advantly.device.link.LinkState
import eu.advantly.sync.protocol.CompatibilityStatus
import eu.advantly.sync.protocol.PeerKind

/**
 * Gesamtzustand einer [MatchSession] – genau das, was eine UI zum Rendern braucht.
 *
 * @property match offenes Match oder `null` (Startbildschirm)
 */
data class SessionState(
    val match: ActiveMatch?,
    val sync: SyncStatus,
)

/**
 * Das offene Match.
 *
 * @property state kanonischer Engine-Zustand (02 §8): Stand, Aufschlag, Flags, `pending`, Effekte des letzten Events
 * @property canUndo es gibt ein Undo-Ziel (02 §7.3)
 * @property canRedo das zuletzt auf diesem Gerät rückgängig gemachte Event lässt sich wiederherstellen
 * @property conflicts aktive Widersprüche (gleichzeitig Punkt für A und B, 01 §4.4) – Banner mit 1-Tap-Undo
 * @property absorbedTaps als Doppel-Tap zusammengeführte Punkte (Hinweis „Doppelte Eingabe zusammengeführt“)
 * @property fork offene Gabelung nach Offline-Zählen auf mehreren Geräten (Dialog „Version A / Version B / zusammenführen“)
 */
data class ActiveMatch(
    val id: MatchId,
    val format: MatchFormat,
    val lineup: Lineup,
    val meta: MatchMeta,
    val createdAtMs: Long,
    val previousMatchId: MatchId?,
    val state: MatchState,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val conflicts: List<TapConflict>,
    val absorbedTaps: List<EventId>,
    val fork: ForkInfo?,
    val eventCount: Int,
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
 * @property lastSyncAtMs Wanduhrzeit des letzten empfangenen Abgleichs (Summary/Events), sonst `null`
 */
data class SyncStatus(
    val link: LinkState,
    val peers: List<PeerInfo>,
    val indicator: SyncIndicator,
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

/**
 * Offene Gabelung (01 §4.4) für den Auflösungsdialog.
 *
 * @property branches die konkurrierenden Versionen; Index = Parameter für [MatchSession.resolveFork]
 */
data class ForkInfo(
    val id: String,
    val branches: List<ForkVersion>,
)

/**
 * Eine Version im Fork-Dialog, z. B. „Version Uhr (4:2)“.
 *
 * @property devices Geräte, die in diesem Zweig gezählt haben; [kinds] deren Art (soweit bekannt)
 * @property includesThisDevice dieses Gerät hat in dem Zweig gezählt
 * @property preview Zustand, wenn nur diese Version gilt
 * @property points Anzahl wirksamer Punkte im Zweig
 */
data class ForkVersion(
    val devices: List<DeviceId>,
    val kinds: List<PeerKind>,
    val includesThisDevice: Boolean,
    val preview: MatchState,
    val points: Int,
)

/** Callback-Variante von [MatchSession.state] (für die Swift-Facade ohne Flows). Aufruf auf dem Scheduler-Thread. */
fun interface SessionListener {
    fun onStateChanged(state: SessionState)
}
