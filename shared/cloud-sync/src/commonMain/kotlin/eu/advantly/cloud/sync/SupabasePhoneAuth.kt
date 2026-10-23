package eu.advantly.cloud.sync

import eu.advantly.data.CloudUploadStore
import eu.advantly.data.LocalAccountOwnership
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.SignOutScope
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64

/**
 * supabase-kt-Adapter. Der Host injiziert Client samt sicherem SessionManager und Installationsgeheimnis.
 * [hostStorageAction] umfasst die synchrone SDK-Collectorsynchronisation; `false` beendet diesen Durchlauf.
 * Der Default fuehrt direkt aus, ohne Fehler zu behandeln oder die Nicht-Android-Semantik zu aendern.
 */
class SupabasePhoneAuth(
    private val client: SupabaseClient,
    private val deviceSecret: String,
    uploads: CloudUploadStore? = null,
    private val scope: CoroutineScope,
    private val threadGuard: () -> Boolean = { true },
    private val requestTimeoutMillis: Long = 20_000,
    ownership: LocalAccountOwnership? = null,
    private val hostAccessAllowed: () -> Boolean = { true },
    private val hostStorageAction: (() -> Unit) -> Boolean = {
        it()
        true
    },
) : CloudAuthPort {
    private val transport =
        CloudHttp(
            client.supabaseHttpUrl,
            client.supabaseKey,
            client.config.networkConfig.httpEngine
                ?.let { HttpClient(it) { followRedirects = false } }
                ?: HttpClient { followRedirects = false },
            requestTimeoutMillis,
            exportFactory = exportHttpTransportFactory(client.config.networkConfig.httpEngine),
        )
    val sessions = CloudSessionGate(::credentials, uploads, threadGuard, ownership, hostAccessAllowed)
    override val session = sessions.session
    private val mutations = Mutex()
    private val lifecycle = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private var closed = false
    private var accountEpoch = 0L
    private var accountIdentity: Pair<String, String>? = null
    private val verifiedAccount = MutableStateFlow<CloudSession?>(null)
    override val accountSession = verifiedAccount.asStateFlow()
    private val exports =
        SupabaseDataExport(
            transport,
            capture = { expected ->
                requireExportAccount(expected)
                sessions.capture(requireProfile = false).credentials.also { requireExportAccount(expected) }
            },
            checkAccount = ::requireExportAccount,
        )

    /** Privater ZIP-Transport; keine zusaetzliche Cloud-Aktivierung oder Profilabfrage. */
    fun dataExports(): CloudDataExportPort = exports

    private fun requireExportAccount(expected: CloudDataExportContext) {
        check(threadGuard()) { "Export nur auf dem seriellen Host-Kontext verwenden" }
        requireHostAccess()
        requireAccountSession(CloudSession(expected.profileId, false, false, generation = expected.generation))
    }

    private var pendingAccountMutations = 0
    private var mutationRevision = 0L
    private var unconfirmedDecline = false

    private enum class DeletionIntent { REQUEST, RECOVERY }

    private data class PendingDeletion(
        val intent: DeletionIntent,
        val revision: Long,
    )

    private var pendingDeletion: PendingDeletion? = null

    private class RejectedAccount(
        val credentials: CloudCredentials,
        val epoch: Long,
        val rejection: CloudException,
    ) : Exception()

    init {
        lifecycle.launch(start = CoroutineStart.UNDISPATCHED) {
            client.auth.sessionStatus.collect {
                if (!hostAccessAllowed()) return@collect
                val completed =
                    hostStorageAction {
                        try {
                            if (hostAccessAllowed()) {
                                sessions.synchronize()
                                if (hostAccessAllowed()) synchronizeAccount()
                            }
                        } catch (error: CloudException) {
                            // Widerruf zwischen Vorprüfung und Gate-Operation bricht nur diesen Durchlauf ab.
                            if (error.reason != "cloud_session_changed" || !error.permission || hostAccessAllowed()) throw error
                        }
                    }
                if (!completed || !hostAccessAllowed()) return@collect
            }
        }
    }

    private fun credentials(): CloudCredentials? {
        val raw = client.auth.currentSessionOrNull() ?: return null
        return try {
            val claims =
                Json.parseToJsonElement(
                    Base64.UrlSafe
                        .withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
                        .decode(raw.accessToken.split('.')[1])
                        .decodeToString(),
                ) as JsonObject
            val profile = claims.getValue("sub").jsonPrimitive.content
            val sessionId = claims.getValue("session_id").jsonPrimitive.content
            if (profile.isBlank() || sessionId.isBlank() || (raw.user != null && raw.user?.id != profile)) return null
            CloudCredentials(profile, sessionId, raw.accessToken)
        } catch (_: Exception) {
            null
        }
    }

    /** Installation dieses Handys; erkennt in der Geräteliste das eigene Gerät (K4) und weist Freigaben nach (K4-U). */
    private var installation: CloudInstallation? = null

    /** Keine automatische Registrierung/Übertragung; der versionierte Serververtrag ist noch ein Release-Gate. */
    fun cloudBackend(installation: CloudInstallation): SupabaseCloudBackend {
        this.installation = installation
        return SupabaseCloudBackend(sessions, transport, installation)
    }

    /** Vor einem späteren Lösch-RPC aufrufen; Netzwerkfehler dürfen die lokale Sperre nicht aufheben. */
    fun stopForDeletion() {
        exports.invalidate(verifiedAccount.value)
        pendingDeletion = PendingDeletion(DeletionIntent.REQUEST, reserveMutation())
        sessions.suspendCloud(leaveIdentity = true)
    }

    private fun synchronizeAccount() {
        requireHostAccess()
        val identity = credentials()?.let { it.profileId to it.sessionId }
        if (identity != accountIdentity) {
            accountIdentity = identity
            invalidateAccount()
        }
    }

    private fun invalidateAccount() {
        exports.invalidate()
        accountEpoch++
        verifiedAccount.value = null
        unconfirmedDecline = false
        pendingDeletion = null
    }

    override fun requireAccountSession(expected: CloudSession) {
        synchronizeAccount()
        if (closed || verifiedAccount.value?.profileId != expected.profileId || expected.generation != accountEpoch) {
            throw CloudException("cloud_session_changed", true)
        }
    }

    override fun adoptionSources(): List<CloudAdoptionSource> = sessions.adoptionSources()

    override fun adoptSources(ids: Set<String>) = sessions.adoptSources(ids)

    override fun close() {
        if (closed) return
        closed = true
        var failure: Exception? = null

        fun cleanup(action: () -> Unit) {
            try {
                action()
            } catch (error: Exception) {
                val primary = failure
                if (primary == null) {
                    failure = error
                } else if (primary !== error) {
                    primary.addSuppressed(error)
                }
            }
        }
        cleanup(::invalidateAccount)
        cleanup { sessions.stop(leaveIdentity = false) }
        cleanup { lifecycle.cancel() }
        cleanup { client.auth.stopAutoRefreshForCurrentSession() }
        cleanup { transport.close() }
        // Der Hostjob kann bereits widerrufen sein. Clientabbau bleibt davon unabhängig und greift nicht auf SQL zu.
        val closing = CoroutineScope(scope.coroutineContext.minusKey(Job) + SupervisorJob())
        val primary = failure
        cleanup {
            closing.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    withContext(NonCancellable) { client.close() }
                } catch (error: Exception) {
                    if (primary != null && primary !== error) primary.addSuppressed(error) else throw error
                } finally {
                    closing.cancel()
                }
            }
        }
        failure?.let { throw it }
    }

    private fun requireHostAccess() {
        if (!hostAccessAllowed()) throw CloudException("cloud_session_changed", true)
    }

    override suspend fun issueAgeTicket(
        country: String,
        birthDate: String,
    ): String =
        guarded {
            rpc(
                "issue_age_ticket",
                buildJsonObject {
                    put("p_country", country)
                    put("p_birth_date", birthDate)
                    put("p_device_secret", deviceSecret)
                },
            ).getValue("ticket").jsonPrimitive.content
        }

    override suspend fun cancelAgeTicket(ticket: String) =
        guarded {
            rpc("cancel_age_ticket", buildJsonObject { put("p_ticket", ticket) })
            Unit
        }

    override suspend fun signInWithIdToken(
        provider: IdTokenProvider,
        idToken: String,
        rawNonce: String?,
        ticket: String,
    ) = newLogin {
        if (provider != IdTokenProvider.GOOGLE) throw CloudException("provider_unavailable", false)
        // Nur Routing, kein Vertrauensbeweis: Supabase verifiziert Signatur, aud, exp und Nonce des ganzen Tokens.
        val subject =
            Json.parseToJsonElement(
                Base64.UrlSafe
                    .withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
                    .decode(idToken.split('.')[1])
                    .decodeToString(),
            ) as JsonObject
        bind(ticket, "google", subject.getValue("sub").jsonPrimitive.content)
        client.auth.signInWith(IDToken) {
            this.provider = Google
            this.idToken = idToken
            nonce = rawNonce
        }
    }

    override suspend fun linkGoogle(
        idToken: String,
        rawNonce: String?,
    ) = guarded {
        val lease = sessions.capture(requireProfile = false)
        mutations.withLock {
            sessions.request(lease) { boundedAuth { client.auth.linkIdentityWithIdToken(Google, idToken) { nonce = rawNonce } } }
            refreshProfile()
        }
    }

    override suspend fun sendEmailCode(
        email: String,
        ticket: String,
    ) {
        invalidateAccount()
        val transition = sessions.stop()
        guarded {
            mutations.withLock {
                sessions.requireTransition(transition)
                bind(ticket, "email", email)
                boundedAuth {
                    client.auth.signInWith(OTP) {
                        this.email = email.trim()
                        data = buildJsonObject { put("age_ticket", ticket) }
                    }
                }
            }
        }
    }

    override suspend fun verifyEmailCode(
        email: String,
        code: String,
    ) = newLogin {
        client.auth.verifyEmailOtp(OtpType.Email.EMAIL, email.trim(), code.trim())
    }

    override suspend fun completeOnboarding(
        alias: String,
        username: String?,
    ) = guarded {
        val lease = sessions.capture(requireProfile = false)
        sessions.request(lease) {
            transport.rpc(
                "finish_onboarding",
                buildJsonObject {
                    put("p_alias", alias)
                    put("p_username", username)
                },
                lease.credentials.accessToken,
            )
        }
        refreshProfile()
    }

    override suspend fun refreshProfile() = refreshProfileAfterMutation()

    private suspend fun refreshProfileAfterMutation(expectedRevision: Long? = null) =
        guarded {
            client.auth.awaitInitialization()
            sessions.synchronize()
            synchronizeAccount()
            if (expectedRevision != null) requireMutationCurrent(expectedRevision)
            if (client.auth.currentSessionOrNull() == null) {
                sessions.verifiedAbsent()
                return@guarded
            }
            val epoch = accountEpoch
            val revision = mutationRevision
            val deletion = pendingDeletion
            // Eine erst nach RPC-Ende eintreffende Antwort macht einen während Pending gestarteten Beweis nicht nachträglich gültig.
            val mutationsFinishedAtStart = pendingAccountMutations == 0
            // Nur ein nach beendetem Rücknahmeversuch gestarteter Profilabruf kann dessen Verlust quittieren.
            // Eine spätere Consent-Annahme entwertet alte Antworten, aber nicht die noch offene Recovery-Absicht.
            val recoveryProof =
                mutationsFinishedAtStart && !unconfirmedDecline && deletion?.intent == DeletionIntent.RECOVERY
            val lease =
                sessions.captureProfile(
                    canResumeCloud = mutationsFinishedAtStart && !unconfirmedDecline && (pendingDeletion == null || recoveryProof),
                )
            val profile =
                sessions.request(lease) {
                    Json.parseToJsonElement(
                        accountRequest("my_auth_profile", buildJsonObject {}, lease.credentials, epoch),
                    ) as JsonObject
                }
            val verified =
                CloudSession(
                    profile.getValue("profileId").jsonPrimitive.content,
                    profile.getValue("onboarded").jsonPrimitive.boolean,
                    profile.getValue("deletionRequested").jsonPrimitive.boolean,
                    consentStatus =
                        profile["consentStatus"]
                            ?.jsonPrimitive
                            ?.content
                            ?.let { runCatching { ConsentStatus.valueOf(it) }.getOrNull() }
                            ?: ConsentStatus.UNAVAILABLE,
                    suspended = profile["suspended"]?.jsonPrimitive?.boolean == true,
                )
            requireAccountCurrent(lease.credentials, epoch)
            requireMutationCurrent(revision)
            sessions.publish(lease, verified)
            verifiedAccount.value = verified.copy(generation = epoch)
            exports.publish(verified.copy(generation = epoch))
            if (mutationsFinishedAtStart && revision == mutationRevision && pendingAccountMutations == 0 && pendingDeletion == deletion &&
                (
                    (verified.deletionRequested && pendingDeletion == PendingDeletion(DeletionIntent.REQUEST, revision)) ||
                        (!verified.deletionRequested && recoveryProof)
                )
            ) {
                pendingDeletion = null
            }
            if (mutationsFinishedAtStart && revision == mutationRevision && verified.consentStatus == ConsentStatus.DECLINED) {
                unconfirmedDecline = false
            }
        }

    private suspend fun newLogin(block: suspend () -> Unit) {
        invalidateAccount()
        val transition = sessions.stop()
        guarded {
            mutations.withLock {
                sessions.requireTransition(transition)
                boundedAuth { block() }
                sessions.completeTransition(transition)
                refreshProfile()
            }
        }
    }

    override suspend fun signOut() {
        invalidateAccount()
        val transition = sessions.stop()
        try {
            boundedAuth {
                mutations.withLock {
                    sessions.requireTransition(transition)
                    guarded { client.auth.signOut(SignOutScope.GLOBAL) }
                }
            }
        } finally {
            // Auch ein abgebrochener Logout wartet auf ältere Auth-Mutationen und löscht deren Credentials.
            withContext(NonCancellable) {
                mutations.withLock {
                    if (sessions.isTransitionCurrent(transition)) client.auth.clearSession()
                }
            }
        }
    }

    override suspend fun consent(language: String): ConsentSnapshot =
        guarded {
            val result = accountRpc("my_consent_v1", buildJsonObject { put("p_language", language) })
            decodeConsent(result)
        }

    override suspend fun decideConsent(
        snapshot: ConsentSnapshot,
        accept: Boolean,
    ): ConsentSnapshot =
        guarded {
            synchronizeAccount()
            if (credentials()?.profileId != snapshot.profileId) throw CloudException("cloud_session_changed", true)
            val expected = credentials() ?: throw CloudException("cloud_session_changed", true)
            val epoch = accountEpoch
            val revision = reserveMutation()
            if (!accept) unconfirmedDecline = true
            val result =
                cloudMutation {
                    accountRpc(
                        "record_consent_v1",
                        buildJsonObject {
                            put("p_accept", accept)
                            put(
                                "p_documents",
                                buildJsonArray {
                                    snapshot.documents.forEach { document ->
                                        add(
                                            buildJsonObject {
                                                put("type", document.type.name)
                                                put("version", document.version)
                                                put("language", document.language)
                                            },
                                        )
                                    }
                                },
                            )
                        },
                    )
                }
            requireAccountCurrent(expected, epoch)
            requireMutationCurrent(revision)
            val decoded = decodeConsent(result)
            unconfirmedDecline = false
            refreshProfileAfterMutation(revision)
            requireAccountCurrent(expected, epoch)
            decoded
        }

    override suspend fun exportData(): String = guarded { accountRpc("export_my_data", buildJsonObject {}) }

    override suspend fun requestDeletion() =
        guarded {
            exports.invalidate(verifiedAccount.value)
            synchronizeAccount()
            val expected = credentials() ?: throw CloudException("cloud_session_changed", true)
            val epoch = accountEpoch
            val revision = reserveMutation()
            pendingDeletion = PendingDeletion(DeletionIntent.REQUEST, revision)
            cloudMutation(leaveIdentity = true) { accountRpc("request_account_deletion", buildJsonObject {}) }
            requireAccountCurrent(expected, epoch)
            requireMutationCurrent(revision)
            pendingDeletion = null
            refreshProfileAfterMutation(revision)
            requireAccountCurrent(expected, epoch)
        }

    override suspend fun cancelDeletion() =
        guarded {
            synchronizeAccount()
            val expected = credentials() ?: throw CloudException("cloud_session_changed", true)
            val epoch = accountEpoch
            val revision = reserveMutation()
            pendingDeletion = PendingDeletion(DeletionIntent.RECOVERY, revision)
            cloudMutation { accountRpc("cancel_account_deletion", buildJsonObject {}) }
            requireAccountCurrent(expected, epoch)
            requireMutationCurrent(revision)
            pendingDeletion = null
            refreshProfileAfterMutation(revision)
            requireAccountCurrent(expected, epoch)
        }

    override suspend fun devices(): List<CloudDevice> =
        guarded {
            val own = installation?.id
            CloudJson
                .decodeFromString<List<CloudDevice>>(accountRpc("cloud_list_devices_v1", buildJsonObject {}))
                .map { it.copy(current = own != null && it.installationId == own) }
        }

    override suspend fun revokeDevice(deviceId: String) =
        guarded {
            accountRpc("cloud_revoke_device_v1", buildJsonObject { put("p_device_id", deviceId) })
            Unit
        }

    // Ein eigener Grund, wenn dieses Handy selbst (noch) nicht gebunden oder widerrufen ist: Die Kontositzung gilt weiter.
    override suspend fun restoreDevice(deviceId: String) =
        guarded {
            val own = installation ?: throw CloudException(RESTORE_UNAVAILABLE, true)
            try {
                accountRpc("cloud_unrevoke_device_v1", buildJsonObject { put("p_device_id", deviceId) }, own)
            } catch (e: CloudException) {
                if (e.reason in setOf("device_revoked", "device_not_authorized")) throw CloudException(RESTORE_UNAVAILABLE, true)
                throw e
            }
            Unit
        }

    /** Vor jedem Intent reservieren; eine ältere Antwort darf keine neuere Sperrabsicht quittieren. */
    private fun reserveMutation(): Long {
        check(mutationRevision < Long.MAX_VALUE)
        mutationRevision++
        return mutationRevision
    }

    private fun requireMutationCurrent(revision: Long) {
        if (revision != mutationRevision) throw CloudException("cloud_session_changed", true)
    }

    /** Kontorechte bleiben auch bei lokal gesperrtem Cloud-Gate erreichbar, stets mit festem Bearer. */
    private suspend fun accountRpc(
        name: String,
        params: JsonObject,
        installation: CloudInstallation? = null,
    ): String {
        synchronizeAccount()
        val before = sessions.capture(requireProfile = false).credentials
        val epoch = accountEpoch
        val result = accountRequest(name, params, before, epoch, installation)
        requireAccountCurrent(before, epoch)
        return result
    }

    private suspend fun <T> cloudMutation(
        leaveIdentity: Boolean = false,
        block: suspend () -> T,
    ): T {
        pendingAccountMutations++
        try {
            sessions.suspendCloud(leaveIdentity)
            return block()
        } finally {
            pendingAccountMutations--
        }
    }

    private suspend fun accountRequest(
        name: String,
        params: JsonObject,
        credentials: CloudCredentials,
        epoch: Long,
        installation: CloudInstallation? = null,
    ): String =
        try {
            requireHostAccess()
            transport.rpc(name, params, credentials.accessToken, installation).also { requireHostAccess() }
        } catch (error: CloudException) {
            if (error.permission && error.reason in setOf("cloud_session_changed", "profile_missing")) {
                throw RejectedAccount(credentials, epoch, error)
            }
            throw error
        }

    private fun requireAccountCurrent(
        before: CloudCredentials,
        epoch: Long,
    ) {
        requireHostAccess()
        synchronizeAccount()
        val after = credentials()
        if (closed || epoch != accountEpoch || before.profileId != after?.profileId || before.sessionId != after.sessionId) {
            throw CloudException("cloud_session_changed", true)
        }
    }

    private fun decodeConsent(result: String): ConsentSnapshot {
        val snapshot = CloudJson.decodeFromString<ConsentSnapshot>(result)
        if (snapshot.profileId != credentials()?.profileId) throw CloudException("cloud_session_changed", true)
        return snapshot
    }

    private suspend fun bind(
        ticket: String,
        provider: String,
        identity: String,
    ) {
        rpc(
            "bind_age_ticket",
            buildJsonObject {
                put("p_ticket", ticket)
                put("p_provider", provider)
                put("p_identity", identity)
            },
        )
    }

    private suspend fun boundedAuth(block: suspend () -> Unit) {
        requireHostAccess()
        withTimeoutOrNull(requestTimeoutMillis) {
            block()
            requireHostAccess()
            true
        } ?: throw CloudException("connection_failed", false)
    }

    private suspend fun rpc(
        name: String,
        params: JsonObject,
    ): JsonObject {
        requireHostAccess()
        val result = Json.parseToJsonElement(transport.rpc(name, params, null)) as JsonObject
        requireHostAccess()
        result["error"]?.let { throw CloudException(it.jsonPrimitive.content, false) }
        return result
    }

    private suspend fun <T> guarded(block: suspend () -> T): T =
        try {
            if (closed) throw CloudException("cloud_closed", false)
            requireHostAccess()
            block().also { requireHostAccess() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RejectedAccount) {
            requireHostAccess()
            val current = credentials()
            if (accountEpoch == e.epoch && current?.profileId == e.credentials.profileId &&
                current.sessionId == e.credentials.sessionId
            ) {
                invalidateAccount()
                sessions.suspendCloud()
            }
            throw e.rejection
        } catch (e: CloudException) {
            if (e.reason == "cloud_connection_failed") throw CloudException("connection_failed", e.permission)
            throw e
        } catch (e: Exception) {
            // Keine Rohantwort/Token an UI oder Logs weitergeben. Unbekannte Fehler bleiben generisch.
            val reason = REASONS.firstOrNull { e.message?.contains(it) == true } ?: "connection_failed"
            throw CloudException(reason, false)
        }

    private companion object {
        const val RESTORE_UNAVAILABLE = "device_restore_unavailable"

        val REASONS =
            listOf(
                "profile_missing",
                "age_ticket_required",
                "age_ticket_expired",
                "age_ticket_bound",
                "age_retry_later",
                "rate_limit_exceeded",
                "country_not_supported",
                "age_below_minimum",
                "invalid_birth_date",
                "username_taken",
                "username_reserved",
                "invalid_username",
                "invalid_alias",
                "otp_expired",
                "already_onboarded",
            )
    }
}
