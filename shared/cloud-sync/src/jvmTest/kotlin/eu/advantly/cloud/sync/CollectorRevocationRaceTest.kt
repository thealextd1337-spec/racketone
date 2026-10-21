package eu.advantly.cloud.sync

import eu.advantly.data.AdoptionSource
import eu.advantly.data.CloudUploadSession
import eu.advantly.data.LocalAccountOwnership
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.minimalConfig
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Echter SDK-Statuswechsel und zweiter Thread; kein Android-/SQLite-/UI-Vollrepro. */
class CollectorRevocationRaceTest {
    private class SerialDispatcher : CoroutineDispatcher() {
        private val owner = Thread.currentThread()
        private val queue = LinkedBlockingQueue<Runnable>()

        override fun isDispatchNeeded(context: CoroutineContext) = Thread.currentThread() !== owner

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            queue.put(block)
        }

        fun drain() {
            while (true) (queue.poll() ?: break).run()
        }

        fun pump(done: () -> Boolean) {
            drain()
            while (!done()) {
                checkNotNull(queue.poll(10, TimeUnit.SECONDS)) { "SDK collector did not reach the barrier" }.run()
            }
        }
    }

    private fun scenario(
        race: Int = 0,
        failure: Exception? = null,
        revokeOnFailure: Boolean = false,
        hookFailure: Boolean = false,
        defaults: Boolean = false,
    ) {
        val dispatcher = SerialDispatcher()
        val errors = mutableListOf<Throwable>()
        val job = SupervisorJob()
        // Ausschliesslich Fehlerbeobachter im Test, keine Produktionsbehandlung.
        val scope = CoroutineScope(job + dispatcher + CoroutineExceptionHandler { _, error -> errors += error })
        val allowed = AtomicBoolean(true)
        var armed = false
        var insideOperation = false
        var completed = 0
        var reads = 0
        var failures = 0
        var innerChecks = 0
        var detached = false
        var checksAfterDetach = 0
        var raceHits = 0

        fun revoke() {
            val done = CountDownLatch(1)
            val background =
                Thread {
                    allowed.set(false)
                    job.cancel(CancellationException("concrete_host_revoked"))
                    done.countDown()
                }
            background.start()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            background.join(5_000)
            assertFalse(background.isAlive)
        }

        val ownership =
            object : LocalAccountOwnership {
                override val retainedProfileId: String? get() {
                    reads++
                    if (armed && failure != null && !hookFailure && failures++ == 0) {
                        if (revokeOnFailure) revoke()
                        throw failure
                    }
                    return null
                }

                override fun detachAccountSession() {
                    detached = true
                }

                override fun leaveAccount() {}

                override fun activateAccount(
                    profileId: String,
                    session: CloudUploadSession,
                ) {}

                override fun adoptionSources() = emptyList<AdoptionSource>()

                override fun adoptSources(
                    session: CloudUploadSession,
                    deviceIds: Set<String>,
                ) {}
            }
        val sdk =
            createSupabaseClient("https://collector.test", "public") {
                defaultLogLevel = LogLevel.NONE
                httpEngine = MockEngine { error("No HTTP request expected") }
                install(Auth) { minimalConfig() }
            }
        val auth =
            if (defaults) {
                // Beide Hostcallbacks fehlen absichtlich, einschliesslich direktem Storage-Default.
                SupabasePhoneAuth(sdk, "test", scope = scope, ownership = ownership)
            } else {
                SupabasePhoneAuth(
                    sdk,
                    "test",
                    scope = scope,
                    ownership = ownership,
                    hostAccessAllowed = {
                        val captured = allowed.get()
                        if (armed && insideOperation && raceHits == 0 && captured) {
                            innerChecks++
                            if (detached) checksAfterDetach++
                            // Erste innere Vorpruefung bzw nach Gates letztem Invalidatecheck die Vorpruefung
                            // zur zweiten Collectoroperation. Der schon gelesene Wert bleibt true.
                            if ((race == 1 && innerChecks == 1) || (race == 2 && checksAfterDetach == 2)) {
                                raceHits++
                                revoke()
                            }
                        }
                        captured
                    },
                    hostStorageAction = { action ->
                        if (armed && hookFailure && failure != null) {
                            if (revokeOnFailure) revoke()
                            throw failure
                        }
                        insideOperation = true
                        try {
                            action()
                            completed++
                            allowed.get()
                        } finally {
                            insideOperation = false
                        }
                    },
                )
            }
        try {
            runBlocking { sdk.auth.awaitInitialization() }
            dispatcher.drain()
            val before = completed
            armed = true
            runBlocking {
                sdk.auth.importAuthToken("header.eyJzdWIiOiJjb2xsZWN0b3IiLCJzZXNzaW9uX2lkIjoib25lIn0.signature", autoRefresh = false)
            }
            dispatcher.pump { errors.isNotEmpty() || if (defaults) reads > 0 else completed > before }
            if (race != 0) {
                assertEquals(1, raceHits)
                assertTrue(job.isCancelled)
                assertFalse(allowed.get())
                assertTrue(errors.isEmpty(), "Revoked collector escaped: $errors")
                assertEquals(before + 1, completed)
            } else if (failure != null) {
                assertSame(failure, errors.single())
            } else {
                assertTrue(errors.isEmpty())
                assertTrue(allowed.get())
                assertTrue(reads > 0)
            }
        } finally {
            armed = false
            auth.close()
            scope.cancel()
            runBlocking { sdk.close() }
        }
    }

    @Test fun revokeBetweenFirstAccessReadAndGateOperationIsAborted() = scenario(race = 1)

    @Test fun revokeBetweenSecondAccessReadAndAccountOperationIsAborted() = scenario(race = 2)

    @Test fun healthyHostStillSynchronizes() = scenario()

    @Test fun sameCloudReasonWithAllowedHostPropagates() = scenario(failure = CloudException("cloud_session_changed", true))

    @Test fun otherCloudReasonWithRevokedHostPropagates() =
        scenario(
            failure = CloudException("profile_missing", true),
            revokeOnFailure = true,
        )

    @Test fun permissionFalseWithRevokedHostPropagates() =
        scenario(failure = CloudException("cloud_session_changed", false), revokeOnFailure = true)

    @Test fun programmingErrorWithRevokedHostPropagates() =
        scenario(
            failure = IllegalArgumentException("programming"),
            revokeOnFailure = true,
        )

    @Test fun storageHookOwnCloudErrorIsNotSuppressed() =
        scenario(failure = CloudException("cloud_session_changed", true), revokeOnFailure = true, hookFailure = true)

    @Test fun storageHookOwnIoErrorIsNotSuppressed() = scenario(failure = IOException("hook IO"), hookFailure = true)

    @Test fun defaultHealthyHostIsUnchanged() = scenario(defaults = true)

    @Test fun defaultIoErrorKeepsIdentity() = scenario(failure = IOException("default IO"), defaults = true)

    @Test fun defaultProgrammingErrorKeepsIdentity() = scenario(failure = IllegalArgumentException("default programming"), defaults = true)

    @Test fun defaultMatchingCloudErrorKeepsIdentity() = scenario(failure = CloudException("cloud_session_changed", true), defaults = true)
}
