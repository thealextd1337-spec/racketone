package eu.advantly.wear.sync

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import eu.advantly.device.link.WearDataLayerTransport
import eu.advantly.wear.diagnostics.DiagnosticLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Hintergrund-Empfang über die Wear Data Layer (P1-Sync-Service, 05 §2.4): Play Services startet diesen Dienst (und
 * damit den App-Prozess), wenn eine Nachricht des Handys ankommt, die Queue sich ändert oder das Handy wieder
 * erreichbar wird – auch wenn die App nicht läuft.
 *
 * - **Nachricht** (`/advantly/link/v1`): Frame direkt an den laufenden Link ([InjectableTransport.inject]); so landen
 *   Matches vom Handy (Director bzw. „Uhr zählt“), Events und Löschmarkierungen sofort in der Datenbank, und der
 *   Absender bekommt sein Ack.
 * - **Queue-DataItem** (`/advantly/link/q/…`) bzw. **Capability**: Session sicherstellen; der Transport liest die Queue
 *   beim Start bzw. im Live-Listener, die Session gleicht bei Wiederverbindung sofort ab (Hello/Summary/Rückstand).
 *
 * Die Callbacks laufen auf einem Hintergrund-Thread; die Session ist Main-Thread-gebunden. Wurde der Prozess für den
 * Aufruf frisch gestartet (Kaltstart), bleibt der Dienst gebunden, bis der Abgleich abgeschlossen ist (Gegenstelle
 * kompatibel, Abgleich empfangen, nichts ausstehend) – höchstens [SETTLE_TIMEOUT_MS] –, damit Queue und
 * Antwort-Runden (Summary, fehlende Events, Löschmarkierungen) durchlaufen, bevor das System den Prozess einfriert.
 */
class SyncListenerService : WearableListenerService() {
    private val main = Handler(Looper.getMainLooper())

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WearDataLayerTransport.MESSAGE_PATH) return
        val peer = event.sourceNodeId
        val frame = event.data
        onMain { holder -> holder.transport.inject(peer, frame) }
    }

    override fun onDataChanged(events: DataEventBuffer) {
        events.release()
        onMain { }
    }

    override fun onCapabilityChanged(info: CapabilityInfo) {
        onMain { holder -> holder.session.syncNow() }
    }

    private fun onMain(action: (WearSessions.Holder) -> Unit) {
        val cold =
            runOnMain {
                val holder = WearSessions.get(this)
                action(holder)
                WearSessions.consumeColdStart()
            } ?: return
        if (!cold) {
            Thread.sleep(LINGER_MS)
            return
        }
        // Kaltstart: bis zum abgeschlossenen Abgleich gebunden bleiben (begrenzt)
        val deadline = SystemClock.elapsedRealtime() + SETTLE_TIMEOUT_MS
        var settled = false
        while (!settled && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(POLL_MS)
            settled = runOnMain { WearSessions.isSyncSettled() } == true
        }
        DiagnosticLog.log("SYNC_SERVICE_WAKE", "settled" to settled)
    }

    /** Führt [block] auf dem Main-Thread aus und wartet auf das Ergebnis (`null` bei Zeitüberschreitung). */
    private fun <T> runOnMain(block: () -> T): T? {
        val done = CountDownLatch(1)
        var result: T? = null
        main.post {
            try {
                result = block()
            } finally {
                done.countDown()
            }
        }
        return if (done.await(MAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) result else null
    }

    private companion object {
        const val MAIN_TIMEOUT_MS = 5_000L
        const val SETTLE_TIMEOUT_MS = 8_000L
        const val POLL_MS = 200L
        const val LINGER_MS = 300L
    }
}
