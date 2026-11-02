package eu.advantly.cloud.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Ein laufendes fremdes Match aus `cloud_my_live_matches_v1` (plan-live-mitspieler §8): ich bin Mitspieler, Zähler oder
 * Zuschauer, aber nicht Ersteller. [projection] ist bereinigt wie `visitor_view` (nie Telemetrie, nie Profil-/Geräte-IDs).
 */
@Serializable
data class LiveMatch(
    val matchId: String,
    /** `player` | `visitor` | `scorer`; unbekannte Werte späterer Server-Versionen verwirft die Anzeige. */
    val relation: String,
    val sport: String,
    val discipline: String,
    val participants: List<VisitorTeamMember> = emptyList(),
    val projection: JsonObject? = null,
    /** Vom zählenden Konto gemeldet; `null` = unbekannt (ältere App, noch kein Live-Stand) – nie als „offline“ zeigen. */
    val scorerConnected: Boolean? = null,
    /** Letzte Änderung am Server (ISO-8601). */
    val updatedAt: String,
)

/** Live-Liste für Handy und (über den Handy-Provider) Uhr; Fehler als [CloudException] wie bei [VisitorsPort]. */
interface LiveMatchesPort {
    /** Laufende Matches, die das Konto [profileId] verfolgen darf; trägt die Sitzung ein anderes Konto: `cloud_session_changed`. */
    suspend fun liveMatches(profileId: String): List<LiveMatch>
}
