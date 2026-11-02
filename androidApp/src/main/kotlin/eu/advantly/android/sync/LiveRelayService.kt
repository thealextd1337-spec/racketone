package eu.advantly.android.sync

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import eu.advantly.android.MainActivity
import eu.advantly.android.R
import eu.advantly.android.auth.runScheduledSync
import eu.advantly.core.model.MatchId
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.match.session.ActiveMatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Welche Matches die Live-Übertragung brauchen (plan-live-mitspieler §3.1, Doze): laufend (nicht pausiert/beendet), die
 * eigene Uhr zählt, und beim Eintritt gibt es Publikum ([audience]: verknüpfte Mitspieler oder vorgemerkte Zuschauer).
 * Ein aufgenommenes Match bleibt, bis es endet, pausiert, abgegeben wird oder nicht mehr die Uhr zählt – vorgemerkte
 * Zuschauer verschwinden nach dem Senden aus dem lokalen Speicher.
 */
internal class LiveRelayGate(
    private val audience: (ActiveMatch) -> Boolean,
) {
    private val relayed = HashSet<MatchId>()

    /** `true`: Der Vordergrunddienst soll laufen. */
    fun update(
        signedIn: Boolean,
        matches: List<ActiveMatch>,
        watchScores: (ActiveMatch) -> Boolean,
    ): Boolean {
        val live =
            if (signedIn) matches.filter { it.state.status in RUNNING && watchScores(it) } else emptyList()
        relayed.retainAll(live.mapTo(HashSet()) { it.id })
        live.forEach { if (it.id !in relayed && audience(it)) relayed += it.id }
        return relayed.isNotEmpty()
    }

    private companion object {
        val RUNNING = setOf(MatchStatus.IN_PROGRESS, MatchStatus.AWAITING_DECISION)
    }
}

/**
 * Vordergrunddienst (Typ `connectedDevice`) „Live-Stand wird übertragen“, solange die eigene Uhr ein Match mit Publikum
 * zählt: Im Doze sind Netz und Jobs gesperrt, Expedited Jobs haben nur eine kleine Tagesquote. Ein Prozess mit
 * Vordergrunddienst hat Netz und Wakelocks auch im Doze; der Upload läuft deshalb direkt hier statt über JobScheduler.
 *
 * Android 12+ erlaubt den Start nur aus dem Vordergrund (z. B. Match am Handy gestartet oder App geöffnet). Scheitert
 * er, bleibt es beim Expedited Job; der nächste Zustandswechsel versucht es erneut. Aufrufe auf Main.
 */
class LiveRelayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private val wakeLock by lazy {
        getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "advantly:live-relay")
            .apply { setReferenceCounted(false) }
    }
    private var pending: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        // Erst in den Vordergrund (Pflicht nach startForegroundService), dann prüfen, ob er noch gebraucht wird.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification())
        }
        if (!wanted) stop()
        return START_NOT_STICKY
    }

    /** Ein Upload nach [delayMs]; das Gerät bleibt bis dahin wach (Handler zählt sonst im Tiefschlaf nicht weiter). */
    internal fun upload(
        profile: String,
        delayMs: Long,
    ) {
        pending?.let(main::removeCallbacks)
        wakeLock.acquire(delayMs + UPLOAD_TIMEOUT_MS)
        val run =
            Runnable {
                pending = null
                scope.launch {
                    try {
                        // Rückgabe (erneut planen) ignoriert: der nächste Punkt stößt ohnehin den nächsten Lauf an.
                        runScheduledSync(applicationContext, profile)
                    } finally {
                        if (pending == null) wakeLock.release()
                    }
                }
            }
        pending = run
        main.postDelayed(run, delayMs)
    }

    private fun stop() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        pending?.let(main::removeCallbacks)
        scope.cancel()
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.live_relay_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.live_relay_channel_description)
            },
        )
        val open = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return Notification
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.live_relay_title))
            .setContentText(getString(R.string.live_relay_text))
            .setOngoing(true)
            .setLocalOnly(true) // nicht auf die Uhr spiegeln: dort läuft das Match
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE))
            .build()
    }

    internal companion object {
        const val NOTIFICATION_ID = 41_101
        const val CHANNEL_ID = "live_relay"
        const val UPLOAD_TIMEOUT_MS = 60_000L

        internal var instance: LiveRelayService? = null
            private set
        private var wanted = false

        /** Dienst starten bzw. beenden ([run]); ein im Hintergrund verbotener Start fällt still auf Jobs zurück. */
        fun update(
            context: Context,
            run: Boolean,
        ) {
            wanted = run
            val running = instance
            if (!run) {
                running?.stop()
                return
            }
            if (running != null) return
            try {
                context.startForegroundService(Intent(context, LiveRelayService::class.java))
            } catch (e: IllegalStateException) {
                // Android 12+: ForegroundServiceStartNotAllowedException (ist eine IllegalStateException), ältere
                // Versionen: Hintergrundstart von Diensten verboten
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e !is ForegroundServiceStartNotAllowedException) throw e
            }
        }

        /** Upload über den laufenden Dienst; `false`: kein Dienst, der Aufrufer plant einen Job. */
        fun upload(
            profile: String,
            delayMs: Long,
        ): Boolean {
            val service = instance ?: return false
            service.upload(profile, delayMs)
            return true
        }
    }
}
