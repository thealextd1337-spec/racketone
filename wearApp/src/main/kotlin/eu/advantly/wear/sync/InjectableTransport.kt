package eu.advantly.wear.sync

import eu.advantly.device.link.DeliveryResult
import eu.advantly.device.link.LinkTransport

/**
 * Hülle um den Plattform-Transport, die Frames aus dem `WearableListenerService` direkt an den laufenden Link
 * übergibt ([inject]). So wird eine Nachricht, die den Prozess erst geweckt hat (der Live-Listener war noch nicht
 * angemeldet), sofort verarbeitet und bestätigt – statt erst nach der Wiederholung des Absenders, die ein
 * eingefrorener Hintergrundprozess womöglich nicht mehr erlebt.
 *
 * Doppelte Zustellung (Live-Listener **und** Service) ist harmlos: `ReliableDeviceLink` verwirft Duplikate anhand
 * der Nachrichten-ID und bestätigt sie erneut; Events sind über ihre ID ohnehin idempotent.
 */
class InjectableTransport(
    private val delegate: LinkTransport,
) : LinkTransport {
    @Volatile private var listener: LinkTransport.Listener? = null

    override fun start(listener: LinkTransport.Listener) {
        this.listener = listener
        delegate.start(listener)
    }

    override fun stop() {
        listener = null
        delegate.stop()
    }

    override fun send(
        peer: String,
        frame: ByteArray,
        onResult: (DeliveryResult) -> Unit,
    ) = delegate.send(peer, frame, onResult)

    /** Übergibt einen empfangenen Frame von [peer] an den Link. `false`, solange der Link nicht läuft. */
    fun inject(
        peer: String,
        frame: ByteArray,
    ): Boolean {
        val target = listener ?: return false
        target.onFrame(peer, frame)
        return true
    }
}
