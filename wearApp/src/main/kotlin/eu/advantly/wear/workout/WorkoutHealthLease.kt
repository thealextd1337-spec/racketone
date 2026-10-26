package eu.advantly.wear.workout

import android.os.Handler
import android.os.Looper
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import java.util.concurrent.ExecutionException

/**
 * Prozessweite Health-Uebernahme nur auf Main. OWNED bezeichnet die App, nicht die Servicegeneration.
 * Ein bereits an Health Services abgegebenes Ende bleibt bis zum nativen Futureabschluss eine Barriere,
 * auch wenn der alte Service oder sein Coroutine-Warter beendet wird. Keine persistente Recoveryfreigabe.
 */
internal class WorkoutHealthLease private constructor() {
    val current: Boolean get() {
        confined()
        return owner === this
    }
    val ready: Boolean get() = current && ending == null

    suspend fun awaitReady(): Boolean {
        while (current) {
            val pending = ending ?: return true
            // Cancellation dieses Wartens cancelt weder pending noch das native Health-Future.
            pending.await()
        }
        return false
    }

    fun end(operation: () -> ListenableFuture<Void>): Deferred<Unit>? {
        if (!ready) return null
        val pending = CompletableDeferred<Unit>()
        val result = CompletableDeferred<Unit>()
        // Vor dem nativen Aufruf setzen: Der Aufruf selbst darf eine neue Uebernahme reentrant ausloesen.
        ending = pending

        fun complete(error: Exception?) {
            confined()
            check(ending === pending)
            ending = null
            // Erst die Barriere loesen, dann Warter fortsetzen. Neue Adoption fragt danach echten Status ab.
            pending.complete(Unit)
            if (error == null) result.complete(Unit) else result.completeExceptionally(error)
        }
        try {
            val native = operation()
            native.addListener({
                val failure =
                    try {
                        native.get()
                        null
                    } catch (error: ExecutionException) {
                        error.cause as? Exception ?: error
                    } catch (error: Exception) {
                        error
                    }
                complete(failure)
            }, { command -> handler.post(command) })
        } catch (error: Exception) {
            // Synchrones Throw ist terminal; reentrant wartende Adoption wird erst danach freigegeben.
            complete(error)
        }
        return result
    }

    companion object {
        private var owner: WorkoutHealthLease? = null
        private var ending: CompletableDeferred<Unit>? = null
        private val handler = Handler(Looper.getMainLooper())

        private fun confined() = check(Looper.myLooper() == Looper.getMainLooper()) { "Health lease requires main thread" }

        /** Erst nach tatsaechlicher Ready-Serviceuebernahme aufrufen. Unavailable-Starts entwerten keinen Besitzer. */
        fun take(): WorkoutHealthLease {
            confined()
            return WorkoutHealthLease().also { owner = it }
        }
    }
}
