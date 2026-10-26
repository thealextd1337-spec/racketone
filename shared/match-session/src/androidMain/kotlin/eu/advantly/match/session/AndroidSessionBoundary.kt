package eu.advantly.match.session

import android.content.Context
import eu.advantly.data.AndroidInstallationGuard

/**
 * Enge Android-Hostgrenze um die tatsaechliche synchrone Operation, auch nach Main-Handoff.
 * Kein SQL-Taskrollback, keine Wiederholung: bereits committed Daten bleiben erhalten. Bei Widerruf gibt es
 * keinen Erfolgswert; Programmier-/Validierungsfehler propagiert die bestehende Storagegrenze unveraendert.
 */
class AndroidSessionBoundary(
    context: Context,
    private val allowed: () -> Boolean,
    private val release: () -> Unit = {},
) : AutoCloseable {
    private val app = context.applicationContext

    private data class Attempt<T>(
        val value: T?,
    )

    fun <T> run(block: () -> T): T? {
        if (!allowed()) return null
        val result =
            AndroidInstallationGuard.storage(app) {
                if (!allowed()) {
                    Attempt<T>(null)
                } else {
                    val value = block()
                    Attempt(if (allowed()) value else null)
                }
            }
        return result?.value
    }

    override fun close() = release()

    companion object {
        /** Eigene Access-Lebensdauer fuer retained ViewModels; Rotation einer Activity widerruft sie nicht. */
        fun forSession(
            context: Context,
            session: MatchSession,
        ): AndroidSessionBoundary {
            val access = AndroidSessionAccess(context, session)
            return AndroidSessionBoundary(context, { access.ready }, access::close)
        }
    }
}
