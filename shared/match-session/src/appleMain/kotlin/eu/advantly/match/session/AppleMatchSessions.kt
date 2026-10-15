package eu.advantly.match.session

import eu.advantly.data.createNativeLocalData
import eu.advantly.device.link.MainQueueScheduler
import eu.advantly.device.link.createWatchConnectivityLink
import eu.advantly.sync.protocol.PeerKind
import platform.Foundation.NSThread

/**
 * Prozessweite [MatchSession] für iPhone und Apple Watch (WatchConnectivity als Link). Aufruf nur auf der
 * Main-Queue. Die Swift-Facade der Uhr (`umbrella-watch`) kapselt das später mit einfachen Typen.
 */
object AppleMatchSessions {
    private var instance: MatchSession? = null

    /** Liefert die gestartete Session; beim ersten Aufruf werden Datenbank, Link und Session angelegt. */
    fun shared(kind: PeerKind): MatchSession =
        instance ?: run {
            val scheduler = MainQueueScheduler()
            MatchSessions
                .create(
                    createNativeLocalData(),
                    createWatchConnectivityLink(scheduler),
                    scheduler,
                    kind,
                    threadGuard = { NSThread.isMainThread },
                ).also {
                    it.start()
                    instance = it
                }
        }
}
