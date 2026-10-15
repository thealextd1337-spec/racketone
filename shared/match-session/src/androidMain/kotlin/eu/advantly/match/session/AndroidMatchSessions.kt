package eu.advantly.match.session

import android.content.Context
import eu.advantly.data.createAndroidLocalData
import eu.advantly.device.link.MainLooperScheduler
import eu.advantly.device.link.createWearDeviceLink
import eu.advantly.sync.protocol.PeerKind

/**
 * Prozessweite [MatchSession] für Phone- und Wear-App (gleicher Code, Wear Data Layer als Link).
 * Aufruf nur auf dem Main-Thread; die Session läuft bis zum Prozessende.
 */
object AndroidMatchSessions {
    private var instance: MatchSession? = null

    /** Liefert die gestartete Session; beim ersten Aufruf werden Datenbank, Link und Session angelegt. */
    fun get(
        context: Context,
        kind: PeerKind,
    ): MatchSession =
        instance ?: run {
            val app = context.applicationContext
            val scheduler = MainLooperScheduler()
            MatchSessions
                .create(createAndroidLocalData(app), createWearDeviceLink(app, scheduler), scheduler, kind)
                .also {
                    it.start()
                    instance = it
                }
        }
}
