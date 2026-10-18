package eu.advantly.cloud.sync

import eu.advantly.data.AccountIdentityRepository

/**
 * Android-Quellen aus dem belegten U2-Katalog, auch nach späterer Übernahme einer Uhr.
 * Ein Versuch behält seine Gerätewerte bis zur nächsten Auswahl; Status liest und wählt keine Quellen.
 * Callerbindung und Ack-Prüfungen bleiben ausschließlich beim bestehenden Backend.
 */
class OwnedSourceUploadPort(
    private val delegate: CloudUploadPort,
    private val identities: AccountIdentityRepository,
) : CloudUploadPort by delegate {
    private var selected: CloudUploadAttempt? = null
    private var devices: List<DeviceDto> = emptyList()

    override fun captureUpload(limit: Int): CloudUploadAttempt = freeze(delegate.captureUpload(limit))

    override fun nextUpload(
        attempt: CloudUploadAttempt,
        limit: Int,
    ): CloudUploadAttempt = freeze(delegate.nextUpload(attempt, limit))

    private fun freeze(attempt: CloudUploadAttempt): CloudUploadAttempt {
        delegate.requireCurrentUpload(attempt)
        val sources = attempt.events.mapTo(mutableSetOf()) { it.deviceId }
        val owned = identities.ownedSources(attempt.profileId).filter { it.deviceId in sources }
        if (owned.map { it.deviceId }.toSet() != sources) throw CloudException("device_not_authorized", true)
        val frozen =
            owned.map {
                when (it.kind) {
                    "PHONE" -> DeviceDto(it.deviceId, "phone", "android")
                    "WATCH" -> DeviceDto(it.deviceId, "watch", "wearos")
                    else -> throw CloudException("device_not_authorized", true)
                }
            }
        selected = attempt
        devices = frozen
        return attempt
    }

    override suspend fun pushSelected(
        attempt: CloudUploadAttempt,
        batch: PushBatch,
    ): PushResponse {
        delegate.requireCurrentUpload(attempt)
        check(selected === attempt) { "Quellen nur für den eingefrorenen Versuch senden" }
        require(batch.devices.isEmpty())
        return delegate.pushSelected(attempt, batch.copy(devices = devices))
    }
}
