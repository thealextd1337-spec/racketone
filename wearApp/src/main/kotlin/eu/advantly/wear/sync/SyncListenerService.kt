package eu.advantly.wear.sync

import android.os.Handler
import android.os.Looper
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
 * Die Callbacks laufen auf einem Hintergrund-Thread; die Session ist Main-Thread-gebunden. Danach bleibt der Dienst
 * kurz gebunden ([LINGER_MS]), damit Antwort-Runden (Summary, fehlende Events) noch durchlaufen, bevor das System den
 * Prozess einfriert.
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
        val done = CountDownLatch(1)
        var fresh = false
        main.post {
            try {
                fresh = !WearSessions.isStarted
                val holder = WearSessions.get(this)
                if (fresh) DiagnosticLog.log("SYNC_SERVICE_WAKE")
                action(holder)
            } finally {
                done.countDown()
            }
        }
        if (!done.await(MAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return
        // Frisch geweckter Prozess: Abgleich-Runden (Hello → Summary → Events) abwarten; sonst nur kurz
        Thread.sleep(if (fresh) LINGER_AFTER_WAKE_MS else LINGER_MS)
    }

    private companion object {
        const val MAIN_TIMEOUT_MS = 5_000L
        const val LINGER_AFTER_WAKE_MS = 3_000L
        const val LINGER_MS = 300L
    }
}
