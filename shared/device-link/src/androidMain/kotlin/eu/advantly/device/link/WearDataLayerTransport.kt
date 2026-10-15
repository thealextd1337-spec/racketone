package eu.advantly.device.link

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import java.util.concurrent.atomic.AtomicLong

/**
 * [LinkTransport] über die Wear Data Layer API (Phone ↔ Wear, gleiche `applicationId` + Signatur Pflicht).
 *
 * - **Live:** `MessageClient.sendMessage` an die Node-ID (Pfad [MESSAGE_PATH]).
 * - **Queue-Fallback:** scheitert die Nachricht (Gegenseite nicht verbunden), wird der Frame als dringendes
 *   DataItem unter `/advantly/link/q/<ziel-node>/<eindeutig>` abgelegt. Die Data Layer synchronisiert es beim
 *   Wiederverbinden; der Empfänger liefert es aus und löscht es erst NACH der Verarbeitung durch den Link
 *   (Löschen wirkt auf allen Knoten). Generationen und Zielfilter: [WearQueueInbox].
 * - **Peers:** Knoten mit der Capability [CAPABILITY] (beide Apps deklarieren sie in `res/values/wear.xml`),
 *   Filter „erreichbar“; aktualisiert über `CapabilityClient`-Listener und alle [REFRESH_MS] ms.
 *
 * Listener laufen, solange [start] aktiv ist. Hintergrund-Empfang: Ein manifestregistrierter `WearableListenerService`
 * der App reicht Nachrichten über [WearDataLayerInbound] an den laufenden Transport weiter (Phone-App: W1-I).
 */
class WearDataLayerTransport(
    context: Context,
    /** DataItem-Queue nutzen, wenn die Gegenseite nicht live erreichbar ist. */
    private val useDataItemQueue: Boolean = true,
) : LinkTransport {
    private val appContext = context.applicationContext
    private val messageClient = Wearable.getMessageClient(appContext)
    private val dataClient = Wearable.getDataClient(appContext)
    private val capabilityClient = Wearable.getCapabilityClient(appContext)
    private val nodeClient = Wearable.getNodeClient(appContext)
    private val handler = Handler(Looper.getMainLooper())
    private val queueCounter = AtomicLong(System.currentTimeMillis())

    @Volatile private var listener: LinkTransport.Listener? = null

    private val inbox = WearQueueInbox { key -> dataClient.deleteDataItems(Uri.parse(key)) }

    private val messageListener =
        MessageClient.OnMessageReceivedListener { event ->
            if (event.path == MESSAGE_PATH) listener?.onFrame(event.sourceNodeId, event.data)
        }

    private val capabilityListener = CapabilityClient.OnCapabilityChangedListener { refreshPeers() }

    private val inboundSink: (String, ByteArray, () -> Unit) -> Unit = { node, frame, onConsumed ->
        listener?.onFrame(node, frame, onConsumed)
    }

    private val dataListener =
        DataClient.OnDataChangedListener { events ->
            val generation = inbox.currentGeneration
            val changed =
                events.filter { it.type == DataEvent.TYPE_CHANGED }.map { it.dataItem.freeze() }
            events.release()
            changed.forEach { offerQueuedItem(generation, it) }
        }

    private val refreshLoop =
        object : Runnable {
            override fun run() {
                refreshPeers()
                handler.postDelayed(this, REFRESH_MS)
            }
        }

    override fun start(listener: LinkTransport.Listener) {
        this.listener = listener
        WearDataLayerInbound.attach(inboundSink)
        val generation = inbox.start(listener)
        messageClient.addListener(messageListener)
        dataClient.addListener(dataListener)
        capabilityClient.addListener(capabilityListener, CAPABILITY)
        nodeClient.localNode.addOnSuccessListener { node: Node ->
            inbox.setLocalNode(generation, node.id)
            // Frames, die eingereiht wurden, während diese App nicht lief. Kommt das Ergebnis erst nach stop()
            // bzw. einem neuen Start, verwirft die Inbox es (Generation) – nichts wird gelöscht.
            dataClient.dataItems.addOnSuccessListener { buffer ->
                val items = buffer.map { it.freeze() }
                buffer.release()
                items.forEach { offerQueuedItem(generation, it) }
            }
        }
        handler.post(refreshLoop)
    }

    override fun stop() {
        WearDataLayerInbound.detach(inboundSink)
        handler.removeCallbacks(refreshLoop)
        messageClient.removeListener(messageListener)
        dataClient.removeListener(dataListener)
        capabilityClient.removeListener(capabilityListener, CAPABILITY)
        inbox.stop()
        listener = null
    }

    override fun send(
        peer: String,
        frame: ByteArray,
        onResult: (DeliveryResult) -> Unit,
    ) {
        messageClient
            .sendMessage(peer, MESSAGE_PATH, frame)
            .addOnSuccessListener { onResult(DeliveryResult.SENT) }
            .addOnFailureListener {
                if (useDataItemQueue) enqueue(peer, frame, onResult) else onResult(DeliveryResult.FAILED)
                refreshPeers()
            }
    }

    private fun enqueue(
        peer: String,
        frame: ByteArray,
        onResult: (DeliveryResult) -> Unit,
    ) {
        val request =
            PutDataRequest
                .create("$QUEUE_PREFIX/$peer/${queueCounter.incrementAndGet()}")
                .setData(frame)
                .setUrgent()
        dataClient
            .putDataItem(request)
            .addOnSuccessListener { onResult(DeliveryResult.QUEUED) }
            .addOnFailureListener { onResult(DeliveryResult.FAILED) }
    }

    private fun offerQueuedItem(
        generation: Int,
        item: DataItem,
    ) {
        val uri: Uri = item.uri
        inbox.offer(generation, uri.toString(), uri.host, uri.pathSegments, item.data)
    }

    private fun refreshPeers() {
        capabilityClient
            .getCapability(CAPABILITY, CapabilityClient.FILTER_REACHABLE)
            .addOnSuccessListener { info: CapabilityInfo ->
                listener?.onPeersChanged(info.nodes.map { it.id }.sorted(), available = true)
            }.addOnFailureListener { error ->
                val apiMissing = error is ApiException && error.statusCode == CommonStatusCodes.API_NOT_CONNECTED
                listener?.onPeersChanged(emptyList(), available = !apiMissing)
            }
    }

    companion object {
        /** Capability, die Phone- und Wear-App in `res/values/wear.xml` deklarieren. */
        const val CAPABILITY: String = "advantly_device_link"
        const val MESSAGE_PATH: String = "/advantly/link/v1"
        const val QUEUE_PREFIX: String = "/advantly/link/q"
        private const val REFRESH_MS = 5_000L
    }
}
