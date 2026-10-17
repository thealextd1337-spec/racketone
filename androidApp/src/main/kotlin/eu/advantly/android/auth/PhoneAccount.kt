package eu.advantly.android.auth

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import eu.advantly.android.BuildConfig
import eu.advantly.cloud.sync.CloudException
import eu.advantly.cloud.sync.PhoneAuthStorage
import eu.advantly.cloud.sync.SupabasePhoneAuth
import eu.advantly.cloud.sync.SupabaseSupport
import eu.advantly.match.session.AndroidMatchSessions
import eu.advantly.presentation.auth.AuthViewModel
import eu.advantly.presentation.auth.GoogleCredential
import eu.advantly.presentation.support.SupportViewModel
import eu.advantly.sync.protocol.PeerKind
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.security.MessageDigest
import java.security.SecureRandom

/** Konfiguration fehlt im Standardbuild: P1 funktioniert ohne Konto und ohne Netzwerk unverändert. */
fun ComponentActivity.createPhoneAccount(): AuthViewModel? {
    if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_KEY.isBlank()) return null
    val storage = PhoneAuthStorage(applicationContext)
    val client =
        createSupabaseClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY) {
            // SDK-Debuglogs enthalten Session-Objekte: auch im Debug-Build ausdrücklich abschalten.
            defaultLogLevel = LogLevel.NONE
            install(Auth) { sessionManager = storage }
            install(Postgrest)
        }
    return AuthViewModel(
        SupabasePhoneAuth(
            client,
            storage.deviceSecret,
            uploads = AndroidMatchSessions.get(applicationContext, PeerKind.PHONE).data.cloudUploads,
            ownership = AndroidMatchSessions.get(applicationContext, PeerKind.PHONE),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            threadGuard = { Looper.myLooper() == Looper.getMainLooper() },
        ),
        support =
            SupportViewModel(
                SupabaseSupport(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY, client),
                storage,
            ),
    )
}

/** Ohne Backendkonfiguration bleiben lokale Entwürfe möglich; Versand zeigt ausdrücklich „nicht verfügbar“. */
fun ComponentActivity.createOfflineSupport(): SupportViewModel = SupportViewModel(null, PhoneAuthStorage(applicationContext))

suspend fun ComponentActivity.googleCredential(): GoogleCredential {
    val rawNonce = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    val hashedNonce = MessageDigest.getInstance("SHA-256").digest(rawNonce.toByteArray()).joinToString("") { "%02x".format(it) }
    val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).setNonce(hashedNonce).build()
    val result =
        try {
            CredentialManager.create(this).getCredential(this, GetCredentialRequest.Builder().addCredentialOption(option).build())
        } catch (_: GetCredentialCancellationException) {
            throw CloudException("auth_cancelled", false)
        }
    requireGoogleCredentialType(result.credential.type)
    val token = GoogleIdTokenCredential.createFrom(result.credential.data).idToken
    return GoogleCredential(token, rawNonce)
}

/** Unbekannte Credential-Typen dürfen nicht als Google-ID-Token geparst werden. */
internal fun requireGoogleCredentialType(type: String) {
    if (type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
        throw CloudException("auth_failed", false)
    }
}
