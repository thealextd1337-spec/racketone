package eu.advantly.cloud.sync

import eu.advantly.data.CloudUploadSession
import eu.advantly.data.CloudUploadSnapshot
import eu.advantly.data.CloudUploadStore
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
)

/**
 * Auf dem seriellen Datenbank-/Host-Kontext verwenden (Android: Main). Der Host prüft dies mit [threadGuard].
 * Jede Prüfung liest zusätzlich den SDK-Zustand: ein verzögerter Flow-Collector ist keine Sicherheitsgrenze.
 */
class CloudSessionGate internal constructor(
    private val credentials: () -> CloudCredentials?,
    private val uploads: CloudUploadStore? = null,
    private val threadGuard: () -> Boolean = { true },
) {
    private val mutable = MutableStateFlow<CloudSession?>(null)
    val session = mutable.asStateFlow()
    private var generation = 0L
    private var identity: CloudCredentials? = null
    private var acceptSdkSession = true
    private var uploadSession: CloudUploadSession? = null
    private val requests = mutableSetOf<Job>()

    private fun confined() = check(threadGuard()) { "Cloud-Sitzung nur auf dem seriellen Host-Kontext verwenden" }

    private fun invalidate() {
        confined()
        check(generation < Long.MAX_VALUE)
        generation++
        identity = null
        mutable.value = null
        uploadSession = null
        uploads?.deactivate()
        requests.toList().forEach { it.cancel(CancellationException("cloud_session_changed")) }
        requests.clear()
    }

    /** Vor dem ersten suspendierenden Aufruf von Logout, Kontowechsel oder Löschantrag. */
    fun stop(): Long {
        acceptSdkSession = false
        invalidate()
        return generation
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
            invalidate()
            identity = sdk
        }
    }

    internal fun capture(requireProfile: Boolean = true): CloudLease {
        synchronize()
        if (!acceptSdkSession) changed()
        val sdk = credentials() ?: changed()
        if (requireProfile && (mutable.value?.onboarded != true || mutable.value?.deletionRequested != false)) {
            throw CloudException("profile_not_active", true)
        }
        return CloudLease(generation, sdk)
    }

    internal fun requireCurrent(lease: CloudLease) {
        synchronize()
        if (!acceptSdkSession || lease.generation != generation ||
            lease.credentials.profileId != identity?.profileId || lease.credentials.sessionId != identity?.sessionId
        ) {
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
            stop()
            mutable.value = profile.copy(generation = generation)
            return
        }
        mutable.value = profile.copy(generation = generation)
        if (profile.onboarded && uploadSession == null) uploadSession = uploads?.activate(profile.profileId)
        if (!profile.onboarded && uploadSession != null) {
            uploads?.deactivate()
            uploadSession = null
        }
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
