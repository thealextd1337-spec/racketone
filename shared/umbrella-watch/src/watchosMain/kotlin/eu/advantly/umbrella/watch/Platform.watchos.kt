package eu.advantly.umbrella.watch

import eu.advantly.data.EventStore
import eu.advantly.data.createNativeEventStore
import eu.advantly.device.link.MainQueueScheduler
import eu.advantly.device.link.ReliableDeviceLink
import eu.advantly.device.link.createWatchConnectivityLink

internal actual fun platformEventStore(): EventStore = createNativeEventStore()

internal actual fun platformLinkDebug(): LinkDebug {
    val scheduler = MainQueueScheduler()
    val link: ReliableDeviceLink = createWatchConnectivityLink(scheduler)
    return LinkDebug(link, scheduler)
}
