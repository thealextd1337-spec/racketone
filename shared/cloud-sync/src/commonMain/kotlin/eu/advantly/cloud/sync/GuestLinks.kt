package eu.advantly.cloud.sync

import eu.advantly.data.CloudPullEntityKind
import eu.advantly.data.CloudPullStore

// Gast mit Freund verknüpfen (plan-0.6.3 §3.5): RPCs aus supabase/migrations/20261015000700_rpc_zuweisung_claim.sql,
// assign_player in der Rate-Limit-Fassung 20261016000000. Alle drei prüfen seit 20261018000100 die Cloud-Bindung
// (require_cloud_binding samt Versionssperre und Zustimmung) – eigene cloud_*_v1-Hüllen sind deshalb nicht nötig.

/**
 * Fehler als [CloudException] mit stabilem Grund (`assignee_must_be_friend`, `already_assigned`,
 * `assignment_rejected_before`, `player_not_found`, `player_redacted`, `not_assigned_to_you`, …). Jeder Aufruf nennt
 * das erwartete Konto [profileId]; trägt die Sitzung inzwischen ein anderes, geht nichts ins Netz.
 */
interface GuestLinksPort {
    /** Alle bisherigen Matches des eigenen Gasts [playerId] dem Freund [friendId] zuordnen; Anzahl der Matches. */
    suspend fun assignGuest(
        profileId: String,
        playerId: String,
        friendId: String,
    ): Int

    /** Eigene Zuordnung zurücknehmen. */
    suspend fun unassignGuest(
        profileId: String,
        playerId: String,
    )

    /** „Das war ich nicht“ des Freundes: Zuordnung des fremden Gasts [playerId] lösen und für immer sperren. */
    suspend fun rejectGuestAssignment(
        profileId: String,
        playerId: String,
    )
}

/** Gezogener Spieler [playerId] dieses Kontos (eigener Gast mit Zuordnung bzw. mir zugeordneter fremder Gast), sonst `null`. */
fun CloudPullStore.pulledPlayer(
    profileId: String,
    playerId: String,
): PulledPlayer? =
    snapshot(profileId, CloudPullEntityKind.PLAYER, playerId)?.let {
        runCatching { CloudJson.decodeFromString(PulledPlayer.serializer(), it.json) }.getOrNull()
    }
