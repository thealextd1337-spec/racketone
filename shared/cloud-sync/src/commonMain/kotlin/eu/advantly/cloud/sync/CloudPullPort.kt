package eu.advantly.cloud.sync

/** Ein Auth-/Consent-Lease und genau die beim Start geprüfte Gerätebindung, ohne Credentials in Ausgaben. */
class CloudPullAttempt internal constructor(
    internal val lease: CloudLease,
    internal val deviceBinding: CloudDeviceBinding,
) {
    val profileId: String get() = lease.credentials.profileId
    val generation: Long get() = lease.generation
}

/** Kleiner ausführbarer Lesepfad; ein Host darf weder Bindung noch Anmeldung daraus ableiten. */
interface CloudPullPort {
    fun capturePull(): CloudPullAttempt

    fun requireCurrentPull(attempt: CloudPullAttempt)

    suspend fun pullPage(
        attempt: CloudPullAttempt,
        cursor: Long,
        limit: Int,
    ): PullResponse

    suspend fun pullEventPage(
        attempt: CloudPullAttempt,
        matchId: String,
        afterServerSeq: Long,
        limit: Int,
    ): List<PulledEvent>

    suspend fun pullWatermarks(
        attempt: CloudPullAttempt,
        matchId: String,
    ): List<EventWatermark>

    suspend fun pullRedactionPage(
        attempt: CloudPullAttempt,
        request: CloudRedactionRequest,
    ): CloudRedactionPage

    /** `cloud_match_readiness_v1`: Versionsvektor bis H; nur zusammen mit abgeschlossenem Redaktionstransfer verwenden. */
    suspend fun pullReadiness(
        attempt: CloudPullAttempt,
        request: CloudReadinessRequest,
    ): CloudMatchReadiness
}
