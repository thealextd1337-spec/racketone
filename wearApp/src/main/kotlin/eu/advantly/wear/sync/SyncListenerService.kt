package eu.advantly.wear.sync

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import eu.advantly.device.link.WearDataLayerInbound
import eu.advantly.device.link.WearDataLayerTransport
import eu.advantly.sync.protocol.LiveTelemetrySubscription
import eu.advantly.sync.protocol.LiveTelemetrySubscriptionCodec
import eu.advantly.wear.diagnostics.DiagnosticLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Hintergrund-Empfang der Uhr über die Wear Data Layer (W1-D/W1-I): Play Services startet diesen Dienst – und damit den
 * App-Prozess samt Session (`Application.onCreate`) –, wenn das Handy eine Nachricht schickt, die Queue sich ändert
 * oder das Handy wieder erreichbar wird, auch wenn die Uhr-App geschlossen ist. So kommt z. B. ein am Handy mit
 * „Meine Uhr“ gestartetes Match an: Das `Hello` der Uhr lässt die Handy-Session den Platzhalter-Zähler per
 * `ScorerChanged` an diese Uhr übergeben.
 *
 * - **Nachricht** (`/advantly/link/v1`): über [WearDataLayerInbound] an den laufenden Transport; der Callback endet erst,
 *   wenn die Session den Frame auf dem Main-Thread verarbeitet (und bestätigt) hat.
 * - **Queue-DataItem** (`/advantly/link/q/<diese-node>/…`): einfrieren, an den Transport geben, erst NACH dem Speichern
 *   löschen (bleibt es liegen, liest es der Transport beim nächsten Start erneut, idempotent).
 * - **Capability** (Handy wieder erreichbar): sofort abgleichen.
 *
 * Wurde der Prozess für den Aufruf frisch gestartet (Kaltstart), bleibt der Dienst gebunden, bis der Abgleich
 * abgeschlossen ist (Gegenstelle kompatibel, Abgleich empfangen, nichts ausstehend), höchstens [SETTLE_TIMEOUT_MS] –
 * damit Hello/Summary/fehlende Events und eine Übergabe durchlaufen, bevor das System den Prozess einfriert.
 */
class SyncListenerService : WearableListenerService() {
    private val main = Handler(Looper.getMainLooper())

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == LiveTelemetrySubscription.MESSAGE_PATH) {
            val payload = LiveTelemetrySubscriptionCodec.decode(event.data) ?: return
            val receivedAtMs = SystemClock.elapsedRealtime()
            // Nur beim Keepalive (~20 s) prüfen, nicht bei jedem Sensorupdate. Cloud-erreichbar allein reicht nicht.
            if (!isNearby(event.sourceNodeId)) return
            runOnMain {
                LiveTelemetrySubscriptions.Watch.receive(
                    payload,
                    event.sourceNodeId,
                    WearSessions
                        .get(this)
                        .session.state.value,
                    receivedAtMs,
                )
            }
            return
        }
        if (event.path != WearDataLayerTransport.MESSAGE_PATH) return
        DiagnosticLog.log("SYNC_SERVICE_MESSAGE", "node" to event.sourceNodeId, "bytes" to event.data.size)
        val cold =
            runOnMain {
                WearSessions.get(this) // Session (und damit Transport am Inbound) sicherstellen
                val delivered = WearDataLayerInbound.deliverMessage(event.sourceNodeId, event.path, event.data)
                if (!delivered) DiagnosticLog.log("SYNC_SERVICE_NOT_DELIVERED", "node" to event.sourceNodeId)
                WearSessions.consumeColdStart()
            }
        // Der Link verarbeitet Frames per post() auf dem Main-Thread: eine zweite Runde wartet diese Verarbeitung ab
        runOnMain {}
        if (cold == true) awaitSettled()
    }

    private fun isNearby(nodeId: String): Boolean =
        try {
            Tasks
                .await(
                    Wearable
                        .getCapabilityClient(
                            applicationContext,
                        ).getCapability(WearDataLayerTransport.CAPABILITY, CapabilityClient.FILTER_REACHABLE),
                    QUEUE_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS,
                ).nodes
                .any { it.id == nodeId && it.isNearby }
        } catch (_: ExecutionException) {
            false
        } catch (_: TimeoutException) {
            false
        } catch (_: InterruptedException) {
            false
        }

    override fun onDataChanged(events: DataEventBuffer) {
        // Buffer gilt nur während dieses Aufrufs: Items einfrieren, dann freigeben
        val items = events.filter { it.type == DataEvent.TYPE_CHANGED }.map { it.dataItem.freeze() }
        events.release()
        val cold =
            runOnMain {
                WearSessions.get(this)
                WearSessions.consumeColdStart()
            }
        val localNode = if (items.isEmpty()) null else localNodeId()
        if (localNode != null) {
            val dataClient = Wearable.getDataClient(applicationContext)
            val persisted = CountDownLatch(items.size)
            runOnMain {
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
            persisted.await(QUEUE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            DiagnosticLog.log("SYNC_SERVICE_QUEUE", "items" to items.size, "open" to persisted.count)
        }
        if (cold == true) awaitSettled()
    }

    override fun onCapabilityChanged(info: CapabilityInfo) {
        DiagnosticLog.log("SYNC_SERVICE_CAPABILITY", "nodes" to info.nodes.size)
        val cold =
            runOnMain {
                WearSessions.get(this).session.syncNow()
                WearSessions.consumeColdStart()
            }
        if (cold == true) awaitSettled()
    }

    /** Kaltstart: bis zum abgeschlossenen Abgleich gebunden bleiben (begrenzt). */
    private fun awaitSettled() {
        val deadline = SystemClock.elapsedRealtime() + SETTLE_TIMEOUT_MS
        var settled = false
        while (!settled && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(POLL_MS)
            settled = runOnMain { WearSessions.isSyncSettled() } == true
        }
        DiagnosticLog.log("SYNC_SERVICE_WAKE", "settled" to settled)
    }

    private fun localNodeId(): String? =
        try {
            Tasks.await(Wearable.getNodeClient(applicationContext).localNode, QUEUE_TIMEOUT_MS, TimeUnit.MILLISECONDS).id
        } catch (e: ExecutionException) {
            null
        } catch (e: TimeoutException) {
            null
        } catch (e: InterruptedException) {
            null
        }

    /** Führt [block] auf dem Main-Thread aus und wartet auf das Ergebnis (`null` bei Zeitüberschreitung). */
    private fun <T> runOnMain(block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
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
        const val QUEUE_TIMEOUT_MS = 10_000L
        const val SETTLE_TIMEOUT_MS = 8_000L
        const val POLL_MS = 200L
    }
}
