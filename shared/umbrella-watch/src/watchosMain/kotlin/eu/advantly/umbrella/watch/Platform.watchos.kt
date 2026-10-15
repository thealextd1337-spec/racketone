package eu.advantly.umbrella.watch

import eu.advantly.data.LocalData
import eu.advantly.data.createNativeLocalData
import eu.advantly.device.link.MainQueueScheduler
import eu.advantly.device.link.ReliableDeviceLink
import eu.advantly.device.link.createWatchConnectivityLink
import eu.advantly.match.session.MatchSession
import eu.advantly.match.session.MatchSessions
import eu.advantly.match.session.ThreadGuard
import eu.advantly.sync.protocol.PeerKind
import platform.Foundation.NSThread

internal actual fun platformLocalData(): LocalData = createNativeLocalData()

/**
 * Wie `AppleMatchSessions.shared(PeerKind.WATCH)`, aber auf der [LocalData] der Facade, damit Store-Smoke und Session
 * dieselbe Datenbankverbindung nutzen. `WCSession.default` hat genau einen Delegate – deshalb darf daneben kein
 * zweiter WatchConnectivity-Link laufen (Ausnahme: Debug-Screen „Link-Debug“, siehe [platformLinkDebug]).
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

/** Nur Debug: übernimmt den WCSession-Delegate – die Match-Session ist danach bis zum App-Neustart ohne Link. */
internal actual fun platformLinkDebug(): LinkDebug {
    val scheduler = MainQueueScheduler()
    val link: ReliableDeviceLink = createWatchConnectivityLink(scheduler)
    return LinkDebug(link, scheduler)
}
