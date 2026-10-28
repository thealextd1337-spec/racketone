package eu.advantly.cloud.sync

import eu.advantly.core.scoring.GameKind
import eu.advantly.data.CloudMetadataKind
import eu.advantly.data.CloudMetadataRevision
import eu.advantly.data.CloudUploadSnapshot
import eu.advantly.data.ScoreSummary
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
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
                        ParticipantDto(it.slotId, it.team, it.position, it.displayName, it.playerId, it.profileId)
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
                            liveState = liveStateOf(it.status, it.scoreJson),
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
    val referenced = matches.map { it.id }.toSet() + events.map { it.matchId }
    return PushBatch(
        players = players.map { it.value.let { player -> PlayerDto(player.id, player.kind, player.displayName, player.deleted) } },
        matches = matches,
        events = events.map(EventRows::toRow),
        // E nur für tatsächlich gesendete Matches; Player-only behält G mit leerer Map.
        lifecycle =
            lifecycle?.let { proof ->
                CloudPushLifecycle(proof.generation, proof.redactionEpochs.filterKeys { it in referenced }.mapValues { it.value.toInt() })
            },
    )
}

private val LIVE_POINTS = Regex("^[0-9A-Za-z]{1,3}$")

/**
 * Live-Stand eines laufenden Matches für Visitor (F16): nur Felder, die `private.sanitize_projection` behält
 * (Sätze, Spiele, Punktanzeige, Tiebreak, Status, Zeit) – nie Telemetrie, Gesundheitsdaten oder IDs.
 * Aufschlag fehlt, weil die Projektion ([ScoreSummary]) ihn nicht enthält. Beendete Matches: `null` (Ergebnis kommt aus summary).
 */
internal fun liveStateOf(
    status: String,
    scoreJson: String,
): JsonObject? {
    if (status == "finished") return null
    val score = runCatching { CloudJson.decodeFromString(ScoreSummary.serializer(), scoreJson) }.getOrNull() ?: return null
    val current = score.currentState
    return buildJsonObject {
        putJsonArray("sets") {
            score.sets.take(9).forEach { set ->
                addJsonArray {
                    add(set.a)
                    add(set.b)
                }
            }
        }
        current?.games?.let { games ->
            putJsonArray("games") {
                add(games.a)
                add(games.b)
            }
        }
        current?.display?.takeIf { LIVE_POINTS.matches(it.a) && LIVE_POINTS.matches(it.b) }?.let { display ->
            putJsonArray("points") {
                add(display.a)
                add(display.b)
            }
        }
        current?.let { put("tiebreak", it.gameKind == GameKind.TIEBREAK || it.gameKind == GameKind.MATCH_TIEBREAK) }
        put("status", status)
        score.lastEventAtMs?.let { put("updatedAtMs", it) }
    }
}

/**
 * Endgültige Verknüpfungshinweise (P2-E): Der Server hat den Platz als unverknüpften Namen gespeichert; ein erneuter
 * Versand ändert daran nichts. Die Besetzung gilt als quittiert, der Hinweis wird für die Anzeige gespeichert.
 */
internal val FINAL_LINK_NOTES = setOf("not_friend", "link_rejected", "profile_already_in_match")

/** Nur ausdrücklich erfolgreiche Teilantworten quittieren; andere Hinweise halten Teilnehmerkorrekturen ausstehend. */
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
                    val slots = sent?.participants?.mapTo(mutableSetOf()) { it.slotId }
                    result?.result in success && slots != null &&
                        result?.notes?.all { it.note in FINAL_LINK_NOTES && it.slotId in slots } == true
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

/** Hinweise je quittierter Besetzung (leer = keine); Grundlage für die Anzeige im Match-Detail. */
internal fun linkNotes(
    acknowledged: Set<CloudMetadataRevision>,
    response: PushResponse,
): Map<String, Map<String, String>> =
    acknowledged.filter { it.kind == CloudMetadataKind.PARTICIPANTS }.associate { version ->
        version.id to
            response.matches
                .first { it.id == version.id }
                .notes
                .associate { it.slotId to it.note }
    }
