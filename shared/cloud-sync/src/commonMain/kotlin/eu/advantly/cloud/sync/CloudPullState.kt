package eu.advantly.cloud.sync

enum class CloudPullOutcome { IDLE, RUNNING, COMPLETE, LIMIT_REACHED, DEADLINE_REACHED, BLOCKED, FAILED, CANCELLED, CLOSED }

/** Erfolg meint nur diesen begrenzten D1-Abgleich; D2/D3 bleiben ausdrücklich sichtbar. */
data class CloudPullState(
    val outcome: CloudPullOutcome = CloudPullOutcome.IDLE,
    val profileId: String? = null,
    val generation: Long? = null,
    val completedMetadataPages: Int = 0,
    val completedEventPages: Int = 0,
    val completedEventJobs: Int = 0,
    val rpcCount: Int = 0,
    val pendingRemovals: Long = 0,
    val pendingRedactions: Long = 0,
    val failure: String? = null,
) {
    val running: Boolean get() = outcome == CloudPullOutcome.RUNNING
}
