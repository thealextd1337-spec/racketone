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
    private var last: CompanionState? = null

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
                        if (info.nodes.any { it.id != local.id }) {
                            publish(CompanionState.APP_INSTALLED)
                        } else {
                            nodeClient.connectedNodes
                                .addOnSuccessListener { nodes ->
                                    publish(
                                        if (nodes.none { it.id != local.id }) CompanionState.NO_WATCH else CompanionState.APP_NOT_INSTALLED,
                                    )
                                }.addOnFailureListener { publish(CompanionState.UNKNOWN) }
                        }
                    }.addOnFailureListener { publish(CompanionState.UNKNOWN) }
            }.addOnFailureListener { publish(CompanionState.UNKNOWN) }
    }

    override fun stop() {
        handler.removeCallbacks(loop)
        capabilityClient.removeListener(capabilityListener, WearDataLayerTransport.CAPABILITY)
        listener = null
        last = null
    }

    private fun publish(state: CompanionState) {
        // Play-Services-Callbacks laufen auf dem Main-Thread
        if (state == last) return
        last = state
        listener?.invoke(state)
    }

    private companion object {
        const val REFRESH_MS = 30_000L
    }
}
