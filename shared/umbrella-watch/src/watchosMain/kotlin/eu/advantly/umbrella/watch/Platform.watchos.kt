package eu.advantly.umbrella.watch

import eu.advantly.data.LocalData
import eu.advantly.data.createNativeLocalData
import eu.advantly.device.link.MainQueueScheduler
import eu.advantly.device.link.createWatchConnectivityLink
import eu.advantly.match.session.MatchSession
import eu.advantly.match.session.MatchSessions
import eu.advantly.match.session.ThreadGuard
import eu.advantly.sync.protocol.PeerKind
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSThread
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform

internal actual fun platformLocalData(): LocalData = createNativeLocalData()

@OptIn(ExperimentalNativeApi::class)
internal actual fun platformDemoEnabled(): Boolean =
    Platform.isDebugBinary && NSProcessInfo.processInfo.arguments.contains("-advantlyDemoStage")

/**
 * Wie `AppleMatchSessions.shared(PeerKind.WATCH)`, aber auf der [LocalData] der Facade, damit Store-Smoke und Session
 * dieselbe Datenbankverbindung nutzen. `WCSession.default` hat genau einen Delegate – deshalb läuft daneben kein
 * zweiter WatchConnectivity-Link (der Ping-Pong-Debug-Screen aus Spike S3 ist deshalb entfernt).
 */
internal actual fun platformWatchSession(data: LocalData): MatchSession {
    val scheduler = MainQueueScheduler()
    return MatchSessions
        .create(
            data,
            createWatchConnectivityLink(scheduler),
            scheduler,
            PeerKind.WATCH,
            threadGuard = ThreadGuard { NSThread.isMainThread },
        ).also { it.start() }
}
