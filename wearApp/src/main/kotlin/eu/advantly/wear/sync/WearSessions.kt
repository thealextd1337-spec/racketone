package eu.advantly.wear.sync

import android.content.Context
import android.os.Looper
import eu.advantly.data.LocalData
import eu.advantly.data.createAndroidLocalData
import eu.advantly.device.link.MainLooperScheduler
import eu.advantly.device.link.ReliableDeviceLink
import eu.advantly.device.link.RetryPolicy
import eu.advantly.device.link.WearDataLayerTransport
import eu.advantly.match.session.MatchSession
import eu.advantly.match.session.MatchSessions
import eu.advantly.match.session.SessionListener
import eu.advantly.sync.protocol.PeerKind
import eu.advantly.wear.diagnostics.DiagnosticLog

/**
 * Prozessweite Match-Session der Uhr (Art `WATCH`), gestartet in `Application.onCreate` und damit auch dann aktiv,
 * wenn der `SyncListenerService` den Prozess im Hintergrund weckt.
 *
 * Entspricht `AndroidMatchSessions.get(context, PeerKind.WATCH)` (gleiche Datenbank, gleicher Wear-Data-Layer-Link,
 * Main-Looper-Scheduler, Thread-Prüfung), hält aber zusätzlich
 * - die [LocalData] (eine Instanz je Prozess) für Spieler, „Wie letztes Mal“ und Verlauf der Uhr und
 * - den [InjectableTransport], über den der Hintergrund-Empfang Frames direkt an den Link gibt.
 * Beides gibt die gemeinsame Fabrik nicht heraus; ihre öffentlichen Bausteine (`MatchSessions.create`,
 * `WearDataLayerTransport`, `ReliableDeviceLink`) reichen, `shared/` bleibt unverändert.
 *
 * Nach einem Prozess-Neustart wird das zuletzt offene Match wieder geöffnet (ohne Neustart der Zähl-Rolle).
 */
object WearSessions {
    /** Bausteine der laufenden Session. */
    class Holder(
        val data: LocalData,
        val session: MatchSession,
        val transport: InjectableTransport,
        val prefs: WearPrefs,
    )

    private var holder: Holder? = null

    /** Ob die Session in diesem Prozess schon läuft. Nur Main-Thread. */
    val isStarted: Boolean get() = holder != null

    /** Liefert die gestartete Session; legt sie beim ersten Aufruf an. Nur Main-Thread. */
    fun get(context: Context): Holder {
        check(Looper.myLooper() == Looper.getMainLooper()) { "WearSessions nur auf dem Main-Thread" }
        return holder ?: create(context.applicationContext).also { holder = it }
    }

    private fun create(app: Context): Holder {
        val scheduler = MainLooperScheduler()
        val data = createAndroidLocalData(app)
        val transport = InjectableTransport(WearDataLayerTransport(app))
        val link = ReliableDeviceLink(transport, scheduler, RetryPolicy())
        val session =
            MatchSessions.create(
                data,
                link,
                scheduler,
                PeerKind.WATCH,
                threadGuard = { Looper.myLooper() == Looper.getMainLooper() },
            )
        session.start()
        val prefs = WearPrefs(app)
        prefs.openMatchId?.let { id ->
            val resumed = session.resume(id)
            DiagnosticLog.log("MATCH_RESUME_AFTER_RESTART", "match" to id.value, "ok" to resumed)
        }
        // Offenes Match merken (Neustart) – addListener liefert sofort den aktuellen Stand
        session.addListener(SessionListener { prefs.openMatchId = it.match?.id })
        DiagnosticLog.log("SESSION_STARTED", "device" to session.deviceId.value)
        return Holder(data, session, transport, prefs)
    }
}
