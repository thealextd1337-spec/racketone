package eu.advantly.cloud.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.IOException

/** Der Phone-Default besitzt immer den instrumentierten nativen Transport. */
internal actual fun platformExportHttpTransport(): ExportHttpTransport = AndroidExportHttpTransport()

/** DNS/Observer dienen den nativen Barrieretests; der Produktdefault setzt keine fremde Engine ein. */
internal class AndroidExportHttpTransport(
    dns: Dns = Dns.SYSTEM,
    private val observer: EventListener = EventListener.NONE,
    requestDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ExportHttpTransport {
    internal val dispatcher = Dispatcher()
    private val pool = ConnectionPool()
    private val lock = Any()
    private val calls = mutableSetOf<Call>()
    private val completed = CompletableDeferred<Unit>()
    private var sealed = false
    private var idle = true
    private var cancelling = false
    private var responseBody: ResponseBody? = null

    private fun update(change: () -> Unit) {
        val done =
            synchronized(lock) {
                change()
                sealed && calls.isEmpty() && idle
            }
        // Ausserhalb des Locks fortsetzen: der wartende Host darf dadurch keine Listener-Locks erben.
        if (done) completed.complete(Unit)
    }

    private val native =
        OkHttpClient
            .Builder()
            .dispatcher(dispatcher)
            .connectionPool(pool)
            .dns(dns)
            .fastFallback(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor { chain ->
                val response = chain.proceed(chain.request())
                val discard =
                    synchronized(lock) {
                        responseBody = response.body
                        cancelling
                    }
                // Auch eine zwischen nativer Antwort und Ktor-Continuation verlorene Response gehoert uns.
                if (discard) response.close()
                response
            }.eventListener(
                object : EventListener() {
                    override fun callStart(call: Call) {
                        update {
                            calls += call
                            idle = false
                        }
                        observer.callStart(call)
                    }

                    override fun canceled(call: Call) {
                        observer.canceled(call)
                    }

                    override fun responseHeadersEnd(
                        call: Call,
                        response: Response,
                    ) {
                        observer.responseHeadersEnd(call, response)
                    }

                    override fun callEnd(call: Call) {
                        update { calls -= call }
                        observer.callEnd(call)
                    }

                    override fun callFailed(
                        call: Call,
                        ioe: IOException,
                    ) {
                        update { calls -= call }
                        observer.callFailed(call, ioe)
                    }
                },
            ).build()

    init {
        dispatcher.idleCallback =
            Runnable {
                update { idle = dispatcher.runningCallsCount() == 0 && dispatcher.queuedCallsCount() == 0 }
            }
    }

    override val client =
        HttpClient(OkHttp) {
            followRedirects = false
            install(HttpTimeout)
            engine {
                preconfigured = native
                dispatcher = requestDispatcher
            }
        }

    override fun cancel() {
        val active =
            synchronized(lock) {
                cancelling = true
                calls.toList()
            }
        active.forEach { it.cancel() }
        dispatcher.cancelAll()
    }

    override suspend fun awaitCompletion() {
        // R1-Join ist vorher abgeschlossen: callStart/enqueue laufen synchron im Engine-Request.
        // Damit kann zwischen Versiegelung und Ressourcenfreigabe kein neuer Call mehr entstehen.
        withContext(Dispatchers.IO) { synchronized(lock) { responseBody }?.close() }
        update {
            sealed = true
            idle = dispatcher.runningCallsCount() == 0 && dispatcher.queuedCallsCount() == 0
        }
        completed.await()
    }

    override fun close() {
        check(completed.isCompleted)
        client.close()
        pool.evictAll()
        dispatcher.executorService.shutdown()
    }
}
