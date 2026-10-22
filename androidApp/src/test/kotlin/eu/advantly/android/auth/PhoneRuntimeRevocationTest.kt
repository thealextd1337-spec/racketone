package eu.advantly.android.auth

import android.app.Application
import android.database.sqlite.SQLiteDiskIOException
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import eu.advantly.android.MainActivity
import eu.advantly.android.UnsupportedDirectorySyncLinux
import eu.advantly.data.AndroidInstallationGuard
import eu.advantly.data.createAndroidDriver
import eu.advantly.data.db.AdvantlyDatabase
import eu.advantly.match.session.AndroidMatchSessions
import eu.advantly.presentation.auth.AuthViewModel
import io.github.jan.supabase.auth.minimalConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [UnsupportedDirectorySyncLinux::class])
class PhoneRuntimeRevocationTest {
    @org.junit.Before
    fun reset() =
        eu.advantly.android
            .InstallationHostFailureTest()
            .reset()

    private var closeFailure: Exception? = null
    private var closeCompleted = false

    private fun awaitClosed() {
        val until = System.nanoTime() + 10_000_000_000
        while (!closeCompleted) {
            check(System.nanoTime() < until) { "Auth close did not complete" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
    }

    private fun configuredAuth(activity: MainActivity): AuthViewModel {
        val session = AndroidMatchSessions.current()!!
        val sdk =
            io.github.jan.supabase.createSupabaseClient("https://cloud.test", "public") {
                defaultLogLevel = io.github.jan.supabase.logging.LogLevel.NONE
                install(io.github.jan.supabase.auth.Auth) { minimalConfig() }
            }
        val runtime = AndroidAccountRuntime(activity, session)
        val auth =
            eu.advantly.cloud.sync.SupabasePhoneAuth(
                sdk,
                "controlled-secret",
                session.data.cloudUploads,
                runtime.scope,
                ownership = session,
                hostAccessAllowed = { runtime.allowed },
            )
        val backend =
            auth.cloudBackend(
                eu.advantly.cloud.sync
                    .CloudInstallation("installation", "a".repeat(64)),
            )
        val observed =
            object : eu.advantly.cloud.sync.CloudAuthPort by auth {
                override fun close() {
                    try {
                        auth.close()
                    } catch (
                        error: Exception,
                    ) {
                        closeFailure = error
                        throw error
                    } finally {
                        runtime.close()
                        closeCompleted = true
                    }
                }
            }
        return AuthViewModel(
            observed,
            upload =
                PhoneUploadHost(auth, session, backend, backend::bindDevice, runtime.scope, hostAccessAllowed = {
                    runtime.allowed
                }, lane = PhoneCloudLane()),
            export = PhoneExportHost(auth.dataExports(), activity.applicationContext, scope = runtime.scope),
            workScope = runtime.scope,
        )
    }

    @Test
    fun configuredAuthCloseAfterRevocationIsSqlFree() {
        val app = RuntimeEnvironment.getApplication()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        val auth = configuredAuth(activity.get())
        ViewModelProvider(
            activity.get(),
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = auth as T
            },
        )[AuthViewModel::class.java]
        shadowOf(Looper.getMainLooper()).idle()
        val before =
            java.io
                .File(app.noBackupFilesDir, "installation-v1")
                .readBytes()
                .toList()
        AndroidInstallationGuard.storageFailed(app, SQLiteDiskIOException("runtime failure with configured auth"))
        val failure = runCatching { shadowOf(Looper.getMainLooper()).idle() }.exceptionOrNull()
        awaitClosed()
        assertNull(closeFailure)
        println("Configured auth cleanup: ${failure?.javaClass?.name}: ${failure?.message}")
        assertNull(failure)
        assertNotNull(
            activity
                .get()
                .window.decorView
                .findViewWithTag<android.view.View>("installation_error"),
        )
        assertEquals(
            before,
            java.io
                .File(app.noBackupFilesDir, "installation-v1")
                .readBytes()
                .toList(),
        )
        activity.pause().stop().destroy()
    }

    @Test
    fun cleanupFailuresKeepCauseAndCloseTransport() {
        val session = AndroidMatchSessions.get(RuntimeEnvironment.getApplication(), eu.advantly.sync.protocol.PeerKind.PHONE)
        val primary = java.io.IOException("ownership read failed")
        val secondary = java.io.IOException("detach failed")
        var armed = false
        val ownership =
            object : eu.advantly.data.LocalAccountOwnership by session {
                override val retainedProfileId: String? get() = if (armed) throw primary else session.retainedProfileId

                override fun detachAccountSession() {
                    if (armed) throw secondary else session.detachAccountSession()
                }
            }
        val engine =
            io.ktor.client.engine.mock
                .MockEngine { error("Cleanup must not send HTTP") }
        val sdk =
            io.github.jan.supabase.createSupabaseClient("https://cloud.test", "public") {
                defaultLogLevel = io.github.jan.supabase.logging.LogLevel.NONE
                install(io.github.jan.supabase.auth.Auth) { minimalConfig() }
                httpEngine = engine
            }
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
        val auth =
            eu.advantly.cloud.sync
                .SupabasePhoneAuth(sdk, "secret", scope = scope, ownership = ownership)
        shadowOf(Looper.getMainLooper()).idle()
        // Nur Testbeobachtung des privaten Client-Lebenszyklus, kein produktiver Adapter/Reflective-Fallback.
        val transport =
            auth.javaClass
                .getDeclaredField("transport")
                .apply { isAccessible = true }
                .get(auth)
        val http =
            transport.javaClass
                .getDeclaredField("http")
                .apply { isAccessible = true }
                .get(transport) as io.ktor.client.HttpClient
        assertTrue(http.coroutineContext[kotlinx.coroutines.Job]!!.isActive)
        armed = true
        val failure = runCatching { auth.sessions.stop() }.exceptionOrNull()
        org.junit.Assert.assertSame(primary, failure)
        org.junit.Assert.assertTrue(primary.suppressedExceptions.any { it === secondary })
        val closeFailure = runCatching { auth.close() }.exceptionOrNull()
        org.junit.Assert.assertSame(secondary, closeFailure)
        assertTrue(!http.coroutineContext[kotlinx.coroutines.Job]!!.isActive)
        engine.close()
        scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun healthyAuthClosePersistsGeneration() {
        val app = RuntimeEnvironment.getApplication()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        val auth = configuredAuth(activity.get())
        shadowOf(Looper.getMainLooper()).idle()
        val driver = createAndroidDriver(app)
        try {
            val queries = AdvantlyDatabase(driver).cloudUploadQueries
            val before = queries.generation().executeAsOne()
            val store = androidx.lifecycle.ViewModelStore()
            ViewModelProvider(
                store,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T = auth as T
                },
            )[AuthViewModel::class.java]
            store.clear()
            shadowOf(Looper.getMainLooper()).idle()
            awaitClosed()
            assertNull(closeFailure)
            assertTrue(queries.generation().executeAsOne() > before)
            assertNotNull(AndroidMatchSessions.current())
            assertTrue(AndroidInstallationGuard.privateTransportAllowed(app))
        } finally {
            driver.close()
            activity.pause().stop().destroy()
        }
    }
}
