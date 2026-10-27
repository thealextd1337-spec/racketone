package eu.advantly.android

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.CreationExtras
import eu.advantly.android.auth.PhoneExportHost
import eu.advantly.android.auth.createOfflineSupport
import eu.advantly.android.auth.createPhoneAccount
import eu.advantly.android.auth.googleCredential
import eu.advantly.data.AndroidInstallationAvailability
import eu.advantly.data.AndroidInstallationGuard
import eu.advantly.device.link.WearCompanionDetector
import eu.advantly.match.session.AndroidMatchSessions
import eu.advantly.match.session.AndroidSessionAccess
import eu.advantly.match.session.AndroidSessionBoundary
import eu.advantly.presentation.auth.AuthViewModel
import eu.advantly.presentation.support.SupportViewModel
import eu.advantly.sync.protocol.PeerKind
import eu.advantly.ui.phone.AdvantlyApp

class MainActivity : ComponentActivity() {
    private var sessionAccess: AndroidSessionAccess? = null
    private var account: AuthViewModel? = null
    private val storageHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun runtimeUnavailable() {
        if (isFinishing || isDestroyed) return
        sessionAccess?.close()
        sessionAccess = null
        account = null
        // Entfernen der Composition beendet ihre Subscriptions; Kontojobs nicht über Retry behalten.
        viewModelStore.clear()
        showInstallationError()
    }

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        if (sessionAccess?.ready == false) {
            runtimeUnavailable()
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    /** Erst gesetzt, wenn [start] die Entscheidungen dieser Instanz getroffen hat (nicht schon im Ladezustand). */
    private var started = false
    private var createdFrom: Bundle? = null

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Im Ladezustand den Merker des Ursprungs unverändert weitergeben: Eine echte Wiederherstellung bleibt eine.
        val original = createdFrom
        when {
            started -> outState.putBoolean(KEY_STARTED, true)
            original == null -> outState.putBoolean(KEY_STARTED, false)
            original.containsKey(KEY_STARTED) -> outState.putBoolean(KEY_STARTED, original.getBoolean(KEY_STARTED))
        }
    }

    override fun onDestroy() {
        sessionAccess?.close()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Systemleisten folgen dem System-Theme (die App ist Dark-first, der Live-Score immer schwarz)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        createdFrom = savedInstanceState
        // Kopie und quick_check laufen im Hintergrund; bis dahin nur die Ladeansicht (bekanntes Ergebnis: sofort).
        showInstallationLoading()
        AndroidInstallationGuard.checkAsync(applicationContext) { availability ->
            if (!isFinishing && !isDestroyed) start(savedInstanceState, availability)
        }
    }

    private fun start(
        savedInstanceState: Bundle?,
        availability: AndroidInstallationAvailability,
    ) {
        started = true
        // Rotation im Ladezustand zählt nicht als Wiederherstellung: Hold-Dialog und Hinweis sonst nie gezeigt.
        val restored = savedInstanceState?.getBoolean(KEY_STARTED, true) == true
        if (availability !is AndroidInstallationAvailability.Ready) {
            viewModelStore.clear()
            showInstallationError()
            return
        }
        val afterInstallationRetry = intent.getBooleanExtra(INSTALLATION_RETRY_COMPLETE, false)
        intent.removeExtra(INSTALLATION_RETRY_COMPLETE)
        if ((application as? AdvantlyPhoneApplication)?.bootstrap() == false) {
            viewModelStore.clear()
            showInstallationError()
            return
        }
        val installation = availability.installation
        val current =
            AndroidInstallationGuard.storage(this) { AndroidMatchSessions.get(this, PeerKind.PHONE) }
                ?: run {
                    showInstallationError()
                    return
                }
        sessionAccess =
            AndroidSessionAccess(this, current) {
                storageHandler.post { runtimeUnavailable() }
            }
        // ViewModelStore hält Auth über Rotation; keine Credentials in Bundle oder SavedState.
        val auth =
            if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_KEY.isBlank()) {
                null
            } else {
                runCatching {
                    ViewModelProvider(
                        this,
                        object : ViewModelProvider.Factory {
                            @Suppress("UNCHECKED_CAST")
                            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = createPhoneAccount() as T
                        },
                    )[AuthViewModel::class.java]
                }.getOrNull()
            }
        account = auth
        val support =
            auth?.support ?: runCatching {
                ViewModelProvider(
                    this,
                    object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = createOfflineSupport() as T
                    },
                )[SupportViewModel::class.java]
            }.getOrNull()
        val boundary = AndroidSessionBoundary(applicationContext, allowed = { sessionAccess?.ready == true })
        val model =
            boundary.run {
                ViewModelProvider(
                    this,
                    object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : androidx.lifecycle.ViewModel> create(
                            modelClass: Class<T>,
                            extras: CreationExtras,
                        ): T {
                            val store =
                                AndroidPhoneMatchStore.create(
                                    applicationContext,
                                    current,
                                    companion =
                                        if (installation.privateTransportAllowed) {
                                            WearCompanionDetector(
                                                applicationContext,
                                            )
                                        } else {
                                            null
                                        },
                                )
                            try {
                                return AndroidPhoneAppViewModel(store, extras.createSavedStateHandle()) as T
                            } catch (
                                error: Exception,
                            ) {
                                try {
                                    store.close()
                                } catch (cleanup: Exception) {
                                    if (cleanup !==
                                        error
                                    ) {
                                        error.addSuppressed(cleanup)
                                    }
                                }
                                throw error
                            }
                        }
                    },
                )[AndroidPhoneAppViewModel::class.java].model
            }
        if (model == null) {
            runtimeUnavailable()
            return
        }
        // Ohne privaten Transport gibt es keine Uhr-Erkennung (companion = null), also auch keinen Installationshinweis
        val watchInstall =
            if (installation.privateTransportAllowed) WatchAppInstaller(this, model.live.deviceStatuses, lifecycleScope) else null
        // Recovery hat Vorrang: im Hold und solange der Abschlussdialog offen ist, wartet der Startbildschirm.
        val recoveryNotice = mutableStateOf(false)
        val startLogin = auth?.let { StartLoginPrefs(this) }
        setContent {
            // Debug-Builds zeigen im Live-Menü zusätzlich Latenz-Overlay, Entprellung und Auto-Benchmark (Spike S4)
            AdvantlyApp(
                model = model,
                versionName = BuildConfig.VERSION_NAME,
                buildCommit = getString(R.string.build_git_commit),
                buildTimeUtc = getString(R.string.build_time_utc),
                debugTools = BuildConfig.DEBUG,
                authModel = auth,
                onShareExport = this@MainActivity.phoneExportShareCallback(auth),
                onShareMatchImage = { image, text -> this@MainActivity.shareMatchImage(image, text) },
                onShareText = this@MainActivity::shareText,
                watchInstall = watchInstall,
                startLogin = startLogin,
                recoveryHold = !installation.privateTransportAllowed || recoveryNotice.value,
                supportModel = support,
                supportDiagnostics = mapOf("os" to "Android ${android.os.Build.VERSION.RELEASE}", "device" to android.os.Build.MODEL),
                googleCredential =
                    if (BuildConfig.GOOGLE_WEB_CLIENT_ID.isBlank()) {
                        null
                    } else {
                        { googleCredential() }
                    },
                onRestorePrevious =
                    if (installation.privateTransportAllowed && installation.restorable != null) {
                        { showLaterRestore(current) }
                    } else {
                        null
                    },
            )
        }
        if (!installation.privateTransportAllowed && (!restored || afterInstallationRetry)) {
            showRecoveryDecision(installation, current)
        }
        if (!restored) recoveryNotice.value = showRecoveryDoneOnce(installation) { recoveryNotice.value = false }
        // App-Start bzw. Vordergrund (B4): höchstens ein gedrosselter automatischer Abgleich. Als Beobachter erst ab
        // hier, damit ein Prüfergebnis nach onStart den Abgleich nachholt (der Lifecycle liefert ON_START nach).
        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = foreground(this@MainActivity)
            },
        )
    }

    internal companion object {
        private const val KEY_STARTED = "installation_started"

        /** Testnaht wie [eu.advantly.android.sync.PhoneSyncJobService.work]; im Produkt immer der Konto-Abgleich. */
        @VisibleForTesting
        internal var foreground: (MainActivity) -> Unit = { activity -> activity.account?.sync?.onForeground() }
    }
}

/** Reiner Text (Freundescode) über das System-Teilen-Menü; ohne passende App passiert nichts. */
internal fun ComponentActivity.shareText(text: String) {
    val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    try {
        startActivity(Intent.createChooser(intent, null))
    } catch (_: ActivityNotFoundException) {
        // Kein Teilen-Ziel: der Code bleibt auf dem Bildschirm auswählbar.
    }
}

/** Gehört der aktuellen Activity/Composition; niemals im ViewModel oder mit remember halten. */
internal fun ComponentActivity.phoneExportShareCallback(auth: AuthViewModel?): (() -> Unit)? =
    (auth?.export as? PhoneExportHost)?.let { host -> { host.share(this) } }
