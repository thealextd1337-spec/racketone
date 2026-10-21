package eu.advantly.match.session

import android.content.Context
import eu.advantly.data.AndroidInstallationAvailability
import eu.advantly.data.AndroidInstallationGuard

/** Zugriff auf genau eine veröffentlichte Session. Widerruf gilt sofort, auch vor der Mainthread-UI-Reaktion. */
class AndroidSessionAccess(
    context: Context,
    private val session: MatchSession,
    onRevoked: () -> Unit = {},
) : AutoCloseable {
    private val app = context.applicationContext

    @Volatile private var revoked = false
    private val unregister =
        AndroidInstallationGuard.onUnavailable(app) {
            revoked = true
            onRevoked()
        }

    val ready: Boolean get() =
        !revoked && AndroidMatchSessions.current() === session &&
            AndroidInstallationGuard.check(app) is AndroidInstallationAvailability.Ready
    val privateTransportAllowed: Boolean get() = ready && AndroidInstallationGuard.privateTransportAllowed(app)

    override fun close() {
        revoked = true
        unregister()
    }
}
