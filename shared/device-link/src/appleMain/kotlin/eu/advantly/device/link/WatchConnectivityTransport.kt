// NSUInteger/NSInteger sind auf watchosArm64 (arm64_32) 32 Bit, sonst 64 Bit → Umrechnung über convert()
@file:OptIn(kotlinx.cinterop.UnsafeNumber::class)

package eu.advantly.device.link

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.WatchConnectivity.WCSession
import platform.WatchConnectivity.WCSessionActivationStateActivated
import platform.WatchConnectivity.WCSessionDelegateProtocol

/**
 * [LinkTransport] über WatchConnectivity (`WCSession`), gleicher Kotlin-Code für iPhone und Apple Watch.
 *
 * Entscheidung Spike S3: **Kotlin/Native-Interop direkt** statt Swift-Adapter (siehe docs/spikes/s1-s3-watch-sync.md).
 * - **Live:** `sendMessageData(_:replyHandler:nil, errorHandler:)`, wenn `isReachable` (beide Apps aktiv).
 * - **Queue-Fallback:** `transferUserInfo` (garantierte, geordnete OS-Warteschlange), wenn nicht erreichbar
 *   oder das Live-Senden scheitert.
 * - **Peers:** genau ein Gegenüber ([counterpartPeer]); „verbunden“ = Session aktiviert, Gegen-App installiert, erreichbar.
 * - Kanalwahl als reine, getestete Funktion: [wcRoute] / [wcFallbackAfterLiveError].
 *
 * Wichtig: `WCSession.default` hat genau EINEN Delegate. Alle WatchConnectivity-Nutzung der App (auch spätere
 * `updateApplicationContext`-Aufrufe) muss deshalb über diese Klasse laufen – Swift darf den Delegate nicht setzen.
 * Workout Mirroring (`sendToRemoteWorkoutSession`, PLAN K4) ist ein eigener Transport und kommt mit der
 * Workout-Session (Swift/HealthKit) hinzu.
 */
@OptIn(ExperimentalForeignApi::class)
class WatchConnectivityTransport(
    private val counterpartPeer: String,
    private val useUserInfoQueue: Boolean = true,
) : LinkTransport {
    override fun isTrustedCounterpart(peer: String): Boolean = peer == counterpartPeer

    private var listener: LinkTransport.Listener? = null

    // Starke Referenz halten: WCSession.delegate ist weak
    private val delegate: WCSessionDelegateProtocol =
        newSessionDelegate(
            object : SessionEvents {
                override fun onActivationOrReachabilityChanged() = publishPeers()

                override fun onData(data: NSData) {
                    listener?.onFrame(counterpartPeer, data.toByteArray())
                }

                override fun onUserInfo(userInfo: Map<Any?, *>) {
                    val data = userInfo[USER_INFO_KEY] as? NSData ?: return
                    listener?.onFrame(counterpartPeer, data.toByteArray())
                }
            },
        )

    override fun start(listener: LinkTransport.Listener) {
        this.listener = listener
        if (!WCSession.isSupported()) {
            listener.onPeersChanged(emptyList(), available = false)
            return
        }
        val session = WCSession.defaultSession
        session.delegate = delegate
        session.activateSession()
        publishPeers()
    }

    override fun stop() {
        listener = null
        // Delegate bleibt gesetzt: WCSession lässt sich nicht deaktivieren; Ereignisse laufen ins Leere
    }

    override fun send(
        peer: String,
        frame: ByteArray,
        onResult: (DeliveryResult) -> Unit,
    ) {
        if (!WCSession.isSupported()) {
            onResult(DeliveryResult.FAILED)
            return
        }
        val session = WCSession.defaultSession
        val data = frame.toNSData()
        when (wcRoute(session.currentState(), useUserInfoQueue)) {
            WcRoute.LIVE -> {
                session.sendMessageData(data, replyHandler = null) { _ ->
                    // Asynchron (Hintergrund-Queue): Zustand NEU lesen – die Session kann inzwischen inaktiv sein
                    // (iOS-Uhrenwechsel); transferUserInfo auf inaktiver Session wäre ein Programmierfehler.
                    when (wcFallbackAfterLiveError(session.currentState(), useUserInfoQueue)) {
                        WcRoute.QUEUE -> enqueue(session, data, onResult)
                        else -> onResult(DeliveryResult.FAILED) // Ack-Timer wiederholt kontrolliert
                    }
                }
                onResult(DeliveryResult.SENT)
            }

            WcRoute.QUEUE -> {
                enqueue(session, data, onResult)
            }

            WcRoute.FAIL -> {
                onResult(DeliveryResult.FAILED)
            }
        }
    }

    private fun enqueue(
        session: WCSession,
        data: NSData,
        onResult: (DeliveryResult) -> Unit,
    ) {
        session.transferUserInfo(mapOf<Any?, Any?>(USER_INFO_KEY to data))
        onResult(DeliveryResult.QUEUED)
    }

    private fun publishPeers() {
        listener?.onPeersChanged(wcPeers(WCSession.defaultSession.currentState(), counterpartPeer), available = true)
    }

    private fun WCSession.currentState() =
        WcSessionState(
            activated = activationState == WCSessionActivationStateActivated,
            reachable = reachable,
            counterpartInstalled = counterpartAppInstalled(this),
        )

    private companion object {
        const val USER_INFO_KEY = "eu.advantly.link.frame"
    }
}

/** Plattformneutrale Delegate-Ereignisse (Delegate-Klassen liegen in iosMain/watchosMain). */
internal interface SessionEvents {
    fun onActivationOrReachabilityChanged()

    fun onData(data: NSData)

    fun onUserInfo(userInfo: Map<Any?, *>)
}

/**
 * iOS verlangt zusätzlich `sessionDidBecomeInactive`/`sessionDidDeactivate` (Uhrenwechsel); die gibt es auf
 * watchOS nicht – deshalb je Plattform eine eigene Delegate-Klasse.
 */
internal expect fun newSessionDelegate(events: SessionEvents): WCSessionDelegateProtocol

/** iOS: `isPaired && isWatchAppInstalled`; watchOS: `isCompanionAppInstalled`. */
internal expect fun counterpartAppInstalled(session: WCSession): Boolean
