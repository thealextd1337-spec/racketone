package eu.advantly.android.auth

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import eu.advantly.android.BuildConfig
import eu.advantly.cloud.sync.CloudAuthPort
import eu.advantly.cloud.sync.CloudException
import eu.advantly.cloud.sync.PhoneAuthStorage
import eu.advantly.cloud.sync.SupabasePhoneAuth
import eu.advantly.cloud.sync.SupabaseSupport
import eu.advantly.data.AndroidInstallationGuard
import eu.advantly.match.session.AndroidMatchSessions
import eu.advantly.presentation.auth.AuthViewModel
import eu.advantly.presentation.auth.GoogleCredential
import eu.advantly.presentation.support.SupportViewModel
import eu.advantly.sync.protocol.PeerKind
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import java.security.MessageDigest
import java.security.SecureRandom

/** Konfiguration fehlt im Standardbuild: P1 funktioniert ohne Konto und ohne Netzwerk unverändert. */
fun ComponentActivity.createPhoneAccount(): AuthViewModel? = createPhoneAccount(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY)

/** Explizite Konfiguration für Kompositionstests; die öffentliche Factory liefert die BuildConfig-Werte. */
internal fun ComponentActivity.createPhoneAccount(
    apiUrl: String,
    apiKey: String,
): AuthViewModel? {
    if (apiUrl.isBlank() || apiKey.isBlank()) return null
    // Login allein ist kein Wiederherstellungs-/Zugriffsbeleg. Integration folgt nach D2/D3.
    if (!AndroidInstallationGuard.privateTransportAllowed(applicationContext)) return null
    val storage = PhoneAuthStorage(applicationContext)
    val client =
        createSupabaseClient(apiUrl, apiKey) {
            // SDK-Debuglogs enthalten Session-Objekte: auch im Debug-Build ausdrücklich abschalten.
            defaultLogLevel = LogLevel.NONE
            install(Auth) { sessionManager = storage }
            install(Postgrest)
        }
    val session = AndroidMatchSessions.get(applicationContext, PeerKind.PHONE)
    val runtime = AndroidAccountRuntime(applicationContext, session)
    val auth =
        SupabasePhoneAuth(
            client,
            storage.deviceSecret,
            uploads = session.data.cloudUploads,
            ownership = session,
            scope = runtime.scope,
            threadGuard = { Looper.myLooper() == Looper.getMainLooper() },
            hostAccessAllowed = { runtime.allowed },
            hostStorageAction = runtime::storageAction,
        )
    val backend = auth.cloudBackend(storage.cloudInstallation)
    val lane = PhoneCloudLane()
    return AuthViewModel(
        object : CloudAuthPort by auth {
            override fun close() {
                try {
                    auth.close()
                } finally {
                    runtime.close()
                }
            }
        },
        workScope = runtime.scope,
        upload =
            PhoneUploadHost(auth, session, backend, backend::bindDevice, runtime.scope, hostAccessAllowed = {
                runtime.allowed
            }, storageAction = runtime::storageAction, lane = lane),
        pull =
            PhonePullHost(auth, session, backend, backend::bindDevice, lane, runtime.scope, hostAccessAllowed = {
                runtime.allowed
            }, storageAction = runtime::storageAction),
        export = PhoneExportHost(auth.dataExports(), applicationContext, runtime.scope),
        support =
            SupportViewModel(
                SupabaseSupport(apiUrl, apiKey, client),
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
