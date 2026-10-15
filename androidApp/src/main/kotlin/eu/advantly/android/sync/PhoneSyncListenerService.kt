package eu.advantly.android.sync

import android.os.Handler
import android.os.Looper
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import eu.advantly.device.link.WearDataLayerInbound
import eu.advantly.device.link.WearDataLayerTransport
import eu.advantly.match.session.AndroidMatchSessions
import eu.advantly.presentation.live.LiveTelemetryGate
import eu.advantly.presentation.live.LiveTelemetryStore
import eu.advantly.sync.protocol.LiveTelemetry
import eu.advantly.sync.protocol.LiveTelemetryCodec
import eu.advantly.sync.protocol.PeerKind
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Empfang von Uhr-Events, wenn keine Activity läuft (App im Hintergrund oder Prozess beendet, W1-I): Google Play
 * Services startet den Prozess für diesen manifestregistrierten Service (Pfad `/advantly/link…`).
 *
 * - **Nachricht** (Live-Kanal): Session sicherstellen, Frame an den laufenden Transport geben
 *   ([WearDataLayerInbound]) und warten, bis die Session ihn auf dem Main-Thread gespeichert hat – erst dann endet der
 *   Callback. Das Ack geht wie gewohnt an die Uhr; fehlt es, wiederholt die Uhr bzw. gleicht beim nächsten `Hello` ab.
 * - **DataItem** (Queue-Kanal, Kaltstart): Items einfrieren (`freeze()`), Session sicherstellen, jedes Item für dieses
 *   Gerät mit Verarbeitungsbestätigung an den Transport geben und den Callback offenhalten (mit Timeout), bis die
 *   Session alle gespeichert hat. Gelöscht wird ein Item erst NACH dem Speichern; bleibt es wegen Timeout/Prozess-Tod
 *   liegen, liest es der Transport beim nächsten Start erneut (idempotent).
 *
 * - **Telemetrie** (eigener Message-Pfad): nur RAM mit 30-s-Ablauf, nur bekannte eigene Uhr und passendes Workout-Match.
 *
 * Callbacks laufen auf einem Worker-Thread; die Session nur auf dem Main-Thread benutzen.
 */
class PhoneSyncListenerService : WearableListenerService() {
    private val main = Handler(Looper.getMainLooper())

    private val expireTelemetry = Runnable { LiveTelemetryStore.Phone.expire() }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == LiveTelemetry.MESSAGE_PATH) {
            val payload = LiveTelemetryCodec.decode(event.data) ?: return
            onMainAndWait {
                val state = AndroidMatchSessions.current()?.state?.value ?: return@onMainAndWait
                if (LiveTelemetryGate.accept(
                        payload,
                        event.sourceNodeId,
                        state.match?.id,
                        state.match?.meta?.watchWorkout == true,
                        state.sync.peers,
                    )
                ) {
                    LiveTelemetryStore.Phone.receive(payload)
                    main.removeCallbacks(expireTelemetry)
                    // Handler nutzt uptime: Tiefschlaf kann das Aufräumen verschieben; Lesen prüft elapsedRealtime.
                    main.postDelayed(expireTelemetry, LiveTelemetryStore.STALE_AFTER_MS)
                }
            }
            return
        }
        if (event.path != WearDataLayerTransport.MESSAGE_PATH) return
        onMainAndWait {
            ensureSession()
            WearDataLayerInbound.deliverMessage(event.sourceNodeId, event.path, event.data)
        }
        // Der Link verarbeitet Frames per post() auf dem Main-Thread: eine zweite Runde wartet diese Verarbeitung ab
        onMainAndWait {}
    }

    override fun onDataChanged(events: DataEventBuffer) {
        // Buffer gilt nur während dieses Aufrufs: Items einfrieren, dann freigeben
        val items = events.filter { it.type == DataEvent.TYPE_CHANGED }.map { it.dataItem.freeze() }
        events.release()
        onMainAndWait { ensureSession() }
        if (items.isEmpty()) return
        val localNode = localNodeId() ?: return // ohne eigene Node-ID kein sicheres Zielfiltern; Transport liest beim Start
        val dataClient = Wearable.getDataClient(applicationContext)
        val persisted = CountDownLatch(items.size)
        onMainAndWait {
            items.forEach { item ->
                val uri = item.uri
                val delivered =
                    WearDataLayerInbound.deliverQueuedItem(localNode, uri.host, uri.pathSegments, item.data) {
                        // Session hat den Frame gespeichert → erst jetzt aus der Queue löschen
                        dataClient.deleteDataItems(uri)
                        persisted.countDown()
                    }
                if (!delivered) persisted.countDown()
            }
        }
        persisted.await(QUEUE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun localNodeId(): String? =
        try {
            Tasks.await(Wearable.getNodeClient(applicationContext).localNode, QUEUE_TIMEOUT_SECONDS, TimeUnit.SECONDS).id
        } catch (e: ExecutionException) {
            null
        } catch (e: TimeoutException) {
            null
        } catch (e: InterruptedException) {
            null
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
        const val QUEUE_TIMEOUT_SECONDS = 10L
    }
}
