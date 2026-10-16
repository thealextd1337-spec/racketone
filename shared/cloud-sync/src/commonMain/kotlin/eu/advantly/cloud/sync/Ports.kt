package eu.advantly.cloud.sync

import eu.advantly.data.StoredEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

// Schnittstellen P2 (docs/p2-plan.md §6). CloudAuthPort ist für das Android-Handy implementiert (P2-C).
// CloudBackend + LiveChannelPort folgen in P2-B/H mit supabase-kt (Handy und später Wear OS standalone),
// CloudOutboxStore als Adapter HIER in cloud-sync über neue Primitive in shared/data (Spalte cloud_state, Cursor-Tabelle,
// Migration 2.sqm) – data kennt cloud-sync nicht (kein Modulzyklus), CloudSyncPort als Worker.

/** Server-RPCs (PostgREST `rpc/…`). Alle Aufrufe sind idempotent bzw. lesend; Fehler als [CloudException]. */
interface CloudBackend {
    /** `sync_push`: Geräte, Spieler, Match-Köpfe, Events (≤ 500), Projektionen in einer Transaktion. */
    suspend fun push(batch: PushBatch): PushResponse

    /** `sync_pull`: sichtbare Matches/Spieler/Freundschaften mit `serverVersion > cursor`. */
    suspend fun pull(
        cursor: Long,
        limit: Int = 200,
    ): PullResponse

    /** `pull_match_events`: Events eines Matches nach Server-Cursor (RLS: nur Mitglieder, nie Visitor). */
    suspend fun pullEvents(
        matchId: String,
        afterServerSeq: Long,
        limit: Int = 500,
    ): List<PulledEvent>

    /** `match_event_watermarks`: Wasserstand je Gerät (Lückenprüfung wie die Geräte-Anti-Entropie). */
    suspend fun watermarks(matchId: String): List<EventWatermark>

    /** `get_visitor_match`: bereinigte Live-/Ergebnis-Sicht (Visitor, Mitglieder; nicht Feed). */
    suspend fun visitorView(matchId: String): VisitorView
}

/** Fachlicher Fehler eines RPCs: [reason] = stabiler Grund-Schlüssel (`profile_not_active`, `age_below_minimum`, …). */
class CloudException(
    val reason: String,
    val permission: Boolean,
    cause: Throwable? = null,
) : Exception(reason, cause)

/** Angemeldete Sitzung (Profil-ID = `auth.uid()`). */
data class CloudSession(
    val profileId: String,
    /** `false` bis `finish_onboarding` (Alias nach serverseitigem Altersgate) erfolgreich war. */
    val onboarded: Boolean,
    val deletionRequested: Boolean = false,
)

enum class IdTokenProvider { GOOGLE, APPLE }

/** Handy-Auth. Geburtsdatum und Ticket leben nur für den laufenden Anmeldeversuch im Speicher. */
interface CloudAuthPort {
    val session: StateFlow<CloudSession?>

    suspend fun issueAgeTicket(
        country: String,
        birthDate: String,
    ): String

    suspend fun cancelAgeTicket(ticket: String)

    suspend fun signInWithIdToken(
        provider: IdTokenProvider,
        idToken: String,
        rawNonce: String?,
        ticket: String,
    )

    suspend fun linkGoogle(
        idToken: String,
        rawNonce: String?,
    )

    suspend fun sendEmailCode(
        email: String,
        ticket: String,
    )

    suspend fun verifyEmailCode(
        email: String,
        code: String,
    )

    suspend fun completeOnboarding(
        alias: String,
        username: String?,
    )

    suspend fun refreshProfile()

    /** Lokale Sitzung wird auch offline gelöscht; serverseitiger Widerruf benötigt Netz. */
    suspend fun signOut()
}

/**
 * Lokale Outbox und Cursor. Implementierung als Adapter in `cloud-sync` über Primitive von `shared/data`
 * (`cloud_state` je Event, Cursor-Tabelle) – Abhängigkeit nur cloud-sync → data. Grundlage ist das vorhandene
 * Event-Log: Events mit `cloud_state = PENDING` sind ausstehend; Köpfe/Spieler/Geräte stammen aus den lokalen Tabellen.
 */
interface CloudOutboxStore {
    fun pendingEvents(limit: Int): List<StoredEvent>

    fun pendingCount(): Long

    /** Match-Köpfe (inkl. Teilnehmer/Projektion), die seit dem letzten Upload geändert wurden. */
    fun pendingHeaders(): Map<String, MatchDto>

    fun pendingPlayers(): List<PlayerDto>

    fun knownDevices(): List<DeviceDto>

    fun apply(update: OutboxUpdate)

    /** Matches, deren Projektion der Server noch nicht übernommen hat (`ahead_of_events`) – nächster Lauf. */
    fun markProjectionPending(matchIds: Set<String>)

    /** Gespeicherte `visibilityEpoch`; Abweichung → Vollabgleich. */
    fun visibilityEpoch(): Long

    fun saveVisibilityEpoch(epoch: Long)

    fun pullCursor(): Long

    fun savePullCursor(cursor: Long)

    fun lastServerSeq(matchId: String): Long

    fun saveServerSeq(
        matchId: String,
        serverSeq: Long,
    )
}

/**
 * Realtime (private, versionierte Kanäle, 03 E12): `match:<id>:<epoch>` liefert Engine-Events an Mitglieder,
 * `match-live:<id>:<epoch>` die bereinigte Live-Sicht (auch Visitor), `tournament:<id>:<epoch>` die Platz-Updates.
 * Auf das Ereignis `rotate` (Rechteänderung) den aktuellen Kanal per RPC holen und neu beitreten; verpasste Daten
 * holt der Pull. Nur öffnen, solange ein Live-Screen sichtbar ist (Verbindungslimit).
 */
interface LiveChannelPort {
    fun matchEvents(matchId: String): Flow<PulledEvent>

    fun liveView(matchId: String): Flow<VisitorView>

    fun tournament(tournamentId: String): Flow<JsonObject>
}

/** Zustand für UI und Diagnose („in der Cloud gesichert“, „N ausstehend“, abgewiesene Events). */
data class CloudSyncState(
    val signedIn: Boolean = false,
    val pendingEvents: Long = 0,
    val rejectedEvents: Int = 0,
    val lastSuccessAtMs: Long? = null,
    val lastError: String? = null,
    val running: Boolean = false,
)

/** Sync-Worker: Push der Outbox, dann Delta-Pull; Auslöser App-Start, Netz da, Event (debounced), Push-Nachricht. */
interface CloudSyncPort {
    val state: StateFlow<CloudSyncState>

    suspend fun syncNow()
}
