package eu.advantly.cloud.sync

import eu.advantly.data.CloudPullEntityKind
import eu.advantly.data.CloudPullStore

// Geteilte Matches (P2-E, F23): RPCs aus supabase/migrations/20261015000700_rpc_zuweisung_claim.sql (Teilen/Ausblenden,
// „War ich nicht“), Standardwert per Spaltenrecht auf profile_settings (20261015000990_rechte.sql); alle über Cloud-Bindung.

/** Fehler als [CloudException] mit stabilem Grund (`no_participation`, `profile_not_active`, …). */
interface SharedMatchesPort {
    /** Eigene Sicht je Match: mit meinen Freunden teilen bzw. aus meiner Liste ausblenden; `null` lässt den Wert. */
    suspend fun setMyParticipation(
        matchId: String,
        shareWithFriends: Boolean? = null,
        hidden: Boolean? = null,
    )

    /** „War ich nicht“: löst meine Verknüpfung und sperrt sie für dieses Match. */
    suspend fun rejectMatchParticipation(matchId: String)

    /** Standard für eigene neue Matches (`share_matches_default`, serverseitig Standard aus). */
    suspend fun shareMatchesDefault(): Boolean

    suspend fun setShareMatchesDefault(share: Boolean)
}

/** Feed dieses Kontos aus dem Pull-Bestand; unlesbare oder gelöschte Snapshots werden ausgelassen. */
fun CloudPullStore.pulledFeed(profileId: String): List<PulledMatch> =
    feedMatches(profileId).mapNotNull { decodeMatch(it)?.takeIf { match -> match.access == "feed" && !match.deleted } }

/** Gezogener Mitglieds-Snapshot von [matchId] (Teilnehmer inkl. eigener Freigabe), sonst `null`. */
fun CloudPullStore.pulledMatch(
    profileId: String,
    matchId: String,
): PulledMatch? {
    val row = snapshot(profileId, CloudPullEntityKind.MATCH, matchId) ?: return null
    if (row.access != "member" || row.removalPending) return null
    return decodeMatch(row.json)?.takeIf { it.id == matchId && it.access == "member" && !it.deleted }
}

private fun decodeMatch(json: String): PulledMatch? = runCatching { CloudJson.decodeFromString(PulledMatch.serializer(), json) }.getOrNull()
