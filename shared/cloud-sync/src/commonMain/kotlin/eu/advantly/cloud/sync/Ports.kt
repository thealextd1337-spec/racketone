package eu.advantly.cloud.sync

import eu.advantly.data.StoredEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

// Schnittstellenentwurf P2 (docs/p2-plan.md §6). Implementierungen folgen in P2-B/C:
// CloudBackend + CloudAuthPort + LiveChannelPort mit supabase-kt (nur Handy und Wear OS standalone),
// CloudOutboxStore in shared/data (Spalte cloud_state in match_event, Migration 2.sqm), CloudSyncPort als Worker.

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

    /** `get_visitor_match`: bereinigte Live-/Ergebnis-Sicht (Visitor, Turnierübersicht). */
    suspend fun visitorView(matchId: String): JsonObject
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
    /** `false` bis `complete_onboarding` (Länder-/Altersabfrage, Alias) erfolgreich war. */
    val onboarded: Boolean,
    val deletionRequested: Boolean = false,
)

enum class IdTokenProvider { GOOGLE, APPLE }

/**
 * Anmeldung (03 E3): Google/Apple nativ per ID-Token, sonst E-Mail-Code (OTP). Die Länder-/Altersabfrage (F13) zeigt die
 * App **vor** diesem Port; [completeOnboarding] wiederholt die Prüfung serverseitig und speichert kein Geburtsdatum.
 */
interface CloudAuthPort {
    val session: StateFlow<CloudSession?>

    suspend fun signInWithIdToken(
        provider: IdTokenProvider,
        idToken: String,
        rawNonce: String?,
    )

    suspend fun sendEmailCode(email: String)

    suspend fun verifyEmailCode(
        email: String,
        code: String,
    )

    /** `complete_onboarding(country, birthDate, alias, username)`; [birthDate] als ISO-Datum `yyyy-MM-dd`. */
    suspend fun completeOnboarding(
        country: String,
        birthDate: String,
        alias: String,
        username: String?,
    )

    suspend fun signOut()
}

/**
 * Lokale Outbox und Cursor (Implementierung in `shared/data`). Grundlage ist das vorhandene Event-Log: Events mit
 * `cloud_state = PENDING` sind ausstehend; Köpfe/Spieler/Geräte werden aus den lokalen Tabellen abgeleitet.
 */
interface CloudOutboxStore {
    fun pendingEvents(limit: Int): List<StoredEvent>

    fun pendingCount(): Long

    /** Match-Köpfe (inkl. Teilnehmer/Projektion), die seit dem letzten Upload geändert wurden. */
    fun pendingHeaders(): Map<String, MatchDto>

    fun pendingPlayers(): List<PlayerDto>

    fun knownDevices(): List<DeviceDto>

    fun apply(update: OutboxUpdate)

    fun pullCursor(): Long

    fun savePullCursor(cursor: Long)

    fun lastServerSeq(matchId: String): Long

    fun saveServerSeq(
        matchId: String,
        serverSeq: Long,
    )
}

/**
 * Realtime (private Kanäle, 03 E12): `match:<id>` liefert Engine-Events an Mitglieder (Anzeige, Director, Zähler),
 * `match-live:<id>` die bereinigte Live-Sicht (auch Visitor), `tournament:<id>` die Platz-Updates der Turnierleitung.
 * Nur öffnen, solange ein Live-Screen sichtbar ist (Verbindungslimit).
 */
interface LiveChannelPort {
    fun matchEvents(matchId: String): Flow<PulledEvent>

    fun liveView(matchId: String): Flow<JsonObject>

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
