package eu.advantly.cloud.sync

import eu.advantly.core.model.TelemetryPreference
import eu.advantly.data.CloudUploadSnapshot
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Instant

/** Auswahl und unveränderlicher Auth-Kontext eines begrenzten Versuchs; keine Credentials in toString. */
class CloudUploadAttempt internal constructor(
    internal val lease: CloudLease,
    internal val snapshot: CloudUploadSnapshot,
    internal val deviceBinding: CloudDeviceBinding,
) {
    val events: List<EventRow> get() = snapshot.events.map(EventRows::toRow)
    val batch: PushBatch get() = snapshot.toPushBatch()
    val inventory get() = snapshot.inventory
    val profileId: String get() = snapshot.profileId
    val generation: Long get() = lease.generation
}

internal class CloudDeviceBinding(
    val generation: Long,
    val sessionId: String,
)

/**
 * Ausführbare Upload-Grenze und versionierte Lese-RPCs, ohne lokale Pull-Anwendung.
 * Ohne ausdrücklich bestätigte Gerätebindung bleibt jeder Sync-Aufruf gesperrt.
 */
class SupabaseCloudBackend internal constructor(
    private val sessions: CloudSessionGate,
    private val http: CloudHttp,
    private val installation: CloudInstallation,
) : CloudBackend,
    CloudPullPort,
    CloudUploadPort,
    FriendsPort,
    CloudTelemetryPort,
    SharedMatchesPort,
    VisitorsPort,
    LiveMatchesPort,
    PushTokenPort,
    ScorerRolesPort,
    GuestLinksPort,
    InvitesPort {
    private var binding: CloudDeviceBinding? = null
    private var bindingAttempt = 0L

    /** Expliziter, später vom U4/K4-Host ausgelöster Aufruf. Kein Fallback auf register_device. */
    suspend fun bindDevice(deviceId: String) {
        require(deviceId.isNotBlank())
        binding = null
        check(bindingAttempt < Long.MAX_VALUE)
        val attempt = ++bindingAttempt
        val lease = sessions.capture()
        val result =
            sessions.request(lease) {
                CloudJson
                    .parseToJsonElement(
                        http.rpc(
                            "bind_cloud_session_v1",
                            buildJsonObject {
                                put("p_installation_id", installation.id)
                                put("p_device_id", deviceId)
                            },
                            lease.credentials.accessToken,
                            installation,
                        ),
                    ).jsonObject
            }

        fun matches(
            key: String,
            expected: String,
        ) = result[key]?.jsonPrimitive?.content == expected
        if (!matches("contractVersion", "1") || !matches("profileId", lease.credentials.profileId) ||
            !matches("sessionId", lease.credentials.sessionId) || !matches("installationId", installation.id) ||
            !matches("deviceId", deviceId)
        ) {
            throw CloudException("device_not_authorized", true)
        }
        sessions.requireCurrent(lease)
        if (attempt != bindingAttempt) throw CloudException("device_not_authorized", true)
        binding = CloudDeviceBinding(lease.generation, lease.credentials.sessionId)
    }

    private fun requireBinding(lease: CloudLease): CloudDeviceBinding {
        sessions.requireCurrent(lease)
        val current = binding
        if (current == null || current.generation != lease.generation || current.sessionId != lease.credentials.sessionId) {
            throw CloudException("device_not_authorized", true)
        }
        return current
    }

    private suspend fun rpc(
        lease: CloudLease,
        name: String,
        body: JsonObject,
    ): String {
        val current = requireBinding(lease)
        val response = sessions.request(lease) { http.rpc(name, body, lease.credentials.accessToken, installation) }
        if (binding !== current) throw CloudException("device_not_authorized", true)
        return response
    }

    override suspend fun push(batch: PushBatch): PushResponse = push(sessions.capture(), batch)

    private suspend fun push(
        lease: CloudLease,
        batch: PushBatch,
    ): PushResponse {
        require(batch.events.size <= PushBatch.MAX_EVENTS_PER_PUSH)
        require(batch.devices.size <= 500 && batch.players.size <= 500 && batch.matches.size <= 500)
        return CloudJson.decodeFromString(
            rpc(
                lease,
                "cloud_sync_push_v1",
                buildJsonObject {
                    put("p_batch", CloudJson.encodeToJsonElement(PushBatch.serializer(), batch))
                },
            ),
        )
    }

    override fun captureUpload(limit: Int): CloudUploadAttempt {
        require(limit in 1..PushBatch.MAX_EVENTS_PER_PUSH)
        val lease = sessions.capture()
        val current = requireBinding(lease)
        return CloudUploadAttempt(lease, sessions.captureUpload(lease, limit), current).also(::requireCurrentUpload)
    }

    override fun requireCurrentUpload(attempt: CloudUploadAttempt) {
        sessions.requireUpload(attempt.lease, attempt.snapshot)
        if (binding !== attempt.deviceBinding) throw CloudException("device_not_authorized", true)
    }

    override fun nextUpload(
        attempt: CloudUploadAttempt,
        limit: Int,
    ): CloudUploadAttempt {
        requireCurrentUpload(attempt)
        return captureUpload(limit)
    }

    override fun releaseUploadHolds() = sessions.releaseUploadHolds(sessions.capture())

    override fun uploadInventory(attempt: CloudUploadAttempt) = readUploadStatus(attempt).inventory

    override fun readUploadStatus(attempt: CloudUploadAttempt?): CloudUploadStatus {
        val lease = attempt?.lease ?: sessions.capture()
        val current = requireBinding(lease)
        if (attempt != null) requireCurrentUpload(attempt)
        val inventory = sessions.uploadInventory(lease)
        if (requireBinding(lease) !== current) throw CloudException("device_not_authorized", true)
        if (attempt != null) requireCurrentUpload(attempt)
        return CloudUploadStatus(lease.credentials.profileId, lease.generation, inventory)
    }

    /** Genau den eingefrorenen Stand senden; jüngere lokale Metadaten bleiben nach dem Ack ausstehend. */
    override suspend fun pushSelected(
        attempt: CloudUploadAttempt,
        batch: PushBatch,
    ): PushResponse {
        requireCurrentUpload(attempt)
        require(batch.events == attempt.events)
        val selected = attempt.batch
        require(batch.players == selected.players && batch.matches == selected.matches)
        require(batch.lifecycle == selected.lifecycle)
        val sent = selected.copy(devices = batch.devices.toList())
        val response =
            try {
                push(attempt.lease, sent)
            } catch (_: SerializationException) {
                throw CloudException("cloud_response_invalid", false)
            }
        val metadata =
            try {
                val ids = response.events.map { it.id }
                require(ids.size == ids.toSet().size && ids.all { id -> sent.events.any { it.id == id } })
                val deviceIds = response.devices.map { it.id }
                require(deviceIds.size == deviceIds.toSet().size && deviceIds.all { id -> sent.devices.any { it.id == id } })
                attempt.snapshot.acknowledgedMetadata(sent, response)
            } catch (_: IllegalArgumentException) {
                throw CloudException("cloud_response_invalid", false)
            }
        val update = OutboxPlanner.evaluate(sent, response)
        sessions.acknowledge(
            attempt.lease,
            attempt.snapshot,
            update.uploaded,
            update.rejected,
            metadata,
            linkNotes(metadata, response),
            update.held,
        )
        return response
    }

    override fun capturePull(): CloudPullAttempt {
        val lease = sessions.capture()
        return CloudPullAttempt(lease, requireBinding(lease)).also(::requireCurrentPull)
    }

    override fun requireCurrentPull(attempt: CloudPullAttempt) {
        sessions.requireCurrent(attempt.lease)
        if (requireBinding(attempt.lease) !== attempt.deviceBinding) throw CloudException("device_not_authorized", true)
    }

    private suspend fun pullRpc(
        attempt: CloudPullAttempt,
        name: String,
        body: JsonObject,
    ): String {
        requireCurrentPull(attempt)
        val response = rpc(attempt.lease, name, body)
        requireCurrentPull(attempt)
        return response
    }

    override suspend fun pullPage(
        attempt: CloudPullAttempt,
        cursor: Long,
        limit: Int,
    ): PullResponse {
        require(cursor >= 0 && limit in 1..200)
        return CloudJson.decodeFromString(
            pullRpc(
                attempt,
                "cloud_sync_pull_v1",
                buildJsonObject {
                    put("p_cursor", cursor)
                    put("p_limit", limit)
                },
            ),
        )
    }

    override suspend fun pullEventPage(
        attempt: CloudPullAttempt,
        matchId: String,
        afterServerSeq: Long,
        limit: Int,
    ): List<PulledEvent> {
        require(matchId.isNotBlank() && afterServerSeq >= 0 && limit in 1..500)
        return CloudJson.decodeFromString(
            pullRpc(
                attempt,
                "cloud_pull_match_events_v1",
                buildJsonObject {
                    put("p_match_id", matchId)
                    put("p_after_server_seq", afterServerSeq)
                    put("p_limit", limit)
                },
            ),
        )
    }

    override suspend fun pullWatermarks(
        attempt: CloudPullAttempt,
        matchId: String,
    ): List<EventWatermark> {
        require(matchId.isNotBlank())
        return CloudJson.decodeFromString(
            pullRpc(attempt, "cloud_match_event_watermarks_v1", buildJsonObject { put("p_match_id", matchId) }),
        )
    }

    override suspend fun pullRedactionPage(
        attempt: CloudPullAttempt,
        request: CloudRedactionRequest,
    ): CloudRedactionPage {
        val response =
            pullRpc(
                attempt,
                "cloud_pull_match_redaction_v1",
                buildJsonObject {
                    put("p_match_id", request.matchId)
                    put("p_generation", request.generation)
                    put("p_redaction_epoch", request.redactionEpoch)
                    request.afterPlayerId?.let { put("p_after_player_id", it) }
                    put("p_after_server_seq", request.afterServerSeq)
                    request.highWatermark?.let { put("p_high_watermark", it) }
                    put("p_player_limit", request.playerLimit)
                    put("p_event_limit", request.eventLimit)
                },
            )
        val page =
            try {
                CloudJson.decodeFromString<CloudRedactionPage>(response)
            } catch (_: SerializationException) {
                throw CloudException("cloud_response_invalid", false)
            }
        page.validate(request)
        requireCurrentPull(attempt)
        return page
    }

    override suspend fun pullReadiness(
        attempt: CloudPullAttempt,
        request: CloudReadinessRequest,
    ): CloudMatchReadiness {
        val response =
            pullRpc(
                attempt,
                "cloud_match_readiness_v1",
                buildJsonObject {
                    put("p_match_id", request.matchId)
                    put("p_generation", request.generation)
                    put("p_redaction_epoch", request.redactionEpoch)
                    request.highWatermark?.let { put("p_high_watermark", it) }
                },
            )
        val readiness =
            try {
                CloudJson.decodeFromString<CloudMatchReadiness>(response)
            } catch (_: SerializationException) {
                throw CloudException("cloud_response_invalid", false)
            }
        readiness.validate(request)
        requireCurrentPull(attempt)
        return readiness
    }

    override suspend fun pull(
        cursor: Long,
        limit: Int,
    ): PullResponse {
        require(cursor >= 0 && limit in 1..200)
        return CloudJson.decodeFromString(
            rpc(
                sessions.capture(),
                "cloud_sync_pull_v1",
                buildJsonObject {
                    put("p_cursor", cursor)
                    put("p_limit", limit)
                },
            ),
        )
    }

    override suspend fun pullEvents(
        matchId: String,
        afterServerSeq: Long,
        limit: Int,
    ): List<PulledEvent> {
        require(matchId.isNotBlank() && afterServerSeq >= 0 && limit in 1..500)
        return CloudJson.decodeFromString(
            rpc(
                sessions.capture(),
                "cloud_pull_match_events_v1",
                buildJsonObject {
                    put("p_match_id", matchId)
                    put("p_after_server_seq", afterServerSeq)
                    put("p_limit", limit)
                },
            ),
        )
    }

    override suspend fun watermarks(matchId: String): List<EventWatermark> {
        require(matchId.isNotBlank())
        return CloudJson.decodeFromString(
            rpc(
                sessions.capture(),
                "cloud_match_event_watermarks_v1",
                buildJsonObject {
                    put("p_match_id", matchId)
                },
            ),
        )
    }

    override suspend fun visitorView(matchId: String): VisitorView {
        require(matchId.isNotBlank())
        return CloudJson.decodeFromString(
            rpc(sessions.capture(), "cloud_get_visitor_match_v1", buildJsonObject { put("p_match_id", matchId) }),
        )
    }

    override suspend fun friendCode(): String =
        decode<String>(rpc(sessions.capture(), "my_friend_code", buildJsonObject {})).takeIf { it.isNotBlank() }
            ?: throw CloudException("cloud_response_invalid", false)

    override suspend fun ownProfile(): FoundProfile {
        val lease = sessions.capture()
        val id = lease.credentials.profileId
        return decode<List<FoundProfile>>(ownRow("profiles", lease) { "id=eq.$id&select=id,alias,username" })
            .singleOrNull()
            ?.takeIf { it.id == id } ?: throw CloudException("cloud_response_invalid", false)
    }

    override suspend fun findProfile(query: String): FoundProfile? {
        val trimmed = query.trim()
        require(trimmed.isNotEmpty() && trimmed.length <= 64)
        val found = decode<List<FoundProfile>>(rpc(sessions.capture(), "find_profile", buildJsonObject { put("p_query", trimmed) }))
        if (found.size > 1 || found.any { it.id.isBlank() }) throw CloudException("cloud_response_invalid", false)
        return found.firstOrNull()
    }

    override suspend fun sendFriendRequest(
        profileId: String,
        viaCode: Boolean,
    ): FriendRequestResult {
        require(profileId.isNotBlank())
        val result =
            decode<String>(
                rpc(
                    sessions.capture(),
                    "send_friend_request",
                    buildJsonObject {
                        put("p_target", profileId)
                        put("p_source", if (viaCode) "code" else "search")
                    },
                ),
            )
        return when (result) {
            "requested" -> FriendRequestResult.REQUESTED
            "accepted" -> FriendRequestResult.ACCEPTED
            "already_friends" -> FriendRequestResult.ALREADY_FRIENDS
            else -> throw CloudException("cloud_response_invalid", false)
        }
    }

    override suspend fun respondFriendRequest(
        requesterId: String,
        accept: Boolean,
    ) {
        require(requesterId.isNotBlank())
        rpc(
            sessions.capture(),
            "respond_friend_request",
            buildJsonObject {
                put("p_requester", requesterId)
                put("p_accept", accept)
            },
        )
    }

    override suspend fun removeFriend(profileId: String) = otherRpc("remove_friend", profileId)

    override suspend fun blockUser(profileId: String) = otherRpc("block_user", profileId)

    override suspend fun unblockUser(profileId: String) = otherRpc("unblock_user", profileId)

    override suspend fun blockedProfiles(): List<BlockedProfile> =
        decode<List<BlockedProfile>>(rpc(sessions.capture(), "cloud_my_blocks_v1", buildJsonObject {})).also { blocks ->
            if (blocks.any { it.profileId.isBlank() }) throw CloudException("cloud_response_invalid", false)
        }

    override suspend fun syncTelemetry(
        profileId: String,
        local: TelemetryPreference,
    ): TelemetryPreference? {
        val lease = sessions.capture()
        // Identitätssicherheit: die Wahl von Konto A geht nie an die Sitzung von Konto B.
        if (lease.credentials.profileId != profileId) throw CloudException("cloud_session_changed", true)
        // Der Server nimmt Zähler nur unter 2^53 an; darüber nur lesen statt dauerhaft abgewiesen zu werden.
        val sent = local.takeIf { it.counter < CloudTelemetrySetting.MAX_COUNTER } ?: TelemetryPreference()
        val body =
            buildJsonObject {
                put("p_visibility", sent.visibility.wire)
                put("p_counter", sent.counter)
                put("p_origin", sent.origin)
            }
        return decode<CloudTelemetrySetting>(rpc(lease, "cloud_set_telemetry_visibility_v1", body)).toPreference()
    }

    override suspend fun setMyParticipation(
        profileId: String,
        matchId: String,
        shareWithFriends: Boolean?,
        hidden: Boolean?,
    ) {
        require(matchId.isNotBlank() && (shareWithFriends != null || hidden != null))
        rpc(
            lease(profileId),
            "set_my_participation",
            buildJsonObject {
                put("p_match_id", matchId)
                put("p_share_with_friends", shareWithFriends)
                put("p_hidden", hidden)
            },
        )
    }

    override suspend fun rejectMatchParticipation(
        profileId: String,
        matchId: String,
    ) {
        require(matchId.isNotBlank())
        rpc(lease(profileId), "reject_match_participation", buildJsonObject { put("p_match_id", matchId) })
    }

    override suspend fun shareMatchesDefault(profileId: String): Boolean =
        shareDefault(ownRow("profile_settings", lease(profileId)) { "select=share_matches_default" })

    override suspend fun setShareMatchesDefault(
        profileId: String,
        share: Boolean,
    ) {
        val body = buildJsonObject { put("share_matches_default", share) }
        // Spaltenrecht statt RPC (20261015000990_rechte.sql); der Filter bindet das Update an das geprüfte eigene Profil.
        val stored =
            shareDefault(ownRow("profile_settings", lease(profileId), body) { "profile_id=eq.$profileId&select=share_matches_default" })
        if (stored != share) throw CloudException("cloud_response_invalid", false)
    }

    override suspend fun assignGuest(
        profileId: String,
        playerId: String,
        friendId: String,
    ): Int {
        require(playerId.isNotBlank() && friendId.isNotBlank())
        val body =
            buildJsonObject {
                put("p_player_id", playerId)
                put("p_profile_id", friendId)
            }
        return decode<Int>(rpc(lease(profileId), "assign_player", body)).also {
            if (it < 0) throw CloudException("cloud_response_invalid", false)
        }
    }

    override suspend fun unassignGuest(
        profileId: String,
        playerId: String,
    ) {
        require(playerId.isNotBlank())
        rpc(lease(profileId), "unassign_player", buildJsonObject { put("p_player_id", playerId) })
    }

    override suspend fun rejectGuestAssignment(
        profileId: String,
        playerId: String,
    ) {
        require(playerId.isNotBlank())
        rpc(lease(profileId), "reject_player_assignment", buildJsonObject { put("p_player_id", playerId) })
    }

    override suspend fun createInvite(
        profileId: String,
        kind: InviteKind,
        playerId: String?,
        ttlMinutes: Int,
    ): String {
        require(ttlMinutes in 1..MAX_INVITE_TTL_MINUTES && (kind == InviteKind.CLAIM_PLAYER) == (playerId?.isNotBlank() == true))
        val body =
            buildJsonObject {
                put("p_kind", kind.wire)
                put("p_player_id", playerId)
                put("p_ttl_minutes", ttlMinutes)
            }
        return decode<String>(rpc(lease(profileId), "create_invite", body)).takeIf(INVITE_TOKEN::matches)
            ?: throw CloudException("cloud_response_invalid", false)
    }

    override suspend fun previewInvite(
        profileId: String,
        token: String,
    ): InvitePreview {
        require(INVITE_TOKEN.matches(token))
        val json = decode<JsonObject>(rpc(lease(profileId), "preview_invite", buildJsonObject { put("p_token", token) }))
        return strict {
            val status = InviteStatus.entries.first { it.wire == json.text("status") }
            if (status == InviteStatus.INVALID) return@strict InvitePreview(status)
            // Art und Einladender sind nur für `ok`/`already_friends` Pflicht; ein endgültiger Status zählt auch ohne sie.
            val usable = status == InviteStatus.OK || status == InviteStatus.ALREADY_FRIENDS
            val kind = json.text("kind")?.let { requireNotNull(InviteKind.of(it)) }
            require(kind != null || !usable)
            val claimable = status == InviteStatus.OK && kind == InviteKind.CLAIM_PLAYER
            InvitePreview(
                status = status,
                kind = kind,
                inviterId = json.text("inviterId")?.also { require(it.isNotBlank()) },
                inviterAlias = json.text("inviterAlias"),
                guestName = if (claimable) requireNotNull(json.text("guestName")?.takeIf { it.isNotBlank() }) else null,
                matchCount = if (claimable) requireNotNull(json.int("matchCount")).also { require(it >= 0) } else null,
                expiresAtMillis = json.text("expiresAt")?.let { Instant.parse(it).toEpochMilliseconds() },
            ).also { require(it.inviterId != null || !usable) }
        }
    }

    override suspend fun redeemInvite(
        profileId: String,
        token: String,
    ): InviteRedemption {
        require(INVITE_TOKEN.matches(token))
        val json = decode<JsonObject>(rpc(lease(profileId), "redeem_invite", buildJsonObject { put("p_token", token) }))
        return strict {
            val kind = requireNotNull(InviteKind.of(json.text("kind")))
            val friendId = requireNotNull(json.text("friendId")?.takeIf { it.isNotBlank() })
            if (json["alreadyFriends"]?.let { (it as JsonPrimitive).booleanOrNull } == true) {
                require(kind == InviteKind.FRIEND)
                return@strict InviteRedemption(kind, friendId, alreadyFriends = true)
            }
            val playerId = json.text("playerId")
            require((kind == InviteKind.CLAIM_PLAYER) == (playerId?.isNotBlank() == true))
            InviteRedemption(kind, friendId, playerId, requireNotNull(json.int("matchCount")).also { require(it >= 0) })
        }
    }

    override suspend fun revokeInvite(
        profileId: String,
        inviteId: String,
    ) {
        require(inviteId.isNotBlank())
        rpc(lease(profileId), "revoke_invite", buildJsonObject { put("p_invite_id", inviteId) })
    }

    override suspend fun openInvites(profileId: String): List<OpenInvite> {
        val rows =
            decode<List<JsonObject>>(
                ownRow("invites", lease(profileId)) {
                    "created_by=eq.$profileId&used_at=is.null&revoked_at=is.null&select=id,kind,player_id,expires_at&order=expires_at.desc"
                },
            )
        return strict {
            rows.map { row ->
                val kind = requireNotNull(InviteKind.of(row.text("kind")))
                val playerId = row.text("player_id")
                require((kind == InviteKind.CLAIM_PLAYER) == (playerId?.isNotBlank() == true))
                OpenInvite(
                    requireNotNull(row.text("id")?.takeIf { it.isNotBlank() }),
                    kind,
                    playerId,
                    Instant.parse(requireNotNull(row.text("expires_at"))).toEpochMilliseconds(),
                )
            }
        }
    }

    override suspend fun addVisitor(
        profileId: String,
        matchId: String,
        visitorId: String,
    ) = visitorRpc("add_match_visitor", profileId, matchId, visitorId)

    override suspend fun revokeVisitor(
        profileId: String,
        matchId: String,
        visitorId: String,
    ) = visitorRpc("revoke_match_visitor", profileId, matchId, visitorId)

    override suspend fun matchVisitors(
        profileId: String,
        matchId: String,
    ): List<MatchVisitor> {
        require(matchId.isNotBlank())
        return decode<List<MatchVisitor>>(rpc(lease(profileId), "cloud_match_visitors_v1", buildJsonObject { put("p_match_id", matchId) }))
            .also { visitors -> if (visitors.any { it.profileId.isBlank() }) throw CloudException("cloud_response_invalid", false) }
    }

    override suspend fun myVisitorMatches(profileId: String): List<VisitorView> =
        decode<List<VisitorView>>(rpc(lease(profileId), "my_visitor_matches", buildJsonObject {}))

    override suspend fun liveMatches(profileId: String): List<LiveMatch> =
        decode<List<LiveMatch>>(rpc(lease(profileId), "cloud_my_live_matches_v1", buildJsonObject {}))
            .also { live -> if (live.any { it.matchId.isBlank() }) throw CloudException("cloud_response_invalid", false) }

    override suspend fun setPushToken(
        profileId: String,
        provider: String,
        token: String?,
    ) {
        require(provider.isNotBlank() && token?.isBlank() != true)
        rpc(
            lease(profileId),
            "cloud_set_push_token_v1",
            buildJsonObject {
                put("p_provider", provider)
                put("p_token", token)
            },
        )
    }

    override suspend fun inviteScorer(
        profileId: String,
        matchId: String,
        scorerId: String,
    ) = visitorRpc("invite_scorer", profileId, matchId, scorerId)

    override suspend fun revokeScorer(
        profileId: String,
        matchId: String,
        scorerId: String,
    ) = visitorRpc("revoke_scorer", profileId, matchId, scorerId)

    override suspend fun respondScorerInvitation(
        profileId: String,
        matchId: String,
        accept: Boolean,
        deviceId: String?,
    ) {
        require(matchId.isNotBlank() && (deviceId == null || accept))
        rpc(
            lease(profileId),
            "respond_scorer_invitation",
            buildJsonObject {
                put("p_match_id", matchId)
                put("p_accept", accept)
                put("p_device_id", deviceId)
            },
        )
    }

    override suspend fun registerWatch(
        profileId: String,
        watchId: String,
        phoneId: String,
    ) {
        require(watchId.isNotBlank() && phoneId.isNotBlank() && watchId != phoneId)
        rpc(
            lease(profileId),
            "register_device",
            buildJsonObject {
                put("p_id", watchId)
                put("p_kind", "watch")
                put("p_platform", "wearos")
                put("p_paired_with", phoneId)
            },
        )
    }

    /** `p_match_id` + `p_profile_id`: Zuschauer- und Zähler-RPCs. */
    private suspend fun visitorRpc(
        name: String,
        profileId: String,
        matchId: String,
        visitorId: String,
    ) {
        require(matchId.isNotBlank() && visitorId.isNotBlank())
        rpc(
            lease(profileId),
            name,
            buildJsonObject {
                put("p_match_id", matchId)
                put("p_profile_id", visitorId)
            },
        )
    }

    /** Lease nur für das erwartete Konto: ein inzwischen gewechseltes SDK-Konto sendet nichts. */
    private fun lease(profileId: String): CloudLease {
        require(profileId.isNotBlank())
        return sessions.capture().also { if (it.credentials.profileId != profileId) throw CloudException("cloud_session_changed", true) }
    }

    @Serializable
    private class ShareDefaultRow(
        @SerialName("share_matches_default") val share: Boolean,
    )

    /** Genau die eigene Zeile; fehlt sie (Profil ohne Einstellungen), ist die Antwort ungültig. */
    private fun shareDefault(response: String): Boolean =
        decode<List<ShareDefaultRow>>(response).singleOrNull()?.share ?: throw CloudException("cloud_response_invalid", false)

    /** Eigene Tabellenzeile (Lesen oder mit [body] spaltenweises Update) unter derselben Gerätebindung wie [rpc]. */
    private suspend fun ownRow(
        table: String,
        lease: CloudLease,
        body: JsonObject? = null,
        query: () -> String,
    ): String {
        val current = requireBinding(lease)
        val token = lease.credentials.accessToken
        val response =
            sessions.request(lease) {
                http.ownRow(table, query(), body, token, installation)
            }
        if (binding !== current) throw CloudException("device_not_authorized", true)
        return response
    }

    private suspend fun otherRpc(
        name: String,
        profileId: String,
    ) {
        require(profileId.isNotBlank())
        rpc(sessions.capture(), name, buildJsonObject { put("p_other", profileId) })
    }

    /** Feld als Text (`null` bei fehlend oder JSON-null); andere Typen sind eine ungültige Antwort. */
    private fun JsonObject.text(key: String): String? =
        when (val value: JsonElement? = this[key]) {
            null, JsonNull -> null
            is JsonPrimitive -> value.content.also { require(value.isString) }
            else -> throw IllegalArgumentException()
        }

    private fun JsonObject.int(key: String): Int? =
        when (val value = this[key]) {
            null, JsonNull -> null
            is JsonPrimitive -> requireNotNull(value.intOrNull.takeUnless { value.isString })
            else -> throw IllegalArgumentException()
        }

    /** Strenge Prüfung einer Antwort: jede Abweichung ist `cloud_response_invalid`, nie ein Rohtext an die UI. */
    private inline fun <T> strict(block: () -> T): T =
        try {
            block()
        } catch (_: IllegalArgumentException) {
            throw CloudException("cloud_response_invalid", false)
        } catch (_: NoSuchElementException) {
            throw CloudException("cloud_response_invalid", false)
        } catch (_: IllegalStateException) {
            throw CloudException("cloud_response_invalid", false)
        }

    private inline fun <reified T> decode(response: String): T =
        try {
            CloudJson.decodeFromString<T>(response)
        } catch (_: IllegalArgumentException) {
            // SerializationException ist ein IllegalArgumentException: Rohantworten nie an die UI weitergeben.
            throw CloudException("cloud_response_invalid", false)
        }
}
