package eu.advantly.match.session

import eu.advantly.sync.protocol.AppBuild
import eu.advantly.sync.protocol.CompatibilityStatus
import eu.advantly.sync.protocol.PeerKind
import kotlinx.serialization.Serializable

/** F31: Farbe wird immer zusammen mit Text und Badge dargestellt. */
enum class DeviceLight { GREEN, YELLOW, RED, GRAY }

/** Auch offline gemerkte Gegenstelle; [reachable] wird nie aus dem gespeicherten Zustand übernommen. */
@Serializable
data class DeviceStatus(
    val peer: String,
    val kind: PeerKind,
    val name: String = "",
    val appBuild: AppBuild? = null,
    val compatibility: CompatibilityStatus? = null,
    val lastSeenAtMs: Long? = null,
    val reachable: Boolean = false,
) {
    fun light(local: AppBuild?): DeviceLight =
        when {
            !reachable || (compatibility != null && compatibility != CompatibilityStatus.COMPATIBLE) -> DeviceLight.RED
            compatibility == CompatibilityStatus.COMPATIBLE && sameCommit(local?.commit, appBuild?.commit) -> DeviceLight.GREEN
            else -> DeviceLight.YELLOW
        }
}

/** Drei 30-s-Abgleiche; zwei verpasste Antworten werden toleriert. Transport-Abbruch wirkt sofort. */
const val DEVICE_CONTACT_TIMEOUT_MS: Long = 90_000L

fun contactIsFresh(
    nowMs: Long,
    lastContactMs: Long?,
): Boolean = lastContactMs != null && nowMs - lastContactMs in 0..DEVICE_CONTACT_TIMEOUT_MS

private fun sameCommit(
    local: String?,
    remote: String?,
): Boolean = local != null && local.matches(Regex("[0-9a-fA-F]{7,40}(-dirty)?")) && local == remote
