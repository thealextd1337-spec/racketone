package eu.advantly.cloud.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Wire-Format der Cloud-RPCs (Vertrag zu `supabase/migrations/…0500_rpc_sync.sql`, docs/p2-plan.md §5).
 * JSON-Schlüssel in camelCase = Kotlin-Property-Namen; Zeitpunkte als ISO-8601-Text (UTC), IDs als UUID-Text.
 */
val CloudJson: Json =
    Json {
        ignoreUnknownKeys = true // neuere Server-Felder brechen ältere Apps nicht
        explicitNulls = false
        encodeDefaults = true
    }

/** Ein Event als Tabellenzeile `match_events` (Spalten wie lokal `match_event`, ohne `confirmed`). */
@Serializable
data class EventRow(
    val id: String,
    val matchId: String,
    val deviceId: String,
    val deviceSeq: Long,
    val lamport: Long,
    val wallClockMs: Long,
    val parentId: String? = null,
    /** Payload-Typ (`PointWon`, …) – muss `payload["type"]` entsprechen, sonst lehnt der Server ab. */
    val type: String,
    /** Serialisierter `EventPayload` inkl. Diskriminator `type`, unverändert wie lokal gespeichert. */
    val payload: JsonObject,
)

/**
 * Event aus `pull_match_events` bzw. dem Realtime-Kanal `match:<id>:<epoch>`: Zeile plus Server-Cursor.
 * [redacted] = Klarname in der Payload wurde nach einer Kontolöschung ersetzt (Lösch-Ausnahme, docs/p2-plan.md §5.7):
 * der Client überschreibt seine lokale Kopie dieses Events.
 */
@Serializable
data class PulledEvent(
    val id: String,
    val matchId: String,
    val deviceId: String,
    val deviceSeq: Long,
    val lamport: Long,
    val wallClockMs: Long,
    val parentId: String? = null,
    val type: String,
    val payload: JsonObject,
    val serverSeq: Long,
    val redacted: Boolean = false,
) {
    fun toRow(): EventRow = EventRow(id, matchId, deviceId, deviceSeq, lamport, wallClockMs, parentId, type, payload)
}

/** Wasserstand je Gerät (`match_event_watermarks`) für die Anti-Entropie gegen die Cloud. */
@Serializable
data class EventWatermark(
    val deviceId: String,
    val maxSeq: Long,
    val eventCount: Long,
)

@Serializable
data class DeviceDto(
    val id: String,
    /** `phone` | `watch` */
    val kind: String,
    /** `android` | `ios` | `wearos` | `watchos` | `garmin` */
    val platform: String,
    val pairedWith: String? = null,
    val standalone: Boolean = false,
    val model: String? = null,
    val appVersion: String? = null,
)

/** Spieler-Eintrag: `self` = lokale Self-ID/Alias des Accounts, `guest` = Dummy-/Gastspieler (Name vergibt der Anleger). */
@Serializable
data class PlayerDto(
    val id: String,
    val kind: String,
    val displayName: String,
    val deleted: Boolean = false,
)

/** Platz im Lineup. [slotId] = PlayerId im Lineup (UUID oder `placeholder:…`). */
@Serializable
data class ParticipantDto(
    val slotId: String,
    /** `A` | `B` */
    val team: String,
    /** 1 | 2 */
    val position: Int,
    /** Snapshot-Name (Gäste); verknüpfte Accounts zeigt der Server mit ihrem Alias (F18). */
    val displayName: String,
    val playerId: String? = null,
    /** Account eines Freundes bzw. eigener Account – der Server verknüpft nur eigene/befreundete Profile. */
    val profileId: String? = null,
)

/** Von der Client-Engine berechnete Projektion (Server prüft nur Rechte und Monotonie über [eventCount]). */
@Serializable
data class ProjectionDto(
    /** `in_progress` | `paused` | `awaiting_decision` | `finished` (MatchStatus kleingeschrieben) */
    val status: String,
    /** `retired` | `walkover` | `abandoned` | `stopped` oder `null` */
    val resultReason: String? = null,
    val winner: String? = null,
    val startedAt: String? = null,
    val endedAt: String? = null,
    val summary: JsonObject? = null,
    /** Bereinigter Live-Stand für Anzeige/Visitor – nie Telemetrie (der Server filtert zusätzlich per Whitelist). */
    val liveState: JsonObject? = null,
    val eventCount: Int,
)

/** Match-Kopf. Neu anlegen bzw. ändern darf nur der Ersteller; [projection] dürfen alle Mitglieder mit Upload-Recht. */
@Serializable
data class MatchDto(
    val id: String,
    val sport: String? = null,
    val discipline: String? = null,
    val format: JsonObject? = null,
    val meta: JsonObject? = null,
    val startedAt: String? = null,
    val scheduledAt: String? = null,
    val previousMatchId: String? = null,
    val tournamentId: String? = null,
    val courtId: String? = null,
    val deleted: Boolean = false,
    val participants: List<ParticipantDto>? = null,
    val projection: ProjectionDto? = null,
)

/** Eingabe von `sync_push` – höchstens [MAX_EVENTS_PER_PUSH] Events. */
@Serializable
data class PushBatch(
    val devices: List<DeviceDto> = emptyList(),
    val players: List<PlayerDto> = emptyList(),
    val matches: List<MatchDto> = emptyList(),
    val events: List<EventRow> = emptyList(),
    val lifecycle: CloudPushLifecycle? = null,
) {
    companion object {
        const val MAX_EVENTS_PER_PUSH = 500
    }
}

/** Ergebnis je Element: `accepted`/`duplicate`/`ok`/`created`/`updated`/… oder `rejected` mit [reason]. */
@Serializable
data class ItemResult(
    val id: String? = null,
    val result: String,
    val reason: String? = null,
    /** Nur Matches: `applied` | `stale` | `ahead_of_events` (später erneut senden) | `forbidden` | … */
    val projection: String? = null,
    /** Nur Matches: Hinweise je Platz (`not_friend`, `link_rejected`, `player_not_owned`, `profile_already_in_match`). */
    val notes: List<ParticipantNote> = emptyList(),
)

@Serializable
data class ParticipantNote(
    val slotId: String,
    val note: String,
)

@Serializable
data class PushResponse(
    val devices: List<ItemResult> = emptyList(),
    val players: List<ItemResult> = emptyList(),
    val matches: List<ItemResult> = emptyList(),
    val events: List<ItemResult> = emptyList(),
)

@Serializable
data class PulledParticipant(
    val slotId: String,
    val team: String,
    val position: Int,
    val playerId: String? = null,
    val profileId: String? = null,
    /** Nur Mitglieder: `self` | `linked` (Freund vom Ersteller eingetragen, F23) | `assigned` | `confirmed` | `null` (nicht verknüpft) */
    val linkStatus: String? = null,
    val displayName: String,
    val deletedProfile: Boolean = false,
    val shareWithFriends: Boolean? = null,
    val hidden: Boolean? = null,
)

@Serializable
data class PulledRole(
    val profileId: String,
    val alias: String? = null,
    /** `scorer` | `director` */
    val role: String,
    /** `invited` | `accepted` | `declined` | `revoked` */
    val status: String,
    val deviceId: String? = null,
)

@Serializable
data class PulledMatch(
    val id: String,
    /** `member` (Basistabellen/Events/Live lesbar) | `feed` (bereinigte Sicht eines Freundes, ohne Events/Rollen/Live). */
    val access: String,
    /** Im Feed nur, wenn der Ersteller mein Freund ist. */
    val createdBy: String? = null,
    val sport: String,
    val discipline: String,
    val format: JsonObject,
    val meta: JsonObject,
    val previousMatchId: String? = null,
    val tournamentId: String? = null,
    val courtId: String? = null,
    val scheduledAt: String? = null,
    val startedAt: String? = null,
    val endedAt: String? = null,
    val status: String,
    val resultReason: String? = null,
    val winner: String? = null,
    val summary: JsonObject? = null,
    val liveState: JsonObject? = null,
    val projectionEventCount: Int = 0,
    /** Aktuelle Realtime-Kanalversion (nur Mitglieder): `match:<id>:<accessEpoch>`, `match-live:<id>:<accessEpoch>`. */
    val accessEpoch: Long? = null,
    /** Steigt bei Redaktion von Event-Payloads → Events neu laden und lokale Kopien überschreiben. */
    val redactionEpoch: Int = 0,
    val deleted: Boolean = false,
    val serverVersion: Long,
    val participants: List<PulledParticipant> = emptyList(),
    val roles: List<PulledRole> = emptyList(),
)

@Serializable
data class PulledPlayer(
    val id: String,
    val kind: String,
    val displayName: String,
    /** `true` = eigener Eintrag; `false` = fremder Dummy, der mir zugewiesen ist („War ich nicht“ möglich). */
    val owned: Boolean,
    val profileId: String? = null,
    val assignmentSource: String? = null,
    val deleted: Boolean = false,
    val serverVersion: Long,
)

@Serializable
data class PulledFriendship(
    val profileId: String,
    val alias: String? = null,
    /** `pending` | `accepted` | `declined` */
    val status: String,
    val outgoing: Boolean,
    val serverVersion: Long,
)

/** Antwort von `sync_pull(cursor, limit)`. */
@Serializable
data class PullResponse(
    val matches: List<PulledMatch> = emptyList(),
    val players: List<PulledPlayer> = emptyList(),
    val friendships: List<PulledFriendship> = emptyList(),
    val cursor: Long,
    val hasMore: Boolean = false,
    /**
     * Steigt, wenn sich meine Sichtbarkeit ohne neue Match-Version ändert (Freundschaft, Entzug, Teilen beendet).
     * Weicht er vom gespeicherten Wert ab: Vollabgleich ab Cursor 0 und nicht mehr gelieferte Cloud-Matches lokal
     * entfernen (lokale, noch nicht hochgeladene Matches bleiben).
     */
    val visibilityEpoch: Long = 0,
)

/** Bereinigte Sicht aus `get_visitor_match` (Visitor, Turnierübersicht) – nie IDs von Profilen/Geräten. */
@Serializable
data class VisitorView(
    val matchId: String,
    val sport: String,
    val discipline: String,
    val status: String,
    val resultReason: String? = null,
    val winner: String? = null,
    val startedAt: String? = null,
    val endedAt: String? = null,
    /** Aktueller Kanal `match-live:<id>:<epoch>`; nach dem Ereignis `rotate` neu holen. */
    val channel: String,
    val teams: List<VisitorTeamMember> = emptyList(),
    val live: JsonObject? = null,
    val result: JsonObject? = null,
)

@Serializable
data class VisitorTeamMember(
    val team: String,
    val position: Int,
    val name: String,
)
