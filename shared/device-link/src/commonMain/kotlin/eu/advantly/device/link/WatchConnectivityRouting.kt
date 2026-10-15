package eu.advantly.device.link

/**
 * Kanalwahl für WatchConnectivity als reine Funktion (in commonTest prüfbar, die Apple-Seite läuft nur auf macOS).
 *
 * Apple: `sendMessage*` nur bei `isReachable`; `transferUserInfo` nur auf einer **aktivierten** Session mit
 * installierter Gegen-App – auf einer inaktiven Session (z. B. iOS beim Uhrenwechsel zwischen
 * `sessionDidBecomeInactive` und `sessionDidDeactivate`) ist es ein Programmierfehler. In diesen Fällen liefert die
 * Funktion [WcRoute.FAIL]; der Ack-Timer von [ReliableDeviceLink] wiederholt kontrolliert, und nach der
 * Re-Aktivierung meldet der Transport die Gegenstelle neu (sofortige Wiederholung).
 */
internal enum class WcRoute {
    LIVE,
    QUEUE,
    FAIL,
}

internal data class WcSessionState(
    val activated: Boolean,
    val reachable: Boolean,
    /** iOS: gekoppelt + Watch-App installiert; watchOS: Companion-App installiert. */
    val counterpartInstalled: Boolean,
)

/** Kanal für einen neuen Sendeversuch. */
internal fun wcRoute(
    state: WcSessionState,
    useQueue: Boolean,
): WcRoute =
    when {
        !state.activated || !state.counterpartInstalled -> WcRoute.FAIL
        state.reachable -> WcRoute.LIVE
        useQueue -> WcRoute.QUEUE
        else -> WcRoute.FAIL
    }

/**
 * Kanal nach einem asynchronen Fehler von `sendMessageData`. [state] muss UNMITTELBAR vorher gelesen sein –
 * zwischen Senden und Fehler kann die Session inaktiv geworden sein.
 */
internal fun wcFallbackAfterLiveError(
    state: WcSessionState,
    useQueue: Boolean,
): WcRoute = if (useQueue && state.activated && state.counterpartInstalled) WcRoute.QUEUE else WcRoute.FAIL

/** Live erreichbare Gegenstellen für den aktuellen Session-Zustand. */
internal fun wcPeers(
    state: WcSessionState,
    counterpartPeer: String,
): List<String> = if (state.activated && state.counterpartInstalled && state.reachable) listOf(counterpartPeer) else emptyList()
