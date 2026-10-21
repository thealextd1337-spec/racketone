package eu.advantly.wear.workout

import android.app.Application
import android.database.sqlite.SQLiteDiskIOException
import android.os.Looper
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.health.services.client.ExerciseClient
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.ExerciseInfo
import androidx.health.services.client.data.ExerciseState
import androidx.health.services.client.data.ExerciseStateInfo
import androidx.health.services.client.data.ExerciseTrackedStatus
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import eu.advantly.data.AndroidInstallationGuard
import eu.advantly.match.session.AndroidMatchSessions
import eu.advantly.sync.protocol.PeerKind
import eu.advantly.wear.sync.UnsupportedDirectorySyncLinux
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [UnsupportedDirectorySyncLinux::class])
class WorkoutRuntimeRevocationTest {
    @org.junit.Before
    fun reset() =
        eu.advantly.wear
            .InstallationHostFailureTest()
            .reset()

    private class HealthPort {
        lateinit var callback: ExerciseUpdateCallback
        lateinit var executor: Executor
        var clears = 0
        var registrations = 0
        var queries = 0
        var starts = 0
        var ends = 0
        var holdInfo = false
        var holdEnd = false
        var onEnd: (() -> Unit)? = null
        var endThrows: Exception? = null
        var infoCompletion: CallbackToFutureAdapter.Completer<ExerciseInfo>? = null
        var endCompletion: CallbackToFutureAdapter.Completer<Void>? = null
        var pendingEnd: com.google.common.util.concurrent.ListenableFuture<Void>? = null
        var trackedStatus = ExerciseTrackedStatus.OWNED_EXERCISE_IN_PROGRESS
        val client =
            java.lang.reflect.Proxy.newProxyInstance(
                ExerciseClient::class.java.classLoader,
                arrayOf(ExerciseClient::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "setUpdateCallback" -> {
                        registrations++
                        executor = args!![0] as Executor
                        callback = args[1] as ExerciseUpdateCallback
                        callback.onRegistered()
                        null
                    }

                    "clearUpdateCallbackAsync" -> {
                        clears++
                        done<Void>(null)
                    }

                    "endExerciseAsync" -> {
                        ends++
                        onEnd?.invoke()
                        endThrows?.let { throw it }
                        if (holdEnd) {
                            CallbackToFutureAdapter
                                .getFuture<Void> {
                                    endCompletion = it
                                    "held-native-end"
                                }.also { pendingEnd = it }
                        } else {
                            done<Void>(null)
                        }
                    }

                    "startExerciseAsync" -> {
                        starts++
                        done<Void>(null)
                    }

                    "getCurrentExerciseInfoAsync" -> {
                        queries++
                        if (holdInfo) {
                            CallbackToFutureAdapter.getFuture<ExerciseInfo> {
                                infoCompletion = it
                                "held-native-info"
                            }
                        } else {
                            done(ExerciseInfo(trackedStatus, ExerciseType.TENNIS))
                        }
                    }

                    else -> {
                        error("Unexpected Health Services call: ${method.name}")
                    }
                }
            } as ExerciseClient

        private fun <T> done(value: T?) =
            CallbackToFutureAdapter.getFuture<T> {
                it.set(value)
                "controlled-health-result"
            }

        fun update() {
            val update =
                ExerciseUpdate::class.java.constructors
                    .first {
                        it.parameterCount == 10
                    }.newInstance(
                        DataPointContainer(emptyList<androidx.health.services.client.data.DataPoint<*>>()),
                        emptySet<Any>(),
                        emptySet<Any>(),
                        ExerciseStateInfo(ExerciseState.ACTIVE, 0),
                        null,
                        null,
                        java.time.Duration.ZERO,
                        java.time.Instant.EPOCH,
                        java.time.Duration.ZERO,
                        emptySet<Any>(),
                    ) as ExerciseUpdate
            executor.execute { callback.onExerciseUpdateReceived(update) }
        }
    }

    private fun updateCount(service: WorkoutService): Long =
        WorkoutService::class.java
            .getDeclaredField("updateCount")
            .apply { isAccessible = true }
            .getLong(service)

    private fun register(service: WorkoutService) {
        // Echter produktiver Registrierungsweg, kontrollierter Health-Services-Port statt Hardware.
        WorkoutService::class.java.getDeclaredMethod("registerCallback").apply {
            isAccessible = true
            invoke(service)
        }
    }

    private fun readyService(
        register: Boolean = true,
    ): Pair<org.robolectric.android.controller.ServiceController<WorkoutService>, HealthPort> {
        AndroidMatchSessions.get(RuntimeEnvironment.getApplication(), PeerKind.WATCH)
        val service = Robolectric.buildService(WorkoutService::class.java).create()
        val port = HealthPort()
        WorkoutService::class.java.getDeclaredField("exerciseClient").apply {
            isAccessible = true
            set(service.get(), port.client)
        }
        if (register) register(service.get())
        return service to port
    }

    private fun registered() = readyService()

    private fun retry() {
        val app = RuntimeEnvironment.getApplication()
        assertTrue(AndroidInstallationGuard.retry(app) is eu.advantly.data.AndroidInstallationAvailability.Ready)
        AndroidMatchSessions.get(app, PeerKind.WATCH)
    }

    private fun revoke() {
        AndroidInstallationGuard.storageFailed(RuntimeEnvironment.getApplication(), SQLiteDiskIOException("health ownership revoked"))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun deferredStatusCannotEndNewService() {
        val (oldService, oldPort) = registered()
        val app = RuntimeEnvironment.getApplication()
        val installation = AndroidInstallationGuard.get(app)
        val source = requireNotNull(AndroidMatchSessions.current()).deviceId
        val marker =
            java.io
                .File(app.noBackupFilesDir, "installation-v1")
                .readBytes()
                .toList()
        val store = SessionStore(app)
        store.sessionId = "original-workout"
        store.sessionActive = true
        oldPort.holdInfo = true
        revoke()
        assertNotNull(oldPort.infoCompletion)
        oldService.destroy()
        retry()
        val (newService, newPort) = registered()
        assertEquals(1, newPort.registrations)
        oldPort.infoCompletion!!.set(ExerciseInfo(ExerciseTrackedStatus.OWNED_EXERCISE_IN_PROGRESS, ExerciseType.TENNIS))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, oldPort.ends)
        newPort.update()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1L, updateCount(newService.get()))
        assertEquals(source, requireNotNull(AndroidMatchSessions.current()).deviceId)
        assertEquals(installation, AndroidInstallationGuard.get(app))
        assertEquals(
            marker,
            java.io
                .File(app.noBackupFilesDir, "installation-v1")
                .readBytes()
                .toList(),
        )
        assertTrue(store.sessionActive)
        assertEquals("original-workout", store.sessionId)
        newService.destroy()
    }

    @Test
    fun deferredStatusWithoutTakeoverStillEndsOwned() {
        val (service, port) = registered()
        port.holdInfo = true
        revoke()
        service.destroy()
        assertEquals(0, port.ends)
        port.infoCompletion!!.set(ExerciseInfo(ExerciseTrackedStatus.OWNED_EXERCISE_IN_PROGRESS, ExerciseType.TENNIS))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, port.ends)
    }

    private fun seedRecoveryMatch() {
        val app = RuntimeEnvironment.getApplication()
        val holder =
            eu.advantly.wear.sync.WearSessions
                .get(app)
        val self =
            holder.session.data.players
                .ensureSelf("Watch", 0) {
                    eu.advantly.core.model
                        .PlayerId("watch-self")
                }.id
        val match =
            holder.session.create(
                eu.advantly.core.scoring.Presets.TENNIS_SINGLES_BO3
                    .format(),
                eu.advantly.core.model
                    .Lineup(
                        listOf(self),
                        listOf(
                            eu.advantly.core.model
                                .PlayerId("opponent"),
                        ),
                        self,
                    ),
            )
        holder.prefs.openMatchId = match
        SessionStore(app).apply {
            sessionActive = true
            endRequested = false
        }
        shadowOf(app).grantPermissions(
            android.Manifest.permission.BODY_SENSORS,
            android.Manifest.permission.ACTIVITY_RECOGNITION,
            android.Manifest.permission.POST_NOTIFICATIONS,
        )
        @Suppress("UNCHECKED_CAST")
        val cached =
            CapabilitiesRepository::class.java
                .getDeclaredField("mutable")
                .apply { isAccessible = true }
                .get(CapabilitiesRepository) as kotlinx.coroutines.flow.MutableStateFlow<ExerciseCapabilitiesView?>
        cached.value =
            object : ExerciseCapabilitiesView {
                override val supportedTypes = setOf(WatchExerciseType.TENNIS)

                override fun supportedMetrics(type: WatchExerciseType) = emptySet<WorkoutMetric>()
            }
    }

    private fun pendingEndThenRecovery(failed: Boolean) {
        val (oldService, oldPort) = registered()
        seedRecoveryMatch()
        oldPort.holdEnd = true
        revoke()
        assertEquals(1, oldPort.ends)
        oldService.destroy()
        assertTrue(!oldPort.pendingEnd!!.isCancelled)
        retry()
        val (newService, newPort) = readyService(register = false)
        newPort.trackedStatus =
            if (failed) ExerciseTrackedStatus.OWNED_EXERCISE_IN_PROGRESS else ExerciseTrackedStatus.NO_EXERCISE_IN_PROGRESS
        newService.get().onStartCommand(android.content.Intent(WorkoutService.ACTION_RECOVER), 0, 1)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, newPort.registrations)
        assertEquals(0, newPort.queries)
        assertEquals(0, newPort.starts)
        if (failed) oldPort.endCompletion!!.setException(java.io.IOException("native end failed")) else oldPort.endCompletion!!.set(null)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(newPort.queries > 0)
        assertEquals(1, newPort.registrations)
        assertEquals(if (failed) 0 else 1, newPort.starts)
        newService.destroy()
    }

    @Test fun pendingEndBlocksRecoveryUntilSuccess() = pendingEndThenRecovery(failed = false)

    @Test fun failedEndRequiresFreshHealthStatus() = pendingEndThenRecovery(failed = true)

    @Test
    fun reentrantNewServiceWaitsForIssuedEnd() {
        val (oldService, oldPort) = registered()
        oldPort.holdEnd = true
        var next: Pair<org.robolectric.android.controller.ServiceController<WorkoutService>, HealthPort>? = null
        oldPort.onEnd = {
            // Native Endoperation hat noch nicht einmal ihr Future zurueckgegeben.
            retry()
            next = readyService()
            assertEquals(0, next!!.second.registrations)
        }
        revoke()
        oldService.destroy()
        assertEquals(1, oldPort.ends)
        oldPort.endCompletion!!.set(null)
        shadowOf(Looper.getMainLooper()).idle()
        register(next!!.first.get())
        assertEquals(1, next!!.second.registrations)
        next!!.first.destroy()
    }

    @Test
    fun regularEndSurvivesWaiterCancellation() {
        val (oldService, oldPort) = registered()
        seedRecoveryMatch()
        oldPort.holdEnd = true
        oldService.get().onStartCommand(android.content.Intent(WorkoutService.ACTION_STOP), 0, 1)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, oldPort.ends)
        revoke()
        oldService.destroy()
        assertFalse(oldPort.pendingEnd!!.isCancelled)
        retry()
        val (nextService, nextPort) = readyService(register = false)
        nextPort.trackedStatus = ExerciseTrackedStatus.NO_EXERCISE_IN_PROGRESS
        nextService.get().onStartCommand(android.content.Intent(WorkoutService.ACTION_RECOVER), 0, 1)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, nextPort.queries)
        oldPort.endCompletion!!.set(null)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(nextPort.queries > 0)
        assertEquals(1, oldPort.ends)
        assertFalse(SessionStore(RuntimeEnvironment.getApplication()).sessionActive)
        nextService.destroy()
    }

    @Test
    fun unavailableServiceCannotStealAbortLease() {
        val (oldService, oldPort) = registered()
        oldPort.holdInfo = true
        revoke()
        oldService.destroy()
        val unavailable = Robolectric.buildService(WorkoutService::class.java).create()
        oldPort.infoCompletion!!.set(ExerciseInfo(ExerciseTrackedStatus.OWNED_EXERCISE_IN_PROGRESS, ExerciseType.TENNIS))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, oldPort.ends)
        unavailable.destroy()
    }

    private fun reentrantTerminalEnd(error: Exception?) {
        val (oldService, oldPort) = registered()
        seedRecoveryMatch()
        oldPort.endThrows = error
        var next: Pair<org.robolectric.android.controller.ServiceController<WorkoutService>, HealthPort>? = null
        var queriesDuringNativeCall = -1
        oldPort.onEnd = {
            retry()
            next = readyService(register = false)
            next!!.first.get().onStartCommand(android.content.Intent(WorkoutService.ACTION_RECOVER), 0, 1)
            queriesDuringNativeCall = next!!.second.queries
        }
        revoke()
        oldService.destroy()
        assertEquals(0, queriesDuringNativeCall)
        assertTrue(next!!.second.queries > 0)
        assertEquals(1, next!!.second.registrations)
        assertEquals(0, next!!.second.starts)
        next!!.first.destroy()
    }

    @Test fun reentrantImmediateEndCompletion() = reentrantTerminalEnd(null)

    @Test fun reentrantSynchronousEndFailure() = reentrantTerminalEnd(java.io.IOException("native end threw synchronously"))

    @Test
    fun healthyStopStillConfirmsAndClearsMarker() {
        val (service, port) = registered()
        seedRecoveryMatch()
        port.onEnd = { port.trackedStatus = ExerciseTrackedStatus.NO_EXERCISE_IN_PROGRESS }
        service.get().onStartCommand(android.content.Intent(WorkoutService.ACTION_STOP), 0, 1)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        assertEquals(1, port.ends)
        assertFalse(SessionStore(RuntimeEnvironment.getApplication()).sessionActive)
        assertFalse(SessionStore(RuntimeEnvironment.getApplication()).endRequested)
        service.destroy()
    }

    @Test
    fun revokedCallbackStopsOwnedExercise() {
        val (service, port) = registered()
        val app = RuntimeEnvironment.getApplication()
        val store = SessionStore(app)
        store.sessionActive = true
        store.sessionId = "retained-workout"
        store.endRequested = true
        val marker =
            java.io
                .File(app.noBackupFilesDir, "installation-v1")
                .readBytes()
                .toList()
        WorkoutStateHolder.update { it.copy(heartRateBpm = 123, caloriesKcal = 12.0, steps = 456) }
        // Bereits eingereihter echter Callback muss vor dem spaeteren Mainthread-Abbau inert bleiben.
        port.update()
        assertEquals(0L, updateCount(service.get()))
        val failureThread = Thread { AndroidInstallationGuard.storageFailed(app, SQLiteDiskIOException("runtime storage revoked")) }
        failureThread.start()
        failureThread.join()
        val failure =
            runCatching {
                port.update()
                shadowOf(Looper.getMainLooper()).idle()
            }.exceptionOrNull()
        assertNull(failure)
        assertEquals(0L, updateCount(service.get()))
        assertEquals(1, port.ends)
        assertTrue(port.clears > 0)
        assertTrue(store.sessionActive)
        assertTrue(store.endRequested)
        assertEquals("retained-workout", store.sessionId)
        assertEquals(
            marker,
            java.io
                .File(app.noBackupFilesDir, "installation-v1")
                .readBytes()
                .toList(),
        )
        assertNull(WorkoutStateHolder.state.value.heartRateBpm)
        assertNull(WorkoutStateHolder.state.value.steps)
        assertNull(WorkoutStateHolder.state.value.caloriesKcal)
        service.destroy()
    }

    @Test
    fun revocationDoesNotEndForeignExercise() {
        val (service, port) = registered()
        port.trackedStatus = ExerciseTrackedStatus.OTHER_APP_IN_PROGRESS
        AndroidInstallationGuard.storageFailed(RuntimeEnvironment.getApplication(), SQLiteDiskIOException("storage revoked"))
        port.update()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, port.ends)
        assertEquals(1, port.clears)
        service.destroy()
    }

    @Test
    fun healthyRegisteredCallbackStillProcessesUpdates() {
        val (service, port) = registered()
        port.update()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1L, updateCount(service.get()))
        assertEquals(0, port.ends)
        assertNotNull(AndroidMatchSessions.current())
        service.destroy()
    }

    @Test
    fun destroyedServiceRejectsLatePreviouslyRegisteredCallback() {
        val (service, port) = registered()
        service.destroy()
        AndroidInstallationGuard.storageFailed(RuntimeEnvironment.getApplication(), SQLiteDiskIOException("after service destruction"))
        val failure =
            runCatching {
                port.update()
                shadowOf(Looper.getMainLooper()).idle()
            }.exceptionOrNull()
        assertNull(failure)
        assertEquals(0, port.ends)
    }
}
