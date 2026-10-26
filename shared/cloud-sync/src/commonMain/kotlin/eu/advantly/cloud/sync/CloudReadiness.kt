package eu.advantly.cloud.sync

import kotlinx.serialization.Serializable

/**
 * Vollständigkeitsabruf eines Mitgliedsmatches, gebunden an G, E und Wasserstand H. Mit Redaktionstransfer (E > 0)
 * ist H dessen [CloudRedactionPage.highWatermark]; ohne Transfer (E = 0) hält der Server H beim Abruf fest.
 * Abweichendes G/E liefert `restart_required`.
 */
data class CloudReadinessRequest(
    val matchId: String,
    val generation: Long,
    val redactionEpoch: Int,
    val highWatermark: Long? = null,
) {
    init {
        require(CLOUD_UUID.matches(matchId))
        require(generation >= 0 && redactionEpoch >= 0 && (highWatermark == null || highWatermark >= 0))
    }
}

/**
 * Serverseitiger Versionsvektor aller Events mit `serverSeq <= H` und Snapshot-Version des Matchkopfs.
 * Nur zusammen mit vollständig angewendetem Redaktionstransfer derselben G/E/H ein Readiness-Beleg;
 * `Events.more=false` oder leere Wasserstände allein sind es nicht.
 */
@Serializable
data class CloudMatchReadiness(
    val matchId: String,
    val generation: Long,
    val redactionEpoch: Int,
    val highWatermark: Long,
    val serverVersion: Long,
    val watermarks: List<EventWatermark>,
) {
    internal fun validate(request: CloudReadinessRequest) {
        requireValid(
            matchId == request.matchId && generation == request.generation &&
                redactionEpoch == request.redactionEpoch && (request.highWatermark == null || highWatermark == request.highWatermark),
        )
        requireValid(highWatermark >= 0)
        requireValid(serverVersion >= 0 && watermarks.size <= MAX_READINESS_DEVICES)
        var previous: String? = null
        var total = 0L
        for (mark in watermarks) {
            requireValid(CLOUD_UUID.matches(mark.deviceId) && (previous == null || mark.deviceId > previous))
            requireValid(mark.maxSeq >= 0 && mark.eventCount >= 1)
            total += mark.eventCount
            previous = mark.deviceId
        }
        // Jedes Event belegt eine eigene Serversequenz in 1..H.
        requireValid(total <= highWatermark)
    }

    private fun requireValid(valid: Boolean) {
        if (!valid) throw CloudException("cloud_response_invalid", false)
    }

    companion object {
        const val MAX_READINESS_DEVICES = 256
    }
}

private val CLOUD_UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
