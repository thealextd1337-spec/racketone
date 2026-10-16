package eu.advantly.device.link

import platform.WatchConnectivity.WCSession

/**
 * [CompanionDetector] fürs iPhone: `WCSession.isPaired && isWatchAppInstalled` (die Watch-App muss nicht laufen).
 * WatchConnectivity meldet Änderungen nur an den einen Session-Delegate (gehört dem Transport) – daher fragt der
 * Detector beim Start, bei [refresh] und alle [POLL_MS] ms ab. Die Session aktiviert der Transport.
 */
class WatchConnectivityCompanionDetector(
    private val scheduler: LinkScheduler = MainQueueScheduler(),
) : CompanionDetector {
    private var listener: ((CompanionState) -> Unit)? = null
    private var last: CompanionState? = null
    private var timer: Cancellable? = null
    override var devices: List<CompanionDevice> = emptyList()
        private set

    override fun start(listener: (CompanionState) -> Unit) {
        this.listener = listener
        poll()
    }

    override fun refresh() {
        publish(current())
    }

    override fun stop() {
        timer?.cancel()
        timer = null
        listener = null
        last = null
    }

    private fun poll() {
        refresh()
        timer = scheduler.postDelayed(POLL_MS) { if (listener != null) poll() }
    }

    private fun current(): CompanionState {
        if (!WCSession.isSupported()) {
            devices = emptyList()
            return CompanionState.UNKNOWN
        }
        val session = WCSession.defaultSession
        devices = if (session.paired) listOf(CompanionDevice(WATCH_PEER, "", session.watchAppInstalled)) else emptyList()
        return when {
            !session.paired -> CompanionState.NO_WATCH
            !session.watchAppInstalled -> CompanionState.APP_NOT_INSTALLED
            else -> CompanionState.APP_INSTALLED
        }
    }

    private fun publish(state: CompanionState) {
        if (state == last) return
        last = state
        listener?.invoke(state)
    }

    private companion object {
        const val POLL_MS = 5_000L
    }
}
