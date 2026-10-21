package eu.advantly.android.auth

import android.content.Context
import eu.advantly.match.session.AndroidSessionAccess
import eu.advantly.match.session.AndroidSessionBoundary
import eu.advantly.match.session.MatchSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Eine Kontolaufzeit bleibt an ihre konkrete Android-Session gebunden, auch nach erfolgreichem Storage-Retry. */
internal class AndroidAccountRuntime(
    context: Context,
    session: MatchSession,
    parent: CoroutineScope = CoroutineScope(Dispatchers.Main.immediate),
) : AutoCloseable {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    val scope = CoroutineScope(parent.coroutineContext + job)
    private val access =
        AndroidSessionAccess(context, session) {
            // Nur thread-sichere Capability-/Jobentwertung. Gate-/Auth-State bleibt auf dem bisherigen Hostkontext.
            job.cancel(CancellationException("android_session_revoked"))
        }
    private val storage = AndroidSessionBoundary(context, allowed = { access.ready })

    fun storageAction(action: () -> Unit): Boolean =
        storage.run {
            action()
            true
        } == true

    val allowed: Boolean get() = access.privateTransportAllowed

    override fun close() {
        scope.cancel()
        access.close()
    }
}
