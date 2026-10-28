package eu.advantly.cloud.sync

import eu.advantly.data.CloudPullStore
import kotlinx.serialization.Serializable

// Freunde (P2-D, docs/03-backend-datenmodell.md §6): RPCs aus supabase/migrations/20261015000400_rpc_profil_freunde.sql,
// Rate-Limit 20261016000000, Freundescode 20261016000200; alle über Cloud-Bindung und Installationsnachweis.

/** Exakter Treffer aus `find_profile` (Benutzername oder Freundescode, nie Teiltreffer). */
@Serializable
data class FoundProfile(
    val id: String,
    val alias: String? = null,
    val username: String? = null,
)

/** Eigene Blockierung aus `cloud_my_blocks_v1`; [alias] nur bei aktivem Profil, sonst keine Profildaten. */
@Serializable
data class BlockedProfile(
    val profileId: String,
    val createdAt: String,
    val alias: String? = null,
)

/** Antwort von `send_friend_request`: neue Anfrage, Gegenanfrage angenommen oder schon befreundet. */
enum class FriendRequestResult { REQUESTED, ACCEPTED, ALREADY_FRIENDS }

/** Fehler als [CloudException] mit stabilem Grund (`rate_limit_exceeded`, `profile_not_found`, `request_cooldown`, …). */
interface FriendsPort {
    suspend fun friendCode(): String

    /** Eigener Alias und Benutzername fürs Profil; `null`: nicht verfügbar (Testdoubles). */
    suspend fun ownProfile(): FoundProfile? = null

    /** `null`: kein Treffer (auch Blockierte und nicht auffindbare Benutzernamen sind nicht unterscheidbar). */
    suspend fun findProfile(query: String): FoundProfile?

    suspend fun sendFriendRequest(
        profileId: String,
        viaCode: Boolean,
    ): FriendRequestResult

    suspend fun respondFriendRequest(
        requesterId: String,
        accept: Boolean,
    )

    /** Entfreunden oder eigene offene Anfrage zurückziehen. */
    suspend fun removeFriend(profileId: String)

    suspend fun blockUser(profileId: String)

    suspend fun unblockUser(profileId: String)

    suspend fun blockedProfiles(): List<BlockedProfile>
}

/** Gezogene Freundschaften eines Kontos; unlesbare Snapshots werden ausgelassen statt die Liste zu sperren. */
fun CloudPullStore.pulledFriendships(profileId: String): List<PulledFriendship> =
    friendships(profileId).mapNotNull {
        runCatching { CloudJson.decodeFromString(PulledFriendship.serializer(), it.json) }
            .getOrNull()
            ?.takeIf { friendship -> friendship.profileId == it.id }
    }
