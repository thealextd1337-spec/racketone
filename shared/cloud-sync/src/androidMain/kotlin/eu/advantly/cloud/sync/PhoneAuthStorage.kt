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
) : SessionManager {
    private val prefs =
        EncryptedSharedPreferences.create(
            context.applicationContext,
            "advantly_auth",
            MasterKey.Builder(context.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    private val json = Json { ignoreUnknownKeys = true }

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
