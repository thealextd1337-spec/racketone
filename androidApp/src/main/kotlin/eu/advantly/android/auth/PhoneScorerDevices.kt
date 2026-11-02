package eu.advantly.android.auth

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.EventId
import eu.advantly.core.model.MatchId
import eu.advantly.match.session.MatchSession
import eu.advantly.match.session.ScorerHandover
import eu.advantly.presentation.friends.Handover
import eu.advantly.presentation.friends.ScorerDevices
import eu.advantly.sync.protocol.CompatibilityStatus
import eu.advantly.sync.protocol.PeerKind

/**
 * Geräteseite der Übergabe (plan-live-mitspieler §3.4) über die Android-Session; nur auf Main und nur, solange die
 * Android-Session gilt ([allowed]). Geräte-IDs der Session sind die Cloud-Geräte-IDs (`bind_cloud_session_v1`).
 * [cloudAnswer] liest die Antwort des Servers auf ein Event (Host: innerhalb der Session-Grenze).
 */
internal class PhoneScorerDevices(
    private val session: MatchSession,
    private val cloudAnswer: (eventId: String) -> Boolean?,
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
    ): Handover? {
        if (!allowed()) return null
        val handover = session.handOverScoring(MatchId(matchId), DeviceId(deviceId)) ?: return null
        return Handover(handover.eventId.value, handover.from.value, handover.to.value)
    }

    override fun uploaded(eventId: String): Boolean? = if (allowed()) cloudAnswer(eventId) else null

    override fun handBack(
        matchId: String,
        handover: Handover,
    ): Boolean =
        allowed() &&
            session.handBackScoring(
                MatchId(matchId),
                ScorerHandover(EventId(handover.eventId), DeviceId(handover.from), DeviceId(handover.to)),
            )

    override fun canTakeOver(matchId: String): Boolean = allowed() && session.canTakeOverAsRole(MatchId(matchId))

    override fun takeOver(matchId: String): Boolean = allowed() && session.takeOverAsRole(MatchId(matchId))
}
