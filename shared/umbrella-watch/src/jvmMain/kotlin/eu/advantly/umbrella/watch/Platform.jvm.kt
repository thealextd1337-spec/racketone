package eu.advantly.umbrella.watch

import eu.advantly.data.EventStore
import eu.advantly.data.createInMemoryJvmEventStore

// jvm() existiert nur für Tests auf Windows/Linux
internal actual fun platformEventStore(): EventStore = createInMemoryJvmEventStore()

internal actual fun platformLinkDebug(): LinkDebug = throw UnsupportedOperationException("WatchConnectivity gibt es nur auf watchOS")
