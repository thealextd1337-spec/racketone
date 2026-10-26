package eu.advantly.cloud.sync

import kotlinx.serialization.Serializable

/** Server-Sichtbarkeit G und Match-Redaktionen E; unabhängig von der lokalen Auth-Generation. */
@Serializable
data class CloudPushLifecycle(
    val generation: Long,
    val redactionEpochs: Map<String, Int>,
) {
    init {
        require(generation >= 0)
        require(redactionEpochs.all { (id, epoch) -> CLOUD_UUID.matches(id) && epoch >= 0 })
    }
}

/** Ein begrenzter Manifest-Abruf. Fortsetzungen behalten G, E und den ersten Wasserstand H bei. */
data class CloudRedactionRequest(
    val matchId: String,
    val generation: Long,
    val redactionEpoch: Int,
    val afterPlayerId: String? = null,
    val afterServerSeq: Long = 0,
    val highWatermark: Long? = null,
    val playerLimit: Int = 200,
    val eventLimit: Int = 500,
) {
    init {
        require(CLOUD_UUID.matches(matchId))
        require(generation >= 0 && redactionEpoch >= 0 && afterServerSeq >= 0)
        require(afterPlayerId == null || (CLOUD_UUID.matches(afterPlayerId) && afterPlayerId != NIL_UUID))
        require(playerLimit in 1..200 && eventLimit in 1..500)
        require(
            if (highWatermark == null) {
                afterPlayerId == null && afterServerSeq == 0L
            } else {
                highWatermark >= afterServerSeq
            },
        )
    }
}

/** Nur die authentisierte Cloud-Antwort liefert Manifest-Autorität; ein Login allein genügt nicht. */
@Serializable
data class CloudRedactionPage(
    val matchId: String,
    val generation: Long,
    val redactionEpoch: Int,
    val highWatermark: Long,
    val redactedPlayerIds: List<String>,
    val events: List<PulledEvent>,
    val nextPlayerId: String?,
    val nextServerSeq: Long,
    val hasMorePlayers: Boolean,
    val hasMoreEvents: Boolean,
) {
    internal fun validate(request: CloudRedactionRequest) {
        requireValid(matchId == request.matchId && generation == request.generation && redactionEpoch == request.redactionEpoch)
        requireValid(highWatermark >= request.afterServerSeq)
        requireValid(request.highWatermark == null || highWatermark == request.highWatermark)
        requireValid(redactedPlayerIds.size <= request.playerLimit && events.size <= request.eventLimit)
        var player = request.afterPlayerId
        for (id in redactedPlayerIds) {
            requireValid(CLOUD_UUID.matches(id) && id != NIL_UUID && (player == null || id > player))
            player = id
        }
        requireValid(nextPlayerId == player && (!hasMorePlayers || redactedPlayerIds.isNotEmpty()))
        var seq = request.afterServerSeq
        val eventIds = mutableSetOf<String>()
        for (event in events) {
            requireValid(event.matchId == matchId && event.redacted && event.serverSeq > seq && event.serverSeq <= highWatermark)
            requireValid(event.id.isNotBlank() && eventIds.add(event.id))
            seq = event.serverSeq
        }
        requireValid(nextServerSeq == seq && (!hasMoreEvents || events.isNotEmpty()))
    }

    private fun requireValid(valid: Boolean) {
        if (!valid) throw CloudException("cloud_response_invalid", false)
    }
}

private val CLOUD_UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
private const val NIL_UUID = "00000000-0000-0000-0000-000000000000"
