package eu.advantly.device.link

/**
 * Unzuverlässiger Plattform-Transport für einzelne Frames (Wear Data Layer, WatchConnectivity, In-Memory-Fake).
 * Ack, Retry und Dedup macht [ReliableDeviceLink] darüber. Implementierungen dürfen Callbacks auf beliebigen
 * Threads aufrufen; [ReliableDeviceLink] reicht sie an seinen [LinkScheduler] weiter.
 */
interface LinkTransport {
    fun start(listener: Listener)

    fun stop()

    /**
     * Versucht [frame] an [peer] zu übertragen. [onResult] kann mehrfach kommen
     * (z. B. erst [DeliveryResult.SENT], nach einem späten Fehler noch [DeliveryResult.QUEUED]).
     */
    fun send(
        peer: String,
        frame: ByteArray,
        onResult: (DeliveryResult) -> Unit,
    )

    interface Listener {
        /**
         * Neuer Frame von [peer]. [onConsumed] ruft der Empfänger erst, NACHDEM er den Frame verarbeitet hat
         * (bei gestopptem Link nie). Transporte mit persistenter Queue (Wear `DataItem`) löschen den Eintrag erst dann –
         * so geht kein Frame verloren, wenn er zwischen Übergabe und Verarbeitung auf einen `stop()` trifft.
         */
        fun onFrame(
            peer: String,
            frame: ByteArray,
            onConsumed: () -> Unit = {},
        )

        /** [available] = false, wenn die Plattform-API grundsätzlich fehlt. */
        fun onPeersChanged(
            peers: List<String>,
            available: Boolean,
        )
    }
}

enum class DeliveryResult {
    /** Live übertragen (Zustellung nicht garantiert – das bestätigt erst das Ack). */
    SENT,

    /** In die OS-Warteschlange gelegt (Wear `DataClient`, Apple `transferUserInfo`); Zustellung später. */
    QUEUED,

    /** Weder live noch per Queue möglich. */
    FAILED,
}
