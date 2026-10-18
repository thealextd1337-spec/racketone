package eu.advantly.cloud.sync

import eu.advantly.data.CloudMetadataRevision
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
    val requiresConsent: Boolean = false,
    val consentGeneration: Long = 0,
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
    private var consentGeneration = 0L
    private var identity: CloudCredentials? = null
    private var acceptSdkSession = true
    private var cloudSuspended = false
    private var profileRevision = 0L
    private var cloudRevision = 0L
    private var uploadSession: CloudUploadSession? = null
    private var ownershipActive = false
    private val requests = mutableMapOf<Job, Boolean>()

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
        val shouldLeave = leaveIdentity && (ownershipActive || forceLeave)
        ownershipActive = false
        identity = null
        mutable.value = null
        uploadSession = null
        try {
            uploads?.deactivate()
        } finally {
            requests.keys.toList().forEach { it.cancel(CancellationException("cloud_session_changed")) }
            requests.clear()
            ownership?.detachAccountSession()
            if (shouldLeave) ownership?.leaveAccount()
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
        check(consentGeneration < Long.MAX_VALUE)
        consentGeneration++
        val shouldLeave = leaveIdentity && ownershipActive
        ownershipActive = false
        uploadSession = null
        mutable.value = null
        try {
            uploads?.deactivate()
        } finally {
            val cloudRequests = requests.filterValues { it }.keys.toList()
            cloudRequests.forEach { it.cancel(CancellationException("cloud_session_changed")) }
            cloudRequests.forEach(requests::remove)
            ownership?.detachAccountSession()
            if (shouldLeave) ownership?.leaveAccount()
        }
    }

    /** Ein neuer Profilversuch entwertet ältere Antworten, auch ohne Auth-Kontowechsel. */
    internal fun captureProfile(canResumeCloud: Boolean = true): CloudLease {
        val lease = capture(requireProfile = false)
        check(profileRevision < Long.MAX_VALUE)
        profileRevision++
        return CloudLease(
            lease.generation,
            lease.credentials,
            requiresConsent = false,
            consentGeneration = lease.consentGeneration,
            profileRevision = profileRevision,
            cloudRevision = cloudRevision,
            canResumeCloud = canResumeCloud,
        )
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
            invalidate(leaveIdentity = sdk?.profileId != identity?.profileId, forceLeave = sdk == null && identity != null)
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
        if (requireProfile) requireCloudProfile()
        return CloudLease(
            generation,
            sdk,
            requiresConsent = requireProfile,
            consentGeneration = consentGeneration,
            cloudRevision = cloudRevision,
        )
    }

    private fun requireCloudProfile() {
        if (cloudSuspended || mutable.value?.onboarded != true || mutable.value?.deletionRequested != false) {
            throw CloudException("profile_not_active", true)
        }
        if (mutable.value?.consentStatus != ConsentStatus.CURRENT) {
            throw CloudException("consent_required", true)
        }
    }

    internal fun requireCurrent(lease: CloudLease) {
        synchronize()
        if (!acceptSdkSession || lease.generation != generation ||
            lease.credentials.profileId != identity?.profileId || lease.credentials.sessionId != identity?.sessionId
        ) {
            changed()
        }
        if (lease.profileRevision != null && lease.profileRevision != profileRevision) changed()
        if (lease.requiresConsent) {
            requireCloudProfile()
            if (lease.consentGeneration != consentGeneration || lease.cloudRevision != cloudRevision) changed()
        }
    }

    internal fun publish(
        lease: CloudLease,
        profile: CloudSession,
    ) {
        requireCurrent(lease)
        if (profile.profileId != lease.credentials.profileId) changed()
        if (lease.consentGeneration != consentGeneration) changed()
        if (mutable.value?.consentStatus != profile.consentStatus) {
            check(consentGeneration < Long.MAX_VALUE)
            consentGeneration++
        }
        if (profile.deletionRequested) {
            suspendCloud(leaveIdentity = true)
            mutable.value = profile.copy(generation = generation)
            return
        }
        if (cloudSuspended) {
            if (lease.profileRevision == null || !lease.canResumeCloud || profile.consentStatus != ConsentStatus.CURRENT) {
                mutable.value = profile.copy(generation = generation)
                return
            }
            cloudSuspended = false
        }
        if (profile.onboarded && profile.consentStatus != ConsentStatus.CURRENT) {
            mutable.value = profile.copy(generation = generation)
            if (uploadSession != null) {
                uploadSession = null
                uploads?.deactivate()
                ownership?.detachAccountSession()
            }
            // Zustimmungssperre verändert keine U2-Kontoidentität und sperrt keine unabhängigen Konto-RPCs.
            val cloudRequests = requests.filterValues { it }.keys.toList()
            cloudRequests.forEach { it.cancel(CancellationException("consent_required")) }
            cloudRequests.forEach(requests::remove)
            return
        }
        if (profile.onboarded && profile.consentStatus == ConsentStatus.CURRENT && uploadSession == null) {
            val activated = uploads?.activate(profile.profileId)
            try {
                if (activated != null) ownership?.activateAccount(profile.profileId, activated)
                uploadSession = activated
                ownershipActive = ownership != null && activated != null
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
            requests[job] = lease.requiresConsent
            try {
                val result = block()
                currentCoroutineContext().ensureActive()
                requireCurrent(lease)
                result
            } finally {
                requests.remove(job)
            }
        }

    internal fun captureUpload(
        lease: CloudLease,
        limit: Int,
    ): CloudUploadSnapshot {
        requireCurrent(lease)
        requireCloudProfile()
        return requireNotNull(uploads).capture(requireNotNull(uploadSession), limit)
    }

    internal fun requireUpload(
        lease: CloudLease,
        snapshot: CloudUploadSnapshot,
    ) {
        requireCurrent(lease)
        requireCloudProfile()
        requireNotNull(uploads).requireCurrent(snapshot)
    }

    internal fun acknowledge(
        lease: CloudLease,
        snapshot: CloudUploadSnapshot,
        uploaded: Set<String>,
        rejected: Map<String, String>,
        metadata: Set<CloudMetadataRevision> = emptySet(),
    ) {
        requireUpload(lease, snapshot)
        requireNotNull(uploads).acknowledge(snapshot, uploaded, rejected, metadata)
    }

    private fun changed(): Nothing = throw CloudException("cloud_session_changed", true)
}
