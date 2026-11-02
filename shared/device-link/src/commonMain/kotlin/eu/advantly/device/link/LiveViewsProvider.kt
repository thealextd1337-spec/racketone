package eu.advantly.device.link

import eu.advantly.sync.protocol.LiveView

/**
 * Handy-Seite des Uhr-Zuschauens (plan-live-mitspieler §3.2): liefert die aktuellen fremden Live-Matches für eine
 * [eu.advantly.sync.protocol.LiveViewSubscription] der eigenen Uhr. Das Konto-Paket füllt sie aus
 * `cloud_my_live_matches_v1` (§8); ohne Konto bleibt es bei [Empty].
 *
 * Vertrag: genau ein Serverabruf je Aufruf, kein Cache über den Aufruf hinaus. Leere Liste = nichts live (auch: nicht
 * angemeldet). Bei Netz-/Serverfehler eine Exception werfen statt `emptyList()`, damit die Uhr ihren letzten Stand
 * (mit wachsendem Alter) behält, statt „nichts live“ zu zeigen. Kürzen auf 5 Matches und 2 KB übernimmt der Codec.
 */
interface LiveViewsProvider {
    suspend fun current(): List<LiveView>

    /** Standard ohne Konto: nie etwas live. */
    object Empty : LiveViewsProvider {
        override suspend fun current(): List<LiveView> = emptyList()
    }
}
