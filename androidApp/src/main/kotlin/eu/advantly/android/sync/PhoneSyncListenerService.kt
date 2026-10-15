package eu.advantly.android.sync

import android.os.Handler
import android.os.Looper
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import eu.advantly.device.link.WearDataLayerInbound
import eu.advantly.device.link.WearDataLayerTransport
import eu.advantly.match.session.AndroidMatchSessions
import eu.advantly.sync.protocol.PeerKind
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Empfang von Uhr-Events, wenn keine Activity läuft (App im Hintergrund oder Prozess beendet, W1-I): Google Play
 * Services startet den Prozess für diesen manifestregistrierten Service (Pfad `/advantly/link…`).
 *
 * - **Nachricht** (Live-Kanal): Session sicherstellen, Frame an den laufenden Transport geben
 *   ([WearDataLayerInbound]) und warten, bis die Session ihn auf dem Main-Thread gespeichert hat – erst dann endet der
 *   Callback. Das Ack geht wie gewohnt an die Uhr; fehlt es, wiederholt die Uhr bzw. gleicht beim nächsten `Hello` ab.
 * - **DataItem** (Queue-Kanal): Session sicherstellen; deren Transport liest beim Start alle wartenden Items und löscht
 *   sie erst nach der Verarbeitung (nichts geht verloren, auch wenn der Prozess danach endet).
 *
 * Callbacks laufen auf einem Worker-Thread; die Session nur auf dem Main-Thread benutzen.
 */
class PhoneSyncListenerService : WearableListenerService() {
    private val main = Handler(Looper.getMainLooper())

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WearDataLayerTransport.MESSAGE_PATH) return
        onMainAndWait {
            ensureSession()
            WearDataLayerInbound.deliverMessage(event.sourceNodeId, event.path, event.data)
        }
        // Der Link verarbeitet Frames per post() auf dem Main-Thread: eine zweite Runde wartet diese Verarbeitung ab
        onMainAndWait {}
    }

    override fun onDataChanged(events: DataEventBuffer) {
        events.release()
        onMainAndWait { ensureSession() }
    }

    private fun ensureSession() {
        AndroidMatchSessions.get(applicationContext, PeerKind.PHONE)
    }

    private fun onMainAndWait(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return
        }
        val done = CountDownLatch(1)
        main.post {
            try {
                block()
            } finally {
                done.countDown()
            }
        }
        done.await(MAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private companion object {
        const val MAIN_TIMEOUT_SECONDS = 5L
    }
}
