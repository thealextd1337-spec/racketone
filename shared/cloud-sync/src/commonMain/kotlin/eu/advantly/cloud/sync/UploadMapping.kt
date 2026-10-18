package eu.advantly.cloud.sync

import eu.advantly.data.CloudMetadataKind
import eu.advantly.data.CloudMetadataRevision
import eu.advantly.data.CloudUploadSnapshot
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Instant

/** Ausschließlich der eingefrorene Stand wird abgebildet; keine erneute Datenbankabfrage während HTTP. */
internal fun CloudUploadSnapshot.toPushBatch(): PushBatch {
    val headers = headers.associateBy { it.value.id }
    val participants = participants.associateBy { it.value.matchId }
    val projections = projections.filter { it.value.matchId in completeEventMatches }.associateBy { it.value.matchId }
    val matches =
        (headers.keys + participants.keys + projections.keys).sorted().map { id ->
            val header = headers[id]?.value
            val projection = projections[id]?.value
            val summary = projection?.let { CloudJson.parseToJsonElement(it.scoreJson).jsonObject }
            MatchDto(
                id = id,
                sport = header?.sport?.lowercase(),
                discipline = header?.discipline?.lowercase(),
                format = header?.let { CloudJson.parseToJsonElement(it.formatJson).jsonObject },
                meta = header?.let { CloudJson.parseToJsonElement(it.metaJson).jsonObject },
                startedAt = header?.createdAtMs?.let { Instant.fromEpochMilliseconds(it).toString() },
                previousMatchId = header?.previousMatchId,
                participants =
                    participants[id]?.value?.participants?.map {
                        ParticipantDto(it.slotId, it.team, it.position, it.displayName, it.playerId)
                    },
                projection =
                    projection?.let {
                        ProjectionDto(
                            status = it.status,
                            resultReason =
                                summary
                                    ?.get("reason")
                                    ?.jsonPrimitive
                                    ?.content
                                    ?.takeUnless { it == "null" }
                                    ?.lowercase(),
                            winner =
                                summary
                                    ?.get("winner")
                                    ?.jsonPrimitive
                                    ?.content
                                    ?.takeUnless { it == "null" },
                            startedAt = Instant.fromEpochMilliseconds(it.createdAtMs).toString(),
                            endedAt =
                                summary
                                    ?.get("lastEventAtMs")
                                    ?.jsonPrimitive
                                    ?.content
                                    ?.toLongOrNull()
                                    ?.takeIf { _ -> it.status == "finished" }
                                    ?.let { at -> Instant.fromEpochMilliseconds(at).toString() },
                            summary = summary,
                            eventCount =
                                summary
                                    ?.get("eventCount")
                                    ?.jsonPrimitive
                                    ?.content
                                    ?.toIntOrNull() ?: 0,
                        )
                    },
            )
        }
    return PushBatch(
        players = players.map { it.value.let { player -> PlayerDto(player.id, player.kind, player.displayName, player.deleted) } },
        matches = matches,
        events = events.map(EventRows::toRow),
    )
}

/** Nur ausdrücklich erfolgreiche Teilantworten quittieren; Hinweise halten Teilnehmerkorrekturen ausstehend. */
internal fun CloudUploadSnapshot.acknowledgedMetadata(
    batch: PushBatch,
    response: PushResponse,
): Set<CloudMetadataRevision> {
    fun checked(
        results: List<ItemResult>,
        ids: Set<String>,
    ): Map<String, ItemResult> {
        require(results.all { it.id != null && it.id in ids } && results.map { it.id }.distinct().size == results.size)
        return results.associateBy { requireNotNull(it.id) }
    }
    val matches = checked(response.matches, batch.matches.mapTo(mutableSetOf()) { it.id })
    val players = checked(response.players, batch.players.mapTo(mutableSetOf()) { it.id })
    val success = setOf("accepted", "duplicate", "ok", "created", "updated")
    return metadataRevisions.filterTo(mutableSetOf()) { version ->
        if (version.kind == CloudMetadataKind.PLAYER) {
            players[version.id]?.result in success
        } else {
            val sent = batch.matches.firstOrNull { it.id == version.id }
            val result = matches[version.id]
            when (version.kind) {
                CloudMetadataKind.HEADER -> {
                    result?.result in success && sent?.format != null
                }

                CloudMetadataKind.PARTICIPANTS -> {
                    result?.result in success && sent?.participants != null &&
                        result?.notes?.isEmpty() == true
                }

                CloudMetadataKind.PROJECTION -> {
                    sent?.projection != null && result?.projection in setOf("applied", "stale")
                }

                CloudMetadataKind.PLAYER -> {
                    false
                }
            }
        }
    }
}
