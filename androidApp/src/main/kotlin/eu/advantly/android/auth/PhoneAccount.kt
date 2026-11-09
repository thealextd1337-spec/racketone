package eu.advantly.android.auth

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.lifecycle.SavedStateHandle
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import eu.advantly.android.AdvantlyPhoneApplication
import eu.advantly.android.BuildConfig
import eu.advantly.android.R
import eu.advantly.android.push.AndroidNotificationSettings
import eu.advantly.android.push.AndroidPush
import eu.advantly.android.push.AndroidPushPreferences
import eu.advantly.android.push.FirebaseTokens
import eu.advantly.android.sync.AccountLiveViews
import eu.advantly.android.sync.AndroidPhoneSyncRecord
import eu.advantly.android.sync.AndroidPhoneSyncScheduler
import eu.advantly.cloud.sync.CloudAuthPort
import eu.advantly.cloud.sync.CloudException
import eu.advantly.cloud.sync.PhoneAuthStorage
import eu.advantly.cloud.sync.PulledFriendship
import eu.advantly.cloud.sync.SupabasePhoneAuth
import eu.advantly.cloud.sync.SupabaseSupport
import eu.advantly.cloud.sync.pulledFeed
import eu.advantly.cloud.sync.pulledFriendships
import eu.advantly.cloud.sync.pulledMatch
import eu.advantly.core.model.MatchId
import eu.advantly.data.AndroidInstallationGuard
import eu.advantly.data.LocalData
import eu.advantly.data.scoreEvents
import eu.advantly.device.link.LiveViewsProvider
import eu.advantly.match.session.AndroidMatchSessions
import eu.advantly.match.session.MatchSession
import eu.advantly.presentation.auth.AuthViewModel
import eu.advantly.presentation.auth.GoogleCredential
import eu.advantly.presentation.auth.PhonePullPort
import eu.advantly.presentation.auth.PhoneUploadPort
import eu.advantly.presentation.friends.FriendsViewModel
import eu.advantly.presentation.friends.InvitationsViewModel
import eu.advantly.presentation.friends.ScorerHandovers
import eu.advantly.presentation.friends.SharedMatchesLocal
import eu.advantly.presentation.friends.VisitorQueue
import eu.advantly.presentation.friends.ownGuestLinks
import eu.advantly.presentation.match.CloudPlayerName
import eu.advantly.presentation.push.PushRegistration
import eu.advantly.presentation.support.SupportViewModel
import eu.advantly.sync.protocol.LiveView
import eu.advantly.sync.protocol.PeerKind
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.coroutines.resume

/** Konfiguration fehlt im Standardbuild: P1 funktioniert ohne Konto und ohne Netzwerk unverändert. */
fun ComponentActivity.createPhoneAccount(savedState: SavedStateHandle = SavedStateHandle()): AuthViewModel? =
    createPhoneAccount(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY, savedState)

/** Explizite Konfiguration für Kompositionstests; die öffentliche Factory liefert die BuildConfig-Werte. */
internal fun ComponentActivity.createPhoneAccount(
    apiUrl: String,
    apiKey: String,
    savedState: SavedStateHandle = SavedStateHandle(),
): AuthViewModel? {
    val core = PhoneAccountCore.get(applicationContext, apiUrl, apiKey) ?: return null
    lateinit var model: AuthViewModel
    val refresh: () -> Unit = { model.refresh() }
    val account =
        PhoneAccountPort(core.auth, core.runtime.scope, core.record, core.push) {
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
            friends =
                FriendsViewModel(
                    account,
                    core.backend,
                    core::localFriendships,
                    pull,
                    account.scope,
                    core.backend,
                    core.sharedMatches,
                    core.visitors,
                    core.backend,
                    core.scorers,
                    core.backend,
                ),
            invitations = InvitationsViewModel(account, core.backend, savedState, account.scope, System::currentTimeMillis, pull),
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
    /** Push (Plan Live §3.3); Abmelden und Löschantrag leeren das Token, solange die Sitzung noch gilt. */
    private val push: PushRegistration? = null,
    private val release: () -> Unit,
) : CloudAuthPort by auth {
    private val work = SupervisorJob(processScope.coroutineContext[Job])
    val scope = CoroutineScope(processScope.coroutineContext + work)

    override suspend fun signOut() {
        val profile = auth.accountSession.value?.profileId
        // Ohne Netz nicht aufhalten: bleibt das Server-Token, meldet FCM es nach deleteToken als ungültig.
        push?.let { withTimeoutOrNull(PUSH_SIGN_OUT_MS) { it.disable(signedOut = true) } }
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
        push?.let { withTimeoutOrNull(PUSH_SIGN_OUT_MS) { it.disable(signedOut = true) } }
    }

    override fun close() {
        work.cancel()
        release()
    }

    private companion object {
        const val PUSH_SIGN_OUT_MS = 5_000L
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
            install(Auth) {
                sessionManager = storage
                // Sonst setzt das SDK im Hintergrund `Initializing` (keine Sitzung) und stoppt die Erneuerung – auch für
                // geplante Hintergrund-Synchronisation. Die Erneuerung läuft prozessweit weiter.
                enableLifecycleCallbacks = false
            }
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
            }, storageAction = runtime::storageAction, telemetry = backend)
        }
    private val syncScheduler = AndroidPhoneSyncScheduler(context)

    /** Jeder beendete Lauf; verarbeitet erst nach dem Aufbau ([init], [afterEachRun]). */
    private val runEnded = Channel<Unit>(Channel.CONFLATED)
    val sync =
        PhoneSyncCoordinator(
            auth,
            session,
            pull,
            upload,
            syncScheduler,
            record,
            runtime.scope,
            hostAccessAllowed = { runtime.allowed },
            storageAction = runtime::storageAction,
            onRunEnded = { runEnded.trySend(Unit) },
        )

    /**
     * Zuschauer (F16): Benennungen warten, bis der Match-Kopf hochgeladen ist (oder das Match als Mitglied gezogen
     * wurde), und gehen nach jedem Abgleich hinaus.
     */
    val visitors = VisitorQueue(record, backend, ::onServer) { sync.request(null) }

    private fun onServer(
        profile: String,
        match: String,
    ) = guarded(false) { cloudUploads.headerUploaded(match) || cloudPulls.pulledMatch(profile, match) != null }

    private val handoverPoll =
        HandoverPoll(SystemClock::elapsedRealtime, { delayMs ->
            runtime.scope.launch {
                delay(delayMs)
                sync.request(null)
            }
        })

    /**
     * Zählen weitergeben (plan-live-mitspieler §3.4): Einladungen warten wie Zuschauer auf den Upload des Match-Kopfs.
     * Nach jedem Abgleich gibt das zählende Handy bei angenommener Rolle selbst ab ([afterSync]); während einer offenen
     * Übergabe wird alle 5 s abgeglichen, bis der Wechsel gesehen ist ([HandoverPoll]) – beim Empfänger höchstens
     * [ScorerHandovers.ACCEPT_LIMIT_MS] ab der Annahme, oder bis ein Rückfall, Widerruf oder das Matchende es beendet. Push (`scorer_responded`,
     * `scorer_changed`) stößt nur einen Abgleich an – gezogene Events gibt die Session sofort an die Uhr weiter.
     */
    val scorers =
        ScorerHandovers(
            record,
            backend,
            ::onServer,
            { profile, match -> guarded(null) { cloudPulls.pulledMatch(profile, match) } },
            PhoneScorerDevices(
                session,
                { event -> guarded(null) { cloud.uploaded(event) } },
                { match -> guarded(emptyList()) { events.scoreEvents(MatchId(match)) } },
            ) { runtime.allowed },
            System::currentTimeMillis,
        ) {
            handoverPoll.restart()
            sync.request(null)
        }

    private suspend fun flushVisitors() {
        val profile = auth.session.value?.profileId ?: return
        try {
            visitors.flush(profile)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Nicht endgültige Fehler bleiben vorgemerkt; der nächste Abgleich versucht es erneut.
        }
    }

    /** Push nur mit Firebase-Werten im Build; der Upload wartet auf Zustimmung und eine nutzbare Sitzung. */
    val push: PushRegistration? =
        if (AndroidPush.configured) {
            PushRegistration(
                AndroidPushPreferences(context),
                FirebaseTokens,
                backend,
            ) { auth.session.value }
        } else {
            null
        }

    /** Nach jedem Abgleich: vorgemerkte Zähl-Einladungen senden, angenommene Rollen abgeben, ggf. weiter abfragen. */
    private suspend fun afterSync() {
        val profile = auth.session.value?.profileId ?: return
        try {
            scorers.flush(profile)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Nicht endgültige Fehler bleiben vorgemerkt; der nächste Abgleich versucht es erneut.
        }
        handoverPoll.afterSync(runtime.allowed && scorers.reconcile(profile))
    }

    /** Gezogene Freundschaften nur innerhalb der Session-Grenze; nach Widerruf eine leere Liste statt SQL-Zugriff. */
    fun localFriendships(profile: String): List<PulledFriendship> = guarded(emptyList()) { cloudPulls.pulledFriendships(profile) }

    /** Feed, eigene Freigaben und Upload-Hinweise (P2-E) ebenfalls nur innerhalb der Session-Grenze. */
    val sharedMatches =
        object : SharedMatchesLocal {
            override fun feed(profileId: String) = guarded(emptyList()) { cloudPulls.pulledFeed(profileId) }

            override fun match(
                profileId: String,
                matchId: String,
            ) = guarded(null) { cloudPulls.pulledMatch(profileId, matchId) }

            override fun linkNotes(
                profileId: String,
                matchId: String,
            ) = guarded(emptyMap()) { cloudUploads.linkNotes(profileId, matchId) }

            // Nur eigene Gäste, die der Server kennt; redigiert nach ausdrücklichem Status, nicht nach dem Namen.
            override fun guestLinks(profileId: String) = guarded(emptyMap()) { ownGuestLinks(profileId) }
        }

    private fun <T> guarded(
        fallback: T,
        read: LocalData.() -> T,
    ): T {
        var result = fallback
        return if (runtime.allowed && runtime.storageAction { result = session.data.read() }) result else fallback
    }

    /** Profilprüfung über das zuletzt erzeugte Konto-ViewModel; ohne ViewModel prüft geplante Arbeit selbst. */
    var refreshAccount: (() -> Unit)? = null

    /**
     * Uhr-Zuschauen (plan-live-mitspieler §3.2), auch wenn die Uhr den Prozess gestartet hat: ohne geprüfte Sitzung und
     * ohne Konto-ViewModel (das prüft selbst) erst die Kontoprüfung wie [runScheduledSync] – im Prozess-Scope, damit
     * sie eine Zeitüberschreitung des Abrufs übersteht und der nächste Abruf die Sitzung vorfindet.
     */
    val liveViews =
        AccountLiveViews(
            backend,
            { auth.session.value?.profileId },
            { name ->
                when (name) {
                    is CloudPlayerName.Named -> name.name
                    is CloudPlayerName.Slot -> context.getString(R.string.player_slot_n, name.number)
                    CloudPlayerName.Deleted -> context.getString(R.string.player_deleted)
                }
            },
        ) {
            if (refreshAccount == null) runtime.scope.async { auth.refreshProfile() }.await()
        }

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
        runtime.scope.afterEachRun(runEnded) {
            flushVisitors()
            // Nach dem Lauf ist das Gerät gebunden: ausstehendes Token hochladen bzw. leeren.
            push?.sync()
            afterSync()
        }
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
 * Arbeit nach jedem Abgleich ([work], suspendierend) für die Signale aus [ended] (`Channel.CONFLATED`): Endet ein Lauf,
 * während sie noch läuft, folgt danach genau eine weitere Runde – eine Flanke auf `running` im State-Collector ginge
 * dabei verloren, und mit ihr die 5-s-Abfrage einer offenen Übergabe.
 */
internal fun CoroutineScope.afterEachRun(
    ended: ReceiveChannel<Unit>,
    work: suspend () -> Unit,
) = launch { for (ignored in ended) work() }

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

/**
 * Live-Übertragung ([eu.advantly.android.sync.LiveRelay]): bestätigte Zuschauer von [matchId] vom Server holen, auch auf
 * einem anderen Gerät benannte. `false`: Match noch nicht auf dem Server (hochgeladen oder als Mitglied gezogen) – später
 * erneut. Die Abfrage selbst läuft erst nach dem aktuellen Rückruf: Der Konto-Host darf nicht mitten im Bootstrap oder in
 * einem Session-Listener entstehen. Fehler bleiben still (nächster Versuch im nächsten Vordergrund). Nur auf Main.
 */
internal fun refreshMatchVisitors(
    context: Context,
    session: MatchSession,
    profile: String,
    matchId: String,
): Boolean {
    val app = context.applicationContext
    val onServer =
        AndroidInstallationGuard.storage(app) {
            session.data.cloudUploads.headerUploaded(matchId) || session.data.cloudPulls.pulledMatch(profile, matchId) != null
        } == true
    if (!onServer) return false
    Handler(Looper.getMainLooper()).post {
        if ((app as? AdvantlyPhoneApplication)?.bootstrap() == false) return@post
        val core =
            try {
                PhoneAccountCore.get(app, BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY)
            } catch (_: Exception) {
                null
            } ?: return@post
        core.runtime.scope.launch {
            try {
                // Kaltstart: Die Kontoprüfung (Activity oder Abgleich) setzt die Sitzung gleich.
                val current = withTimeoutOrNull(VISITOR_LOOKUP_SESSION_MS) { core.auth.session.first { it != null } }
                if (current?.profileId == profile) core.visitors.refreshConfirmed(profile, matchId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Offline oder gestört: im nächsten Vordergrund erneut.
            }
        }
    }
    return true
}

private const val VISITOR_LOOKUP_SESSION_MS = 30_000L

/**
 * Uhr-Zuschauen über den prozessweiten Konto-Host (nach Prozesstod wie [runScheduledSync] angelegt). Nur auf Main und
 * nach dem Bootstrap; ohne Backendkonfiguration, im Restore-Hold oder bei Keystore-Fehlern nie etwas live.
 */
internal fun phoneLiveViews(context: Context): LiveViewsProvider =
    object : LiveViewsProvider {
        override suspend fun current(): List<LiveView> {
            val core =
                try {
                    PhoneAccountCore.get(context.applicationContext, BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY)
                } catch (_: Exception) {
                    null
                }
            return (core?.liveViews ?: LiveViewsProvider.Empty).current()
        }
    }

/** Profil-Schalter „Benachrichtigungen“; `null` ohne Backend- oder Firebase-Werte (dann ist Push einfach aus). */
internal fun ComponentActivity.phoneNotifications(requestPermission: () -> Unit): AndroidNotificationSettings? {
    val core = PhoneAccountCore.get(applicationContext, BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY) ?: return null
    return core.push?.let { AndroidNotificationSettings(this, it, core.runtime.scope, requestPermission) }
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
