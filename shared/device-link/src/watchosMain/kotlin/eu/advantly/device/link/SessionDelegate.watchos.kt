// WCSessionActivationState (NSInteger) ist auf watchosArm64 (arm64_32) 32 Bit, auf watchosDeviceArm64 64 Bit
@file:OptIn(kotlinx.cinterop.UnsafeNumber::class)

package eu.advantly.device.link

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.WatchConnectivity.WCSession
import platform.WatchConnectivity.WCSessionActivationState
import platform.WatchConnectivity.WCSessionDelegateProtocol
import platform.darwin.NSObject

internal actual fun counterpartPeerId(): String = PHONE_PEER

internal actual fun counterpartAppInstalled(session: WCSession): Boolean = session.companionAppInstalled

internal actual fun newSessionDelegate(events: SessionEvents): WCSessionDelegateProtocol = WatchSessionDelegate(events)

/** WCSession-Delegate (Apple Watch). Aufrufe kommen auf einer Hintergrund-Queue; [ReliableDeviceLink] wechselt auf Main. */
@OptIn(ExperimentalForeignApi::class)
private class WatchSessionDelegate(
    private val events: SessionEvents,
) : NSObject(),
    WCSessionDelegateProtocol {
    override fun session(
        session: WCSession,
        activationDidCompleteWithState: WCSessionActivationState,
        error: NSError?,
    ) = events.onActivationOrReachabilityChanged()

    override fun sessionReachabilityDidChange(session: WCSession) = events.onActivationOrReachabilityChanged()

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
