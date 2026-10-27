package eu.advantly.data

/** Getrennte Upload-Einheiten; Teilnehmer werden als vollständige Besetzung quittiert. */
enum class CloudMetadataKind { HEADER, PARTICIPANTS, PLAYER, PROJECTION }

enum class CloudMetadataOrigin { UNKNOWN, LOCAL, PEER, CLOUD }

/** Ein Ack nennt zwingend die ausgewählte Version, niemals nur die ID. */
data class CloudMetadataRevision(
    val kind: CloudMetadataKind,
    val id: String,
    val revision: Long,
)

data class CloudMetadataState(
    val selected: CloudMetadataRevision,
    val uploadedRevision: Long,
    val origin: CloudMetadataOrigin,
    val sourceDeviceId: String?,
    val writerProfileId: String?,
)

data class CloudRevisioned<T>(
    val version: CloudMetadataRevision,
    val value: T,
)

data class CloudMatchHeader(
    val id: String,
    val sport: String,
    val discipline: String,
    val formatJson: String,
    val metaJson: String,
    val createdAtMs: Long,
    val previousMatchId: String?,
)

data class CloudParticipant(
    val slotId: String,
    val team: String,
    val position: Int,
    val displayName: String,
    val playerId: String?,
    /** Konto eines als Freund eingetragenen Mitspielers ([PlayerKind.FRIEND]); der Server verknüpft nur Freunde. */
    val profileId: String? = null,
)

/** Auch die verschachtelte Liste lässt sich von außen nicht nachträglich verändern. */
class CloudParticipants internal constructor(
    val matchId: String,
    participants: List<CloudParticipant>,
) {
    private val selected = participants.toList()
    val participants: List<CloudParticipant> get() = selected.toList()
}

data class CloudPlayer(
    val id: String,
    val kind: String,
    val displayName: String,
    val deleted: Boolean,
)

data class CloudProjection(
    val matchId: String,
    val status: String,
    val scoreJson: String,
    val createdAtMs: Long,
)
