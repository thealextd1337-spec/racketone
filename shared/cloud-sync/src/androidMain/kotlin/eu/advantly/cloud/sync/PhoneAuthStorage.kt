@file:Suppress("DEPRECATION") // EncryptedSharedPreferences ist hier ausdrücklich für Keystore-gebundene Sitzungsspeicherung gewählt.

package eu.advantly.cloud.sync

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.exception.NoSessionFoundException
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.security.SecureRandom

/** Kein Fallback auf Klartext; Keystore-/Dateifehler brechen die Cloud-Initialisierung ab, lokales Zählen bleibt möglich. */
class PhoneAuthStorage(
    context: Context,
) : SessionManager,
    SupportDraftStorage {
    private val prefs =
        EncryptedSharedPreferences.create(
            context.applicationContext,
            "advantly_auth",
            MasterKey.Builder(context.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    private val json = Json { ignoreUnknownKeys = true }

    override fun loadDraft(ownerId: String?): String? = prefs.getString("support_${ownerId ?: "guest"}", null)

    override fun loadReceipt(ownerId: String?): String? = prefs.getString("support_receipt_${ownerId ?: "guest"}", null)

    override fun saveDraft(
        ownerId: String?,
        value: String?,
    ) {
        val key = "support_${ownerId ?: "guest"}"
        check(prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.commit())
    }

    override fun saveReceipt(
        ownerId: String?,
        value: String,
    ) {
        check(prefs.edit().putString("support_receipt_${ownerId ?: "guest"}", value).commit())
    }

    val deviceSecret: String
        get() =
            prefs.getString("device_secret", null) ?: ByteArray(32)
                .also { SecureRandom().nextBytes(it) }
                .joinToString("") { "%02x".format(it) }
                .also { check(prefs.edit().putString("device_secret", it).commit()) }

    override suspend fun saveSession(session: UserSession) =
        withContext(Dispatchers.IO) {
            check(prefs.edit().putString("session", json.encodeToString(session)).commit())
        }

    override suspend fun loadSession(): UserSession =
        withContext(Dispatchers.IO) {
            val value = prefs.getString("session", null) ?: throw NoSessionFoundException()
            json.decodeFromString(value)
        }

    override suspend fun deleteSession() =
        withContext(Dispatchers.IO) {
            // Die 24-h-Sperre darf durch Abmelden nicht ihre Installationsbindung verlieren.
            check(prefs.edit().remove("session").commit())
        }
}
