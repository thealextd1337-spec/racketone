package eu.advantly.android.auth

import eu.advantly.cloud.sync.LiveSignal
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.realtime
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.hours

/** Ohne Netz: Der Kanal entsteht beim Sammeln und verschwindet mit dem letzten Sammler, auch wenn er nie stand. */
class PhoneLiveSignalsTest {
    private val topic = "realtime:live:p1"

    @Test fun lastCollectorLeavesTheSharedChannel() =
        runBlocking {
            val client =
                createSupabaseClient("https://advantly.invalid", "public-key") {
                    httpEngine = MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }
                    // Der Verbindungsversuch scheitert; der Wiederholungsversuch des SDK soll den Test nicht stören.
                    install(Realtime) { reconnectDelay = 1.hours }
                }
            // Eigener Scope: shareIn hält dort seinen Teil-Job bis zum Abbau (wie der Konto-Scope der App).
            val scope = CoroutineScope(coroutineContext + Job())
            val signals = PhoneLiveSignals(client.realtime, scope)
            val seen = mutableListOf<LiveSignal>()
            val first = launch { signals.signals("p1").collect { seen += it } }
            val second = launch { signals.signals("p1").collect {} }
            withTimeout(5_000) { while (topic !in client.realtime.subscriptions) delay(10) }
            assertEquals(setOf(topic), client.realtime.subscriptions.keys)
            withTimeout(5_000) { while (seen.isEmpty()) delay(10) }
            assertEquals("nie beigetreten", listOf(LiveSignal.LOST), seen)

            first.cancelAndJoin()
            delay(100)
            assertTrue(topic in client.realtime.subscriptions)
            second.cancelAndJoin()
            withTimeout(5_000) { while (client.realtime.subscriptions.isNotEmpty()) delay(10) }
            scope.cancel()
            client.close()
        }
}
