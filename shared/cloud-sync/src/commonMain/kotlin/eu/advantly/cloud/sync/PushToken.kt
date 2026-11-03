package eu.advantly.cloud.sync

// Push (docs/plan-live-mitspieler.md §3.3/§8): `cloud_set_push_token_v1(provider, token)` wirkt nur am gebundenen
// Gerät des Aufrufers; `null` leert das Token.

/** Einziger Anbieter bis zu einem iOS-Konto (APNs, E9). */
const val PUSH_PROVIDER_FCM = "fcm"

/**
 * Fehler als [CloudException]; trägt die Sitzung ein anderes Konto als [profileId], geht nichts ins Netz
 * (`cloud_session_changed`).
 */
interface PushTokenPort {
    suspend fun setPushToken(
        profileId: String,
        provider: String,
        token: String?,
    )
}
