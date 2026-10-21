package eu.advantly.ui.phone.live

import android.content.Context
import com.google.android.gms.wearable.Wearable
import eu.advantly.core.model.MatchId
import eu.advantly.match.session.AndroidSessionAccess
import eu.advantly.match.session.MatchSession
import eu.advantly.presentation.live.LiveTelemetryGate
import eu.advantly.sync.protocol.LiveTelemetrySubscription
import eu.advantly.sync.protocol.LiveTelemetrySubscriptionCodec
import eu.advantly.sync.protocol.PeerKind

/** Ein laufendes Abo gehört genau seiner veröffentlichten Session, auch zwischen zwei Lifecycle-Callbacks. */
class AndroidTelemetrySubscription(
    context: Context,
    private val session: MatchSession,
    private val matchId: MatchId,
    private val peer: String,
) : AutoCloseable {
    private val access = AndroidSessionAccess(context, session)
    private val app = context.applicationContext
    private val client by lazy { Wearable.getMessageClient(app) }

    fun keepAlive(): Boolean {
        if (!access.privateTransportAllowed) return false
        val state = session.state.value
        if (state.watchMatchId != matchId || state.match(matchId)?.meta?.watchWorkout != true ||
            !LiveTelemetryGate.compatiblePeer(state.sync.peers, peer, PeerKind.WATCH)
        ) {
            return false
        }
        client.sendMessage(
            peer,
            LiveTelemetrySubscription.MESSAGE_PATH,
            LiveTelemetrySubscriptionCodec.encode(LiveTelemetrySubscription(LiveTelemetrySubscription.CURRENT_VERSION, matchId)),
        )
        return true
    }

    override fun close() = access.close()
}
