package eu.advantly.umbrella.ios

import eu.advantly.device.link.MainQueueScheduler
import eu.advantly.device.link.createWatchConnectivityLink
import eu.advantly.ui.phone.advantlyViewController
import platform.UIKit.UIViewController

/**
 * Öffentliche Swift-API von `AdvantlyKit` – bewusst klein halten (Facade).
 * Swift: `AdvantlyKitKt.mainViewController()`.
 */
fun mainViewController(): UIViewController = advantlyViewController()

/** Ping-Pong-Feldtest über WatchConnectivity (Spike S3, nur Debug-UI). Swift: `AdvantlyKitKt.linkDebug()`. */
fun linkDebug(): LinkDebug {
    val scheduler = MainQueueScheduler()
    return LinkDebug(createWatchConnectivityLink(scheduler), scheduler)
}
