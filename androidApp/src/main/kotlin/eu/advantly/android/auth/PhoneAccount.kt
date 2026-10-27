package eu.advantly.android.auth

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import eu.advantly.android.AdvantlyPhoneApplication
import eu.advantly.android.BuildConfig
import eu.advantly.android.sync.AndroidPhoneSyncRecord
import eu.advantly.android.sync.AndroidPhoneSyncScheduler
import eu.advantly.cloud.sync.CloudAuthPort
import eu.advantly.cloud.sync.CloudException
import eu.advantly.cloud.sync.PhoneAuthStorage
import eu.advantly.cloud.sync.PulledFriendship
import eu.advantly.cloud.sync.SupabasePhoneAuth
import eu.advantly.cloud.sync.SupabaseSupport
import eu.advantly.cloud.sync.pulledFriendships
import eu.advantly.data.AndroidInstallationGuard
import eu.advantly.match.session.AndroidMatchSessions
import eu.advantly.presentation.auth.AuthViewModel
import eu.advantly.presentation.auth.GoogleCredential
import eu.advantly.presentation.auth.PhonePullPort
import eu.advantly.presentation.auth.PhoneUploadPort
import eu.advantly.presentation.friends.FriendsViewModel
import eu.advantly.presentation.support.SupportViewModel
import eu.advantly.sync.protocol.PeerKind
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.coroutines.resume

/** Konfiguration fehlt im Standardbuild: P1 funktioniert ohne Konto und ohne Netzwerk unverändert. */
fun ComponentActivity.createPhoneAccount(): AuthViewModel? = createPhoneAccount(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY)

/** Explizite Konfiguration für Kompositionstests; die öffentliche Factory liefert die BuildConfig-Werte. */
internal fun ComponentActivity.createPhoneAccount(
    apiUrl: String,
    apiKey: String,
): AuthViewModel? {
    val core = PhoneAccountCore.get(applicationContext, apiUrl, apiKey) ?: return null
    lateinit var model: AuthViewModel
    val refresh: () -> Unit = { model.refresh() }
    val account =
        PhoneAccountPort(core.auth, core.runtime.scope, core.record) {
            if (core.refreshAccount === refresh) core.refreshAccount = null
        }
    val pull =
        core.pull?.let { pull ->
            object : PhonePullPort by pull {
                override fun close(onDrained: () -> Unit) = onDrained()
            }
        }
    model =
        AuthViewModel(
            account,
            workScope = account.scope,
            upload =
                object : PhoneUploadPort by core.upload {
                    override fun close(onDrained: () -> Unit) = onDrained()
                },
            pull = pull,
            friends = FriendsViewModel(account, core.backend, core::localFriendships, pull, account.scope),
            sync = core.sync,
            export = PhoneExportHost(core.auth.dataExports(), applicationContext, core.runtime.scope),
            support =
                SupportViewModel(
                    SupabaseSupport(apiUrl, apiKey, core.client),
                    core.storage,
                ),
        )
    core.refreshAccount = refresh
    return model
}

/**
 * Konto-Port eines ViewModels über dem prozessweiten Kern. Seine Arbeit (Sitzungs-Collector, Aktionen) läuft in einem
 * eigenen Kind-Job des Prozess-Scopes: [close] bricht nur diesen ab und gibt den Profil-Hook ab ([release]); der Kern
 * bleibt für geplante Arbeit bestehen. Logout und Löschantrag entfernen den Sicherungsstand des Kontos.
 */
internal class PhoneAccountPort(
    private val auth: CloudAuthPort,
    processScope: CoroutineScope,
    private val record: PhoneSyncRecord,
    private val release: () -> Unit,
) : CloudAuthPort by auth {
    private val work = SupervisorJob(processScope.coroutineContext[Job])
    val scope = CoroutineScope(processScope.coroutineContext + work)

    override suspend fun signOut() {
        val profile = auth.accountSession.value?.profileId
        // Die lokale Sitzung endet auch bei Netzfehler oder Abbruch (SupabasePhoneAuth.signOut).
        try {
            auth.signOut()
        } finally {
            profile?.let(record::forget)
        }
    }

    override suspend fun requestDeletion() {
        val profile = auth.accountSession.value?.profileId
        auth.requestDeletion()
        profile?.let(record::forget)
    }

    override fun close() {
        work.cancel()
        release()
    }
}

/**
 * B4: prozessweit genau ein Konto-Host je Android-Session. Konto-ViewModels (auch Multi-Window) und geplante Arbeit teilen
 * SDK-Client, Auth, Hosts und Koordinator: Zwei Clients rotierten Refresh-Tokens gegeneinander, und der Abbau des einen
 * deaktivierte die Upload-Sitzung des anderen. Abgebaut wird erst, wenn die Android-Session widerrufen ist.
 */
internal class PhoneAccountCore private constructor(
    context: Context,
    apiUrl: String,
    apiKey: String,
) {
    val storage = PhoneAuthStorage(context)
    val client =
        createSupabaseClient(apiUrl, apiKey) {
            // SDK-Debuglogs enthalten Session-Objekte: auch im Debug-Build ausdrücklich abschalten.
            defaultLogLevel = LogLevel.NONE
            install(Auth) { sessionManager = storage }
            install(Postgrest)
        }
    private val session = AndroidMatchSessions.get(context, PeerKind.PHONE)
    val runtime = AndroidAccountRuntime(context, session)
    val record = AndroidPhoneSyncRecord(context)
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
    private val lane = PhoneCloudLane()
    val upload =
        PhoneUploadHost(auth, session, backend, backend::bindDevice, runtime.scope, hostAccessAllowed = {
            runtime.allowed
        }, storageAction = runtime::storageAction, lane = lane)
    val pull =
        phonePullHost {
            PhonePullHost(auth, session, backend, backend::bindDevice, lane, runtime.scope, hostAccessAllowed = {
                runtime.allowed
            }, storageAction = runtime::storageAction)
        }
    val sync =
        PhoneSyncCoordinator(
            auth,
            session,
            pull,
            upload,
            AndroidPhoneSyncScheduler(context),
            record,
            runtime.scope,
            hostAccessAllowed = { runtime.allowed },
            storageAction = runtime::storageAction,
        )

    /** Gezogene Freundschaften nur innerhalb der Session-Grenze; nach Widerruf eine leere Liste statt SQL-Zugriff. */
    fun localFriendships(profile: String): List<PulledFriendship> {
        var result = emptyList<PulledFriendship>()
        return if (runtime.allowed &&
            runtime.storageAction { result = session.data.cloudPulls.pulledFriendships(profile) }
        ) {
            result
        } else {
            emptyList()
        }
    }

    /** Profilprüfung über das zuletzt erzeugte Konto-ViewModel; ohne ViewModel prüft geplante Arbeit selbst. */
    var refreshAccount: (() -> Unit)? = null

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val network =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Offline gestartet: erst das Konto prüfen, danach startet der Koordinator selbst.
                if (auth.session.value == null) refreshAccount?.invoke() else sync.onNetworkAvailable()
            }
        }

    init {
        connectivity.registerDefaultNetworkCallback(network, Handler(Looper.getMainLooper()))
    }

    /** Nur für einen widerrufenen Kern: Hosts erst leeren, dann Auth und Laufzeit schließen. */
    private fun close() {
        connectivity.unregisterNetworkCallback(network)
        refreshAccount = null
        sync.close()
        var pending = if (pull == null) 1 else 2
        val drained = {
            if (--pending == 0) {
                try {
                    auth.close()
                } finally {
                    runtime.close()
                }
            }
        }
        upload.close(drained)
        pull?.close(drained)
    }

    companion object {
        private var shared: PhoneAccountCore? = null

        /** Konfiguration fehlt oder Restore-Gate zu: kein Konto-Host, P1 bleibt unverändert. Nur auf Main. */
        fun get(
            context: Context,
            apiUrl: String,
            apiKey: String,
        ): PhoneAccountCore? {
            check(Looper.myLooper() == Looper.getMainLooper()) { "Konto-Host nur auf Main" }
            if (apiUrl.isBlank() || apiKey.isBlank()) return null
            // Login ist nie ein Wiederherstellungsbeleg: Im Hold gibt es kein Konto, erst die ausdrückliche Entscheidung beendet ihn.
            if (!AndroidInstallationGuard.privateTransportAllowed(context)) return null
            shared?.let { current ->
                if (current.runtime.allowed) return current
                shared = null
                current.close()
            }
            return PhoneAccountCore(context.applicationContext, apiUrl, apiKey).also { shared = it }
        }
    }
}

/**
 * Geplanter Abgleich (JobScheduler) über denselben Konto-Host wie die UI, also mit denselben Gates.
 * `true`: Die Kontoprüfung scheiterte vorübergehend; das System wiederholt mit seinem Backoff.
 */
internal suspend fun runScheduledSync(
    context: Context,
    profile: String?,
): Boolean {
    val app = context.applicationContext
    // Kaltstart per JobScheduler: die Installationsprüfung nicht auf Main ausführen, nur auf ihr Ergebnis warten.
    suspendCancellableCoroutine<Unit> { done -> AndroidInstallationGuard.checkAsync(app) { done.resume(Unit) } }
    if ((app as? AdvantlyPhoneApplication)?.bootstrap() == false) return false
    val core =
        try {
            PhoneAccountCore.get(app, BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY)
        } catch (_: Exception) {
            // Keystore-/Speicherfehler: wie in der Activity kein Konto, lokales Zählen bleibt unberührt.
            null
        } ?: return false
    if (core.auth.session.value == null) {
        // Ein sichtbares Konto-ViewModel prüft selbst (sonst zeigte es einen veralteten Fehler); danach startet der Koordinator.
        core.refreshAccount?.let {
            it()
            return false
        }
        try {
            core.auth.refreshProfile()
        } catch (error: CancellationException) {
            throw error
        } catch (error: CloudException) {
            return !error.permission
        } catch (_: Exception) {
            return true
        }
    }
    core.sync.request(profile)?.join()
    return false
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
