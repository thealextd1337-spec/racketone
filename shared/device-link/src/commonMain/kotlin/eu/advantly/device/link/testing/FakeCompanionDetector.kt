package eu.advantly.device.link.testing

import eu.advantly.device.link.CompanionDetector
import eu.advantly.device.link.CompanionState

/** [CompanionDetector] für Tests und Previews: Stand wird von Hand gesetzt, [refreshes] zählt Neuabfragen. */
class FakeCompanionDetector(
    initial: CompanionState = CompanionState.UNKNOWN,
) : CompanionDetector {
    private var listener: ((CompanionState) -> Unit)? = null

    var state: CompanionState = initial
        set(value) {
            field = value
            listener?.invoke(value)
        }

    var refreshes: Int = 0
        private set

    override fun start(listener: (CompanionState) -> Unit) {
        this.listener = listener
        listener(state)
    }

    override fun refresh() {
        refreshes++
        listener?.invoke(state)
    }

    override fun stop() {
        listener = null
    }
}
