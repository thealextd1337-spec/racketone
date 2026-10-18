package eu.advantly.cloud.sync

import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchFormat
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.PlayerId
import eu.advantly.core.scoring.LineupValidator
import eu.advantly.core.scoring.MatchCreated
import eu.advantly.core.scoring.MatchFormatValidator
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.data.CloudPullFriendship
import eu.advantly.data.CloudPullMatch
import eu.advantly.data.CloudPullMetadataPage
import eu.advantly.data.CloudPullPlayer
import eu.advantly.data.CloudPullReceipt
import eu.advantly.data.LocalPlayer
import eu.advantly.data.MatchRecord
import eu.advantly.data.PlayerKind
import eu.advantly.data.ScoreSummary
import eu.advantly.data.toScoreEvent
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Instant

/** Erst die vollständige Seite prüfen; weder alte Minimalformate noch fehlende Regeln erraten. */
internal object PullMapping {
    fun metadata(
        response: PullResponse,
        inputCursor: Long,
        limit: Int,
    ): CloudPullMetadataPage {
        require(response.cursor >= inputCursor && response.visibilityEpoch >= 0)
        require(!response.hasMore || response.cursor > inputCursor)
        require(response.matches.size <= limit)
        requireUnique(response.matches.map { it.id })
        requireUnique(response.players.map { it.id })
        requireUnique(response.friendships.map { it.profileId })
        return CloudPullMetadataPage(
            response.matches.map(::match),
            response.players.map {
                require(it.serverVersion > 0 && it.kind in setOf("self", "guest"))
                require(it.displayName.isNotBlank() || it.deleted)
                CloudPullPlayer(
                    it.id,
                    it.serverVersion,
                    CloudJson.encodeToString(PulledPlayer.serializer(), it),
                    it.owned,
                    LocalPlayer(PlayerId(it.id), it.displayName, if (it.deleted) PlayerKind.ARCHIVED else PlayerKind.GUEST, 0, null),
                )
            },
            response.friendships.map {
                require(it.serverVersion > 0 && it.status in setOf("pending", "accepted", "declined"))
                CloudPullFriendship(it.profileId, it.serverVersion, CloudJson.encodeToString(PulledFriendship.serializer(), it))
            },
            response.cursor,
            response.hasMore,
            response.visibilityEpoch,
        )
    }

    private fun match(row: PulledMatch): CloudPullMatch {
        val engineStatus =
            when (row.status) {
                "scheduled" -> null
                "in_progress" -> MatchStatus.IN_PROGRESS
                "paused" -> MatchStatus.PAUSED
                "awaiting_decision" -> MatchStatus.AWAITING_DECISION
                "finished" -> MatchStatus.FINISHED
                else -> throw IllegalArgumentException("Unbekannter Cloud-Matchstatus")
            }
        require(row.serverVersion > 0 && row.access in setOf("member", "feed"))
        require(row.redactionEpoch >= 0 && (row.accessEpoch == null || row.accessEpoch >= 0))
        require(row.projectionEventCount >= 0)
        requireUnique(row.participants.map { it.slotId })
        require(row.participants.all { it.team in setOf("A", "B") && it.position in 1..2 })
        require(
            row.participants
                .map { it.team to it.position }
                .distinct()
                .size == row.participants.size,
        )
        require(
            row.roles.all {
                it.profileId.isNotBlank() && it.role in setOf("scorer", "director") &&
                    it.status in setOf("invited", "accepted", "declined", "revoked")
            },
        )
        val record = if (row.access == "member" && !row.deleted && engineStatus != null) memberRecord(row, engineStatus) else null
        // Feedprojektionen sind bereinigte JSON-Snapshots, niemals Engine-Köpfe oder Eventberechtigungen.
        return CloudPullMatch(
            row.id,
            row.serverVersion,
            CloudJson.encodeToString(PulledMatch.serializer(), row),
            row.access,
            row.accessEpoch,
            row.redactionEpoch.toLong(),
            row.deleted,
            record,
        )
    }

    private fun memberRecord(
        row: PulledMatch,
        status: MatchStatus,
    ): MatchRecord {
        val format = CloudJson.decodeFromJsonElement(MatchFormat.serializer(), row.format)
        require(MatchFormatValidator.validate(format).isValid)
        require(CloudNames.of(format.sport.name) == row.sport && CloudNames.of(format.discipline.name) == row.discipline)

        fun team(name: String): List<PlayerId> {
            val members = row.participants.filter { it.team == name }.sortedBy { it.position }
            require(members.map { it.position } == (1..format.discipline.playersPerSide).toList())
            return members.map { PlayerId(it.slotId) }
        }
        val a = team("A")
        val b = team("B")
        // Nur Kopf-/Listenbesetzung: die tatsächliche Aufschlaghistorie stammt ausschließlich aus MatchCreated.
        val lineup = Lineup(a, b, a.first())
        require(LineupValidator.validate(format, lineup).isEmpty())
        val started = row.startedAt?.let { Instant.parse(it).toEpochMilliseconds() } ?: 0
        row.scheduledAt?.let { Instant.parse(it) }
        val updated = row.endedAt?.let { Instant.parse(it).toEpochMilliseconds() } ?: started
        return MatchRecord(
            MatchId(row.id),
            format,
            lineup,
            CloudJson.decodeFromJsonElement(MatchMeta.serializer(), row.meta),
            started,
            updated,
            status,
            row.summary?.let { CloudJson.decodeFromJsonElement(ScoreSummary.serializer(), it) } ?: ScoreSummary.EMPTY,
            row.previousMatchId?.let(::MatchId),
        )
    }

    fun events(
        rows: List<PulledEvent>,
        matchId: String,
        cursor: Long,
        limit: Int,
    ): List<CloudPullReceipt> {
        require(rows.size <= limit)
        requireUnique(rows.map { it.id })
        var after = cursor
        return rows.map { row ->
            require(row.serverSeq > after && row.matchId == matchId)
            require(row.deviceId.isNotBlank() && row.deviceSeq > 0 && row.lamport >= 0)
            require(row.type.isNotBlank() && row.payload["type"]?.jsonPrimitive?.content == row.type)
            val stored = EventRows.toStored(row.toRow())
            val payload = stored.toScoreEvent().payload
            if (payload is MatchCreated) {
                require(MatchFormatValidator.validate(payload.config).isValid)
                require(LineupValidator.validate(payload.config, payload.lineup).isEmpty())
            }
            after = row.serverSeq
            CloudPullReceipt(stored, row.serverSeq)
        }
    }

    private fun requireUnique(ids: List<String>) {
        require(ids.all { it.isNotBlank() } && ids.distinct().size == ids.size)
    }
}
