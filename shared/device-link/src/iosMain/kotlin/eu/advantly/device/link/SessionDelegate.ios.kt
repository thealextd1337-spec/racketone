package eu.advantly.device.link

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.WatchConnectivity.WCSession
import platform.WatchConnectivity.WCSessionActivationState
import platform.WatchConnectivity.WCSessionDelegateProtocol
import platform.darwin.NSObject

internal actual fun counterpartPeerId(): String = WATCH_PEER

internal actual fun counterpartAppInstalled(session: WCSession): Boolean = session.paired && session.watchAppInstalled

internal actual fun newSessionDelegate(events: SessionEvents): WCSessionDelegateProtocol = IosSessionDelegate(events)

/** WCSession-Delegate (iPhone). Aufrufe kommen auf einer Hintergrund-Queue; [ReliableDeviceLink] wechselt auf Main. */
@OptIn(ExperimentalForeignApi::class)
private class IosSessionDelegate(
    private val events: SessionEvents,
) : NSObject(),
    WCSessionDelegateProtocol {
    override fun session(
        session: WCSession,
        activationDidCompleteWithState: WCSessionActivationState,
        error: NSError?,
    ) = events.onActivationOrReachabilityChanged()

    override fun sessionReachabilityDidChange(session: WCSession) = events.onActivationOrReachabilityChanged()

    // Pflicht auf iOS: Uhrenwechsel – danach neu aktivieren, damit die Session zur neuen Uhr wechselt
    override fun sessionDidBecomeInactive(session: WCSession) = events.onActivationOrReachabilityChanged()

    override fun sessionDidDeactivate(session: WCSession) {
        session.activateSession()
        events.onActivationOrReachabilityChanged()
    }

    override fun sessionWatchStateDidChange(session: WCSession) = events.onActivationOrReachabilityChanged()

    @ObjCSignatureOverride
    override fun session(
        session: WCSession,
        didReceiveMessageData: NSData,
    ) = events.onData(didReceiveMessageData)

    @ObjCSignatureOverride
    override fun session(
        session: WCSession,
        didReceiveUserInfo: Map<Any?, *>,
    ) = events.onUserInfo(didReceiveUserInfo)
}
