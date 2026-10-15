package eu.advantly.umbrella.ios

import eu.advantly.device.link.MainQueueScheduler
import eu.advantly.device.link.createWatchConnectivityLink
import eu.advantly.match.session.AppleMatchSessions
import eu.advantly.presentation.match.SessionMatchStore
import eu.advantly.sync.protocol.PeerKind
import eu.advantly.ui.phone.advantlyViewController
import platform.UIKit.UIViewController

/**
 * Öffentliche Swift-API von `AdvantlyKit` – bewusst klein halten (Facade).
 * Swift: `AdvantlyKitKt.mainViewController()`.
 *
 * Die Phone-UI läuft über der prozessweiten Match-Session (`AppleMatchSessions`, WatchConnectivity zur Apple Watch,
 * Datenbank im App-Container). Ohne Uhr bleibt der Link „nicht verfügbar“ und das iPhone zählt selbst (Director).
 * Aufruf auf der Main-Queue (SwiftUI `makeUIViewController`).
 */
fun mainViewController(): UIViewController = advantlyViewController { SessionMatchStore(AppleMatchSessions.shared(PeerKind.PHONE)) }

/** Ping-Pong-Feldtest über WatchConnectivity (Spike S3, nur Debug-UI). Swift: `AdvantlyKitKt.linkDebug()`. */
fun linkDebug(): LinkDebug {
    val scheduler = MainQueueScheduler()
    return LinkDebug(createWatchConnectivityLink(scheduler), scheduler)
}
