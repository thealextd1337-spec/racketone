package eu.advantly.device.link

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable

/**
 * [CompanionDetector] über die Wear Data Layer: Knoten mit der Capability [WearDataLayerTransport.CAPABILITY]
 * (`FILTER_ALL` – auch wenn die Uhr-App gerade nicht läuft) = App installiert; sonst verbundene Knoten ohne
 * Capability = Uhr ohne App. Reagiert auf Capability-Änderungen und fragt zusätzlich alle [REFRESH_MS] ms neu ab.
 */
class WearCompanionDetector(
    context: Context,
) : CompanionDetector {
    private val appContext = context.applicationContext
    private val capabilityClient = Wearable.getCapabilityClient(appContext)
    private val nodeClient = Wearable.getNodeClient(appContext)
    private val handler = Handler(Looper.getMainLooper())
    private var listener: ((CompanionState) -> Unit)? = null
    override var devices: List<CompanionDevice> = emptyList()
        private set

    private val capabilityListener = CapabilityClient.OnCapabilityChangedListener { refresh() }

    private val loop =
        object : Runnable {
            override fun run() {
                refresh()
                handler.postDelayed(this, REFRESH_MS)
            }
        }

    override fun start(listener: (CompanionState) -> Unit) {
        this.listener = listener
        capabilityClient.addListener(capabilityListener, WearDataLayerTransport.CAPABILITY)
        handler.post(loop)
    }

    override fun refresh() {
        nodeClient.localNode
            .addOnSuccessListener { local ->
                capabilityClient
                    .getCapability(WearDataLayerTransport.CAPABILITY, CapabilityClient.FILTER_ALL)
                    .addOnSuccessListener { info ->
                        nodeClient.connectedNodes
                            .addOnSuccessListener { connected ->
                                val nodes = (info.nodes + connected).filter { it.id != local.id }.distinctBy { it.id }
                                devices = nodes.map { CompanionDevice(it.id, it.displayName) }.sortedBy { it.peer }
                                publish(
                                    when {
                                        info.nodes.any { it.id != local.id } -> CompanionState.APP_INSTALLED
                                        nodes.isNotEmpty() -> CompanionState.APP_NOT_INSTALLED
                                        else -> CompanionState.NO_WATCH
                                    },
                                )
                            }.addOnFailureListener { publish(CompanionState.UNKNOWN) }
                    }.addOnFailureListener { publish(CompanionState.UNKNOWN) }
            }.addOnFailureListener { publish(CompanionState.UNKNOWN) }
    }

    override fun stop() {
        handler.removeCallbacks(loop)
        capabilityClient.removeListener(capabilityListener, WearDataLayerTransport.CAPABILITY)
        listener = null
    }

    private fun publish(state: CompanionState) {
        // Play-Services-Callbacks laufen auf dem Main-Thread
        listener?.invoke(state)
    }

    private companion object {
        const val REFRESH_MS = 30_000L
    }
}
