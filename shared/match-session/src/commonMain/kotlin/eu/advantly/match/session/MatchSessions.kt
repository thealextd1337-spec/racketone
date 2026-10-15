package eu.advantly.match.session

import eu.advantly.core.model.DeviceId
import eu.advantly.data.LocalData
import eu.advantly.device.link.DeviceLink
import eu.advantly.device.link.LinkScheduler
import eu.advantly.sync.protocol.PeerKind

/**
 * Einfache, DI-freie Fabrik für [MatchSession] (Koin kommt erst in den Apps). Plattform-Varianten mit fertigem
 * Link, Datenbank und Scheduler: `AndroidMatchSessions` (androidMain) und `AppleMatchSessions` (appleMain).
 */
object MatchSessions {
    /** Settings-Schlüssel der stabilen Installations-ID. */
    const val DEVICE_ID_KEY: String = "session.deviceId"

    /** Stabile Installations-ID: beim ersten Aufruf als UUIDv7 erzeugt und in den Settings gespeichert. */
    fun deviceId(
        data: LocalData,
        clock: WallClock = SystemWallClock,
        ids: IdGenerator = RandomIdGenerator(),
    ): DeviceId = DeviceId(data.settings.getOrPut(DEVICE_ID_KEY) { ids.newUuid7(clock.nowMs()).value })

    /**
     * Neue, noch nicht gestartete Session (danach [MatchSession.start] auf dem Scheduler-Thread aufrufen).
     *
     * @param link `null` = nur lokal zählen (z. B. Desktop-Preview, Uhr ohne Kopplung)
     * @param threadGuard erzwingt Aufrufe auf dem Scheduler-Thread (Plattform-Fabriken setzen ihn)
     */
    fun create(
        data: LocalData,
        link: DeviceLink?,
        scheduler: LinkScheduler,
        kind: PeerKind,
        clock: WallClock = SystemWallClock,
        ids: IdGenerator = RandomIdGenerator(),
        threadGuard: ThreadGuard = ThreadGuard.NONE,
    ): MatchSession = MatchSession(data, link, scheduler, SessionConfig(deviceId(data, clock, ids), kind), clock, ids, threadGuard)
}
