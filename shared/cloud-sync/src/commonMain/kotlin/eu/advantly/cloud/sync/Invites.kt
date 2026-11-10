package eu.advantly.cloud.sync

// Einladungen per Link/QR (docs/plan-einladungen.md §3): create_invite/redeem_invite aus 20261015000700 (Rate-Limit-Fassung
// 20261016000000, Fixes 0.6.4 in 20261105000100), preview_invite/revoke_invite aus der 0.7.0-Migration. Alle prüfen die
// Cloud-Bindung (require_cloud_binding). Der Klartext-Token geht nur als RPC-Argument hinaus und erscheint in keinem
// Rückgabewert dieses Ports außer [InvitesPort.createInvite].

enum class InviteKind(
    val wire: String,
) {
    FRIEND("friend"),
    CLAIM_PLAYER("claim_player"),
    ;

    internal companion object {
        fun of(wire: String?): InviteKind? = entries.firstOrNull { it.wire == wire }
    }
}

/** Status aus `preview_invite`; `INVALID` deckt bewusst unbekannt, blockiert, gelöscht und redigiert ab (kein Orakel). */
enum class InviteStatus(
    val wire: String,
) {
    OK("ok"),
    ALREADY_FRIENDS("already_friends"),
    ALREADY_CLAIMED("already_claimed"),
    OWN("own"),
    EXPIRED("expired"),
    USED("used"),
    REVOKED("revoked"),
    INVALID("invalid"),
}

/** Vorschau vor dem Einlösen; bei [InviteStatus.INVALID] sind alle übrigen Felder `null`. */
data class InvitePreview(
    val status: InviteStatus,
    val kind: InviteKind? = null,
    val inviterId: String? = null,
    val inviterAlias: String? = null,
    /** Nur bei [InviteKind.CLAIM_PLAYER] und [InviteStatus.OK]. */
    val guestName: String? = null,
    val matchCount: Int? = null,
    val expiresAtMillis: Long? = null,
)

/** Ergebnis von `redeem_invite`; [alreadyFriends]: Freundes-Link unter Freunden, nicht verbraucht (0.6.4 S4). */
data class InviteRedemption(
    val kind: InviteKind,
    val friendId: String,
    val playerId: String? = null,
    val matchCount: Int = 0,
    val alreadyFriends: Boolean = false,
)

/** Eigene offene (nicht benutzte, nicht zurückgezogene) Einladung; ob sie abgelaufen ist, entscheidet der Aufrufer. */
data class OpenInvite(
    val id: String,
    val kind: InviteKind,
    val playerId: String?,
    val expiresAtMillis: Long,
)

/**
 * Fehler als [CloudException] mit stabilem Grund (`invalid_invite`, `own_invite`, `already_assigned`, `player_not_found`,
 * `player_redacted`, `too_many_open_invites`, `invite_not_found`, `rate_limit_exceeded`, …). Jeder Aufruf nennt das
 * erwartete Konto [profileId]; trägt die Sitzung inzwischen ein anderes, geht nichts ins Netz.
 */
interface InvitesPort {
    /** Neuer Link-Token (64 Hex-Zeichen); [playerId] nur bei [InviteKind.CLAIM_PLAYER]. */
    suspend fun createInvite(
        profileId: String,
        kind: InviteKind,
        playerId: String?,
        ttlMinutes: Int,
    ): String

    suspend fun previewInvite(
        profileId: String,
        token: String,
    ): InvitePreview

    suspend fun redeemInvite(
        profileId: String,
        token: String,
    ): InviteRedemption

    suspend fun revokeInvite(
        profileId: String,
        inviteId: String,
    )

    /** Eigene offene Einladungen (PostgREST-Select auf `invites`, RLS: nur eigene Zeilen, ohne `token_hash`). */
    suspend fun openInvites(profileId: String): List<OpenInvite>
}

/** Format des Servers: `encode(… , 'hex')` aus zwei UUIDs. */
internal val INVITE_TOKEN = Regex("^[0-9a-f]{64}$")

/** Obergrenze des Servers (14 Tage), zugleich Gültigkeit von Freundes- und Claim-Links. */
const val MAX_INVITE_TTL_MINUTES = 20_160
