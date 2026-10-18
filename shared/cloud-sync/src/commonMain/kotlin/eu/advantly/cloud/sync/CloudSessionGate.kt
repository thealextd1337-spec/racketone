package eu.advantly.cloud.sync

import eu.advantly.data.CloudUploadSession
import eu.advantly.data.CloudUploadSnapshot
import eu.advantly.data.CloudUploadStore
import eu.advantly.data.LocalAccountOwnership
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job

/** Unveränderliche Credentials eines Versuchs; absichtlich kein data class/toString mit Geheimnissen. */
internal class CloudCredentials(
    val profileId: String,
    val sessionId: String,
    val accessToken: String,
)

internal class CloudLease(
    val generation: Long,
    val credentials: CloudCredentials,
    val profileRevision: Long? = null,
    val cloudRevision: Long = 0,
    val canResumeCloud: Boolean = false,
)

/**
 * Auf dem seriellen Datenbank-/Host-Kontext verwenden (Android: Main). Der Host prüft dies mit [threadGuard].
 * Jede Prüfung liest zusätzlich den SDK-Zustand: ein verzögerter Flow-Collector ist keine Sicherheitsgrenze.
 */
class CloudSessionGate internal constructor(
    private val credentials: () -> CloudCredentials?,
    private val uploads: CloudUploadStore? = null,
    private val threadGuard: () -> Boolean = { true },
    private val ownership: LocalAccountOwnership? = null,
) {
    private val mutable = MutableStateFlow<CloudSession?>(null)
    val session = mutable.asStateFlow()
    private var generation = 0L
    private var identity: CloudCredentials? = null
    private var acceptSdkSession = true
    private var cloudSuspended = false
    private var profileRevision = 0L
    private var cloudRevision = 0L
    private var uploadSession: CloudUploadSession? = null
    private var identityRetained = false
    private val requests = mutableSetOf<Job>()

    private fun confined() = check(threadGuard()) { "Cloud-Sitzung nur auf dem seriellen Host-Kontext verwenden" }

    private fun invalidate(
        leaveIdentity: Boolean = true,
        forceLeave: Boolean = false,
    ) {
        confined()
        check(generation < Long.MAX_VALUE)
        generation++
        profileRevision++
        cloudRevision++
        cloudSuspended = false
        val shouldLeave = leaveIdentity && (identityRetained || ownership?.retainedProfileId != null || forceLeave)
        identity = null
        mutable.value = null
        uploadSession = null
        try {
            uploads?.deactivate()
        } finally {
            requests.toList().forEach { it.cancel(CancellationException("cloud_session_changed")) }
            requests.clear()
            ownership?.detachAccountSession()
            if (shouldLeave) leaveAccount()
        }
    }

    /** Vor dem ersten suspendierenden Aufruf von Logout, Kontowechsel oder Löschantrag. */
    fun stop(leaveIdentity: Boolean = true): Long {
        acceptSdkSession = false
        invalidate(leaveIdentity, forceLeave = true)
        return generation
    }

    /** Sperrt Cloud-Arbeit, erhält aber die Auth-Identität für serververifizierte Kontorechte. */
    internal fun suspendCloud(leaveIdentity: Boolean = false) {
        confined()
        synchronize()
        cloudSuspended = true
        check(profileRevision < Long.MAX_VALUE)
        profileRevision++
        check(cloudRevision < Long.MAX_VALUE)
        cloudRevision++
        val shouldLeave = leaveIdentity && (identityRetained || ownership?.retainedProfileId != null)
        uploadSession = null
        mutable.value = null
        try {
            uploads?.deactivate()
        } finally {
            requests.toList().forEach { it.cancel(CancellationException("cloud_session_changed")) }
            requests.clear()
            ownership?.detachAccountSession()
            if (shouldLeave) leaveAccount()
        }
    }

    private fun leaveAccount() {
        identityRetained = false
        ownership?.leaveAccount()
    }

    /** Ein neuer Profilversuch entwertet ältere Antworten, auch ohne Auth-Kontowechsel. */
    internal fun captureProfile(canResumeCloud: Boolean = true): CloudLease {
        val lease = capture(requireProfile = false)
        check(profileRevision < Long.MAX_VALUE)
        profileRevision++
        return CloudLease(lease.generation, lease.credentials, profileRevision, cloudRevision, canResumeCloud)
    }

    internal fun completeTransition(expected: Long) {
        confined()
        if (generation != expected) changed()
        acceptSdkSession = true
        synchronize()
    }

    internal fun requireTransition(expected: Long) {
        confined()
        if (generation != expected || acceptSdkSession) changed()
    }

    internal fun isTransitionCurrent(expected: Long): Boolean {
        confined()
        return generation == expected && !acceptSdkSession
    }

    internal fun synchronize() {
        confined()
        if (!acceptSdkSession) return
        val sdk = credentials()
        if (sdk?.profileId != identity?.profileId || sdk?.sessionId != identity?.sessionId) {
            val previousProfile = identity?.profileId ?: ownership?.retainedProfileId
            invalidate(leaveIdentity = sdk?.profileId != previousProfile, forceLeave = sdk == null && identity != null)
            identity = sdk
        }
    }

    internal fun verifiedAbsent() {
        confined()
        if (credentials() == null && acceptSdkSession) invalidate(forceLeave = true)
    }

    internal fun capture(requireProfile: Boolean = true): CloudLease {
        synchronize()
        if (!acceptSdkSession) changed()
        val sdk = credentials() ?: changed()
        if (requireProfile && cloudSuspended) throw CloudException("profile_not_active", true)
        if (requireProfile && (mutable.value?.onboarded != true || mutable.value?.deletionRequested != false)) {
            throw CloudException("profile_not_active", true)
        }
        return CloudLease(generation, sdk, cloudRevision = cloudRevision)
    }

    internal fun requireCurrent(lease: CloudLease) {
        synchronize()
        if (!acceptSdkSession || lease.generation != generation ||
            lease.credentials.profileId != identity?.profileId || lease.credentials.sessionId != identity?.sessionId
        ) {
            changed()
        }
        if (lease.profileRevision != null) {
            if (lease.profileRevision != profileRevision) changed()
        } else if (cloudSuspended || lease.cloudRevision != cloudRevision) {
            changed()
        }
    }

    internal fun publish(
        lease: CloudLease,
        profile: CloudSession,
    ) {
        requireCurrent(lease)
        if (profile.profileId != lease.credentials.profileId) changed()
        if (profile.deletionRequested) {
            suspendCloud(leaveIdentity = true)
            mutable.value = profile.copy(generation = generation)
            return
        }
        if (lease.profileRevision != null && !lease.canResumeCloud && !cloudSuspended) suspendCloud()
        if (cloudSuspended) {
            if (lease.profileRevision == null || !lease.canResumeCloud || profile.consentStatus != ConsentStatus.CURRENT) {
                mutable.value = profile.copy(generation = generation)
                return
            }
            cloudSuspended = false
        }
        if (profile.onboarded && uploadSession == null) {
            val activated = uploads?.activate(profile.profileId)
            try {
                if (activated != null) ownership?.activateAccount(profile.profileId, activated)
                uploadSession = activated
                identityRetained = ownership != null && activated != null
            } catch (error: Exception) {
                invalidate()
                throw error
            }
        }
        if (!profile.onboarded && uploadSession != null) {
            invalidate()
            identity = lease.credentials
        }
        mutable.value = profile.copy(generation = generation)
    }

    internal fun adoptionSources(): List<CloudAdoptionSource> {
        capture()
        return ownership?.adoptionSources()?.map { CloudAdoptionSource(it.deviceId, it.kind, it.eventCount) }.orEmpty()
    }

    internal fun adoptSources(ids: Set<String>) {
        val lease = capture()
        val active = uploadSession ?: changed()
        requireCurrent(lease)
        ownership?.adoptSources(active, ids)
        requireCurrent(lease)
    }

    internal suspend fun <T> request(
        lease: CloudLease,
        block: suspend () -> T,
    ): T =
        coroutineScope {
            requireCurrent(lease)
            val job = currentCoroutineContext().job
            requests += job
            try {
                val result = block()
                currentCoroutineContext().ensureActive()
                requireCurrent(lease)
                result
            } finally {
                requests -= job
            }
        }

    internal fun captureUpload(
        lease: CloudLease,
        limit: Int,
    ): CloudUploadSnapshot {
        requireCurrent(lease)
        return requireNotNull(uploads).capture(requireNotNull(uploadSession), limit)
    }

    internal fun requireUpload(
        lease: CloudLease,
        snapshot: CloudUploadSnapshot,
    ) {
        requireCurrent(lease)
        requireNotNull(uploads).requireCurrent(snapshot)
    }

    internal fun acknowledge(
        lease: CloudLease,
        snapshot: CloudUploadSnapshot,
        uploaded: Set<String>,
        rejected: Map<String, String>,
    ) {
        requireUpload(lease, snapshot)
        requireNotNull(uploads).acknowledge(snapshot, uploaded, rejected)
    }

    private fun changed(): Nothing = throw CloudException("cloud_session_changed", true)
}
