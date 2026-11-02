package eu.advantly.android.auth

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.MatchId
import eu.advantly.match.session.MatchSession
import eu.advantly.presentation.friends.ScorerDevices
import eu.advantly.sync.protocol.CompatibilityStatus
import eu.advantly.sync.protocol.PeerKind

/**
 * Geräteseite der Übergabe (plan-live-mitspieler §3.4) über die Android-Session; nur auf Main und nur, solange die
 * Android-Session gilt ([allowed]). Geräte-IDs der Session sind die Cloud-Geräte-IDs (`bind_cloud_session_v1`).
 */
internal class PhoneScorerDevices(
    private val session: MatchSession,
    private val allowed: () -> Boolean,
) : ScorerDevices {
    override val phoneId: String get() = session.deviceId.value

    /** Verbundene, kompatible Uhr, sonst die einzige bekannte. */
    override fun watchId(): String? {
        if (!allowed()) return null
        val connected =
            session.state.value.sync.peers
                .firstOrNull { it.kind == PeerKind.WATCH && it.compatibility == CompatibilityStatus.COMPATIBLE }
                ?.deviceId
        return (connected ?: session.knownDevices(PeerKind.WATCH).singleOrNull())?.value
    }

    override fun scoring(matchId: String): Boolean = allowed() && session.scoresHere(MatchId(matchId))

    override fun handOver(
        matchId: String,
        deviceId: String,
    ): Boolean = allowed() && session.handOverScoring(MatchId(matchId), DeviceId(deviceId))

    override fun canTakeOver(matchId: String): Boolean = allowed() && session.canTakeOverAsRole(MatchId(matchId))

    override fun takeOver(matchId: String): Boolean = allowed() && session.takeOverAsRole(MatchId(matchId))
}
