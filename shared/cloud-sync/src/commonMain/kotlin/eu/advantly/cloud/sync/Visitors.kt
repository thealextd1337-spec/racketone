package eu.advantly.cloud.sync

import kotlinx.serialization.Serializable

// Zuschauer (F16/F25): RPCs aus supabase/migrations/20261016000000_rate_limits.sql (add_match_visitor),
// 20261015000600 (revoke_match_visitor, my_visitor_matches) und 20261029000100 (cloud_match_visitors_v1).

/** Aktiver Visitor eines Matches aus `cloud_match_visitors_v1`; [canRevoke]: ich habe ihn benannt oder das Match angelegt. */
@Serializable
data class MatchVisitor(
    val profileId: String,
    val alias: String? = null,
    val addedBy: String,
    val canRevoke: Boolean = false,
)

/**
 * Fehler als [CloudException] mit stabilem Grund (`visitor_must_be_friend`, `already_member`, `forbidden`,
 * `rate_limit_exceeded`, …). Jeder Aufruf nennt das erwartete Konto [profileId]; trägt die Sitzung inzwischen ein
 * anderes, geht nichts ins Netz (`cloud_session_changed`).
 */
interface VisitorsPort {
    /** Freund [visitorId] darf Live-Stand und Ergebnis von [matchId] sehen (Match muss schon hochgeladen sein). */
    suspend fun addVisitor(
        profileId: String,
        matchId: String,
        visitorId: String,
    )

    /** Freigabe beenden; [visitorId] == [profileId] heißt „nicht mehr zuschauen“. */
    suspend fun revokeVisitor(
        profileId: String,
        matchId: String,
        visitorId: String,
    )

    /** Aktive Visitor eines eigenen Matches (nur Ersteller und verknüpfte Spieler). */
    suspend fun matchVisitors(
        profileId: String,
        matchId: String,
    ): List<MatchVisitor>

    /** Matches, denen dieses Konto zuschaut (bereinigte Sicht, nie Events oder Telemetrie). */
    suspend fun myVisitorMatches(profileId: String): List<VisitorView>
}
