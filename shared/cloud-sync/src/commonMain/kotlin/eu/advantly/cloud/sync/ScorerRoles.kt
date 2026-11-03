package eu.advantly.cloud.sync

// Zählen an ein anderes Konto weitergeben (F17, plan-live-mitspieler §3.4): RPCs aus 20261016000000_rate_limits.sql
// (invite_scorer), 20261102000300_scorer_handover.sql (respond_scorer_invitation), 20261015000600 (revoke_scorer) und
// 20261018000100_cloud_device_binding.sql (register_device). Offene Einladungen und angenommene Geräte liefert sync_pull
// (`PulledMatch.roles`, seit PR 8 mit `deviceId` für alle Mitglieder).

/**
 * Fehler als [CloudException] mit stabilem Grund (`scorer_not_allowed`, `is_visitor`, `no_pending_invitation`,
 * `device_not_owned`, `device_revoked`, `rate_limit_exceeded`, …; `forbidden` kommt als abgewiesene Berechtigung). Jeder
 * Aufruf nennt das erwartete Konto [profileId]; trägt die Sitzung ein anderes, geht nichts ins Netz.
 */
interface ScorerRolesPort {
    /** Ersteller lädt [scorerId] (Freund oder verknüpfter Mitspieler) als Zähler von [matchId] ein; Match muss hochgeladen sein. */
    suspend fun inviteScorer(
        profileId: String,
        matchId: String,
        scorerId: String,
    )

    /** Einladung annehmen (mit dem zählenden eigenen Gerät [deviceId]) oder ablehnen; erneute Annahme wechselt das Gerät. */
    suspend fun respondScorerInvitation(
        profileId: String,
        matchId: String,
        accept: Boolean,
        deviceId: String?,
    )

    /** Rolle entziehen (Ersteller) bzw. [scorerId] == [profileId]: selbst zurückgeben. */
    suspend fun revokeScorer(
        profileId: String,
        matchId: String,
        scorerId: String,
    )

    /**
     * Eigene Uhr [watchId] (gekoppelt mit diesem Handy [phoneId]) am Server registrieren, bevor sie als Zähl-Gerät angenommen
     * wird. Idempotent; der Server lehnt fremde (`device_owned_by_other`) und widerrufene Geräte (`device_revoked`) ab.
     */
    suspend fun registerWatch(
        profileId: String,
        watchId: String,
        phoneId: String,
    )
}

/** Meine Zähl-Rolle in einem gezogenen Mitglieds-Match (`invited` | `accepted` | …), sonst `null`. */
fun PulledMatch.roleOf(profileId: String): PulledRole? = roles.firstOrNull { it.profileId == profileId && it.role == "scorer" }
