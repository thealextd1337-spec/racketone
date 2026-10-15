package eu.advantly.umbrella.watch

import eu.advantly.data.LocalData
import eu.advantly.data.createInMemoryJvmLocalData
import eu.advantly.device.link.testing.ManualScheduler
import eu.advantly.match.session.MatchSession
import eu.advantly.match.session.MatchSessions
import eu.advantly.sync.protocol.PeerKind

// jvm() existiert nur für Tests auf Windows/Linux
internal actual fun platformLocalData(): LocalData = createInMemoryJvmLocalData()

internal actual fun platformWatchSession(data: LocalData): MatchSession =
    MatchSessions.create(data, link = null, scheduler = ManualScheduler(), kind = PeerKind.WATCH).also { it.start() }
