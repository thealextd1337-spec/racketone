package eu.advantly.android.auth

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import eu.advantly.cloud.sync.CloudDataExportContext
import eu.advantly.cloud.sync.CloudDataExportPort
import eu.advantly.cloud.sync.CloudDataExportSink
import eu.advantly.cloud.sync.CloudDataExportStatus
import eu.advantly.cloud.sync.CloudException
import eu.advantly.presentation.auth.PhoneExportAction
import eu.advantly.presentation.auth.PhoneExportError
import eu.advantly.presentation.auth.PhoneExportPhase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

internal fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal class ExportClock {
    val wall = AtomicLong(1_000)
    val elapsed = AtomicLong(1_000)
    private val waiters = mutableListOf<CompletableDeferred<Unit>>()
    private val registered = Channel<Unit>(Channel.UNLIMITED)

    suspend fun waitMs(
        @Suppress("UNUSED_PARAMETER") duration: Long,
    ) {
        val waiter = CompletableDeferred<Unit>()
        waiters += waiter
        registered.trySend(Unit)
        try {
            waiter.await()
        } finally {
            waiters -= waiter
        }
    }

    suspend fun advance(
        duration: Long,
        wallDelta: Long = duration,
    ) {
        withTimeout(10_000) { while (waiters.isEmpty()) registered.receive() }
        wall.addAndGet(wallDelta)
        elapsed.addAndGet(duration)
        waiters.toList().forEach { it.complete(Unit) }
    }
}

internal class ExportHold {
    val entered = CompletableDeferred<Unit>()
    val cancelled = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val ended = CompletableDeferred<Unit>()

    suspend fun await() {
        entered.complete(Unit)
        try {
            release.await()
        } finally {
            if (!release.isCompleted) cancelled.complete(Unit)
            withContext(NonCancellable) { release.await() }
            ended.complete(Unit)
        }
    }
}

internal class ExportIoGate : CoroutineDispatcher() {
    var armed = false
    val entered = CompletableDeferred<Unit>()
    private val release = CountDownLatch(1)

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        val hold = armed.also { armed = false }
        Dispatchers.IO.dispatch(context) {
            if (hold) {
                entered.complete(Unit)
                check(release.await(10, TimeUnit.SECONDS))
            }
            block.run()
        }
    }

    fun release() = release.countDown()
}

/** Nur Test-Dispatches: der Owner kann vor der Collector-/Provider-Publication weiterarbeiten. */
internal class ExportSerialDispatcher(
    private val delegate: CoroutineDispatcher,
) : CoroutineDispatcher() {
    private val lock = Any()
    private var paused = false
    private val pending = mutableListOf<Pair<CoroutineContext, Runnable>>()
    val queued = Channel<Unit>(Channel.UNLIMITED)

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        synchronized(lock) {
            if (paused) {
                pending += context to block
                queued.trySend(Unit)
            } else {
                delegate.dispatch(context, block)
            }
        }
    }

    fun pause() = synchronized(lock) { paused = true }

    fun resume() {
        val tasks =
            synchronized(lock) {
                paused = false
                pending.toList().also { pending.clear() }
            }
        tasks.forEach { (context, block) -> delegate.dispatch(context, block) }
    }
}

/** Hält nur den Rückweg des erfassten Exports nach nachgewiesenem Dateiwechsel an. */
private class PromotionGate(
    private val delegate: CoroutineDispatcher,
    private val ioBeforeSuspend: Boolean,
) : CoroutineDispatcher() {
    private val owner = Thread.currentThread()
    private val lock = Any()
    private var paused = false
    private val pending = mutableListOf<Pair<CoroutineContext, Runnable>>()
    private var part: File? = null
    private var promoted: File? = null
    private var expectedAction: Job? = null
    private var watching = false
    private var resultBarrier: CompletableJob? = null
    val moved = CompletableDeferred<Boolean>()
    val queued = CompletableDeferred<Unit>()

    fun arm(
        part: File,
        promoted: File,
        action: Job,
    ) {
        check(Thread.currentThread() === owner)
        check(part.isFile && !promoted.exists())
        synchronized(lock) {
            this.part = part
            this.promoted = promoted
            expectedAction = action
            watching = true
        }
    }

    val io =
        object : CoroutineDispatcher() {
            override fun dispatch(
                context: CoroutineContext,
                block: Runnable,
            ) {
                check(Thread.currentThread() === owner)
                val candidate =
                    synchronized(lock) {
                        if (watching && expectedAction!!.children.any { it === context[Job] }) {
                            requireNotNull(part) to requireNotNull(promoted)
                        } else {
                            null
                        }
                    }
                // Nur dieser withContext-Aufruf erhält ein Testkind; Host-/Cleanupjobs bleiben frei.
                val child = candidate?.let { Job(requireNotNull(context[Job])) }
                val finished = CountDownLatch(1)
                val ownerSuspended = CountDownLatch(if (ioBeforeSuspend) 0 else 1)
                if (!ioBeforeSuspend) {
                    // Läuft erst, nachdem der aktuelle Owner-Aufruf bis zu withContext suspendiert ist.
                    delegate.dispatch(context) { ownerSuspended.countDown() }
                }
                try {
                    Dispatchers.IO.dispatch(context) {
                        var retained = false
                        try {
                            check(ownerSuspended.await(10, TimeUnit.SECONDS))
                            val existedBefore = candidate?.second?.isFile == true
                            block.run()
                            if (candidate != null && !existedBefore && candidate.second.isFile && !candidate.first.exists()) {
                                synchronized(lock) {
                                    if (watching) {
                                        paused = true
                                        watching = false
                                        resultBarrier = child
                                        retained = true
                                    }
                                }
                                if (retained) moved.complete(ioBeforeSuspend)
                            }
                        } finally {
                            if (!retained) child?.complete()
                            finished.countDown()
                        }
                    }
                    // Erzwingt das schnelle IO-Ende vor dispatch-Rückkehr und vor getResult/await.
                    if (ioBeforeSuspend) check(finished.await(10, TimeUnit.SECONDS))
                } catch (failure: Throwable) {
                    child?.complete()
                    throw failure
                }
            }
        }

    suspend fun queueOwnerReturn() {
        withTimeout(10_000) { moved.await() }
        val barrier = synchronized(lock) { requireNotNull(resultBarrier) }
        check(barrier.isActive)
        Dispatchers.IO.dispatch(EmptyCoroutineContext) { barrier.complete() }
        withTimeout(10_000) { queued.await() }
        check(barrier.isCompleted)
    }

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        synchronized(lock) {
            if (paused && context[Job] === expectedAction) {
                pending += context to block
                queued.complete(Unit)
            } else {
                delegate.dispatch(context, block)
            }
        }
    }

    fun resume() {
        val (barrier, tasks) =
            synchronized(lock) {
                paused = false
                watching = false
                resultBarrier to pending.toList().also { pending.clear() }
            }
        // Auch fehlgeschlagene Assertions vor queueOwnerReturn dürfen das Testkind nie zurücklassen.
        barrier?.complete()
        tasks.forEach { (context, block) -> delegate.dispatch(context, block) }
    }
}

internal class TestExportPort : CloudDataExportPort {
    override val context = MutableStateFlow<CloudDataExportContext?>(CloudDataExportContext("alice", 1, 0))
    private val owner = Thread.currentThread()
    var sdkCurrent = true
    val calls = mutableListOf<Pair<Boolean, String>>()
    var bytes = ByteArray(20_000) { (it % 127).toByte() }
    var metadata: suspend (Boolean, String) -> CloudDataExportStatus = { _, id -> ready(id) }
    var stream: suspend (CloudDataExportSink) -> Unit = { sink ->
        for (offset in bytes.indices step 8192) sink.write(bytes, offset, minOf(8192, bytes.size - offset))
    }

    fun ready(id: String) = CloudDataExportStatus.Ready(id, 1_000, 61_000, bytes.size.toLong(), digest(bytes))

    override fun requireCurrent(context: CloudDataExportContext) {
        check(Thread.currentThread() === owner)
        if (!sdkCurrent || context != this.context.value) throw CloudException("cloud_session_changed", true)
    }

    override suspend fun request(
        context: CloudDataExportContext,
        requestId: String,
    ): CloudDataExportStatus {
        requireCurrent(context)
        calls += false to requestId
        return metadata(false, requestId)
    }

    override suspend fun status(
        context: CloudDataExportContext,
        requestId: String,
    ): CloudDataExportStatus {
        requireCurrent(context)
        calls += true to requestId
        return metadata(true, requestId)
    }

    override suspend fun download(
        context: CloudDataExportContext,
        ready: CloudDataExportStatus.Ready,
        sink: CloudDataExportSink,
    ) {
        requireCurrent(context)
        stream(sink)
    }
}

internal class ExportHostFixture(
    scope: CoroutineScope,
    val api: CloudDataExportPort = TestExportPort(),
    io: CoroutineDispatcher = Dispatchers.IO,
    publication: CoroutineDispatcher? = null,
) {
    val application: Context = RuntimeEnvironment.getApplication()
    val clock = ExportClock()
    val parent = SupervisorJob(scope.coroutineContext[Job])
    private val owner = Thread.currentThread()
    val host =
        PhoneExportHost(
            api,
            application,
            CoroutineScope(scope.coroutineContext + parent + (publication ?: EmptyCoroutineContext)),
            clock.wall::get,
            clock.elapsed::get,
            { Thread.currentThread() === owner },
            PrivateExportCache(application, io),
            clock::waitMs,
        )

    suspend fun await(phase: PhoneExportPhase) =
        try {
            withTimeout(10_000) { host.state.first { it.phase == phase && PhoneExportAction.CANCEL !in it.allowedActions } }
        } catch (failure: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Expected $phase, actual ${host.state.value}", failure)
        }

    suspend fun start() {
        withTimeout(10_000) { host.state.first { PhoneExportAction.REQUEST in it.allowedActions } }
        assertEquals(setOf(PhoneExportAction.REQUEST), host.state.value.allowedActions)
    }

    suspend fun local() {
        start()
        host.requestExport()
        await(PhoneExportPhase.READY)
        host.download()
        await(PhoneExportPhase.LOCAL_FILE)
    }

    fun files(): List<File> =
        listOf("account-exports", "account-export-staging").flatMap { name ->
            File(application.cacheDir, name).walkTopDown().filter { it.isFile }.toList()
        }

    suspend fun close() {
        val drained = CompletableDeferred<Unit>()
        host.close { drained.complete(Unit) }
        withTimeout(10_000) { drained.await() }
        parent.cancel()
        assertTrue(files().isEmpty())
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PhoneExportHostTest {
    @Test fun promotionGateHoldsTheOwnerWhenIoFinishesBeforeSuspension() = heldPromotion(true)

    @Test fun promotionGateHoldsTheOwnerWhenIoFinishesAfterSuspension() = heldPromotion(false)

    private fun heldPromotion(ioBeforeSuspend: Boolean) =
        runBlocking {
            val gate = PromotionGate(coroutineContext[ContinuationInterceptor] as CoroutineDispatcher, ioBeforeSuspend)
            val port = TestExportPort()
            val f = ExportHostFixture(this, port, gate.io, gate)
            try {
                f.start()
                port.stream = { sink ->
                    sink.write(port.bytes, 0, port.bytes.size)
                    val part = f.files().single()
                    gate.arm(
                        part,
                        File(f.application.cacheDir, "account-exports/${part.nameWithoutExtension}/advantly-data.zip"),
                        requireNotNull(currentCoroutineContext()[Job]),
                    )
                }
                f.host.requestExport()
                f.await(PhoneExportPhase.READY)
                f.host.download()
                assertEquals(ioBeforeSuspend, withTimeout(10_000) { gate.moved.await() })
                assertEquals("advantly-data.zip", f.files().single().name)
                assertEquals(PhoneExportPhase.DOWNLOADING, f.host.state.value.phase)
                gate.queueOwnerReturn()
                assertEquals("advantly-data.zip", f.files().single().name)
                assertEquals(PhoneExportPhase.DOWNLOADING, f.host.state.value.phase)
            } finally {
                gate.resume()
                f.close()
            }
        }

    @Test fun aPromotionReleasedBeforeTheIntentIsLegitimatelyLocal() =
        runBlocking {
            for (ioBeforeSuspend in listOf(true, false)) {
                val gate = PromotionGate(coroutineContext[ContinuationInterceptor] as CoroutineDispatcher, ioBeforeSuspend)
                val port = TestExportPort()
                val f = ExportHostFixture(this, port, gate.io, gate)
                try {
                    f.start()
                    port.stream = { sink ->
                        sink.write(port.bytes, 0, port.bytes.size)
                        val part = f.files().single()
                        gate.arm(
                            part,
                            File(f.application.cacheDir, "account-exports/${part.nameWithoutExtension}/advantly-data.zip"),
                            requireNotNull(currentCoroutineContext()[Job]),
                        )
                    }
                    f.host.requestExport()
                    f.await(PhoneExportPhase.READY)
                    f.host.download()
                    assertEquals(ioBeforeSuspend, withTimeout(10_000) { gate.moved.await() })
                    gate.queueOwnerReturn()
                    assertEquals(PhoneExportPhase.DOWNLOADING, f.host.state.value.phase)
                    gate.resume()
                    f.await(PhoneExportPhase.LOCAL_FILE)
                    assertEquals("advantly-data.zip", f.files().single().name)
                    assertEquals(digest(port.bytes), digest(f.files().single().readBytes()))
                    assertTrue(PhoneExportAction.SHARE in f.host.state.value.allowedActions)
                    assertEquals(1, port.calls.size)
                    f.host.discard()
                    withTimeout(10_000) { f.host.state.first { PhoneExportAction.REQUEST in it.allowedActions } }
                    assertTrue(f.files().isEmpty())
                } finally {
                    gate.resume()
                    f.close()
                }
            }
        }

    @Test fun closeAndParentCancellationBeforeLazyStartStillDrainOnTheOwner() =
        runBlocking {
            for (transition in listOf("close", "parent", "foreign-parent")) {
                val port = TestExportPort()
                val f = ExportHostFixture(this, port)
                val drained = CompletableDeferred<Unit>()
                val actionField = PhoneExportHost::class.java.getDeclaredField("action").apply { isAccessible = true }
                var triggered = false
                var observer: Job? = null
                try {
                    f.start()
                    observer =
                        launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                            f.host.state.collect { state ->
                                if (!triggered && state.phase == PhoneExportPhase.REQUESTING) {
                                    triggered = true
                                    when (transition) {
                                        "close" -> {
                                            f.host.close { drained.complete(Unit) }
                                        }

                                        "parent" -> {
                                            f.parent.cancel()
                                        }

                                        else -> {
                                            val cancelled = CountDownLatch(1)
                                            Thread {
                                                try {
                                                    f.parent.cancel()
                                                } finally {
                                                    cancelled.countDown()
                                                }
                                            }.start()
                                            assertTrue(cancelled.await(10, TimeUnit.SECONDS))
                                        }
                                    }
                                }
                            }
                        }
                    f.host.requestExport()
                    assertTrue(triggered)
                    assertTrue(port.calls.isEmpty())
                    f.host.close { drained.complete(Unit) }
                    withTimeout(10_000) { drained.await() }
                    assertNull(actionField.get(f.host))
                    assertEquals(PhoneExportPhase.CLOSED, f.host.state.value.phase)
                    assertTrue(
                        f.host.state.value.allowedActions
                            .isEmpty(),
                    )
                    f.host.requestExport()
                    assertTrue(port.calls.isEmpty())
                } finally {
                    observer?.cancel()
                    f.close()
                }
            }
        }

    @Test fun aReentrantReplacementSurvivesThePreviousJobsCompletionAndDoubleClicks() =
        runBlocking {
            val port = TestExportPort()
            val hold = ExportHold()
            val f = ExportHostFixture(this, port)
            val actionField = PhoneExportHost::class.java.getDeclaredField("action").apply { isAccessible = true }
            var previous: Job? = null
            var replacement: Job? = null
            var triggered = false
            var observer: Job? = null
            try {
                f.start()
                port.metadata = { _, id ->
                    if (port.calls.size == 2) hold.await()
                    port.ready(id)
                }
                observer =
                    launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                        f.host.state.collect { state ->
                            if (state.phase == PhoneExportPhase.REQUESTING && previous == null) {
                                previous = actionField.get(f.host) as Job
                            }
                            if (!triggered && state.phase == PhoneExportPhase.READY) {
                                triggered = true
                                assertTrue(previous!!.isCompleted)
                                f.host.discard()
                                f.host.requestExport()
                                replacement = actionField.get(f.host) as Job
                                f.host.requestExport()
                                f.host.retry()
                            }
                        }
                    }
                f.host.requestExport()
                withTimeout(10_000) { hold.entered.await() }
                assertTrue(triggered)
                assertNotNull(replacement)
                assertTrue(previous!!.isCompleted)
                assertFalse(replacement!!.isCompleted)
                assertSame(replacement, actionField.get(f.host))
                assertEquals(2, port.calls.size)
                f.host.requestExport()
                f.host.retry()
                assertEquals(2, port.calls.size)
                hold.release.complete(Unit)
                f.await(PhoneExportPhase.READY)
                assertTrue(replacement.isCompleted)
                assertNull(actionField.get(f.host))
            } finally {
                observer?.cancel()
                hold.release.complete(Unit)
                f.close()
            }
        }

    @Test fun completionWaitsForActionChildrenAfterTheBodyHasReturned() =
        runBlocking {
            for (transition in listOf("complete", "cancel", "discard", "close")) {
                val port = TestExportPort()
                val hold = ExportHold()
                val f = ExportHostFixture(this, port)
                val actionField = PhoneExportHost::class.java.getDeclaredField("action").apply { isAccessible = true }
                try {
                    f.start()
                    port.metadata = { _, id ->
                        CoroutineScope(currentCoroutineContext()).launch(start = CoroutineStart.UNDISPATCHED) { hold.await() }
                        port.ready(id)
                    }
                    f.host.requestExport()
                    withTimeout(10_000) { hold.entered.await() }
                    val running = actionField.get(f.host) as Job?
                    assertNotNull(running)
                    assertFalse(running!!.isCompleted)
                    assertEquals(PhoneExportPhase.REQUESTING, f.host.state.value.phase)
                    f.host.requestExport()
                    assertEquals(1, port.calls.size)
                    val drained = CompletableDeferred<Unit>()
                    when (transition) {
                        "cancel" -> {
                            f.host.cancel()
                        }

                        "discard" -> {
                            f.host.discard()
                        }

                        "close" -> {
                            f.parent.cancel()
                            f.host.close { drained.complete(Unit) }
                        }
                    }
                    if (transition != "complete") withTimeout(10_000) { hold.cancelled.await() }
                    assertFalse(running.isCompleted)
                    assertSame(running, actionField.get(f.host))
                    assertFalse(drained.isCompleted)
                    f.host.requestExport()
                    assertEquals(1, port.calls.size)
                    hold.release.complete(Unit)
                    when (transition) {
                        "discard" -> f.await(PhoneExportPhase.IDLE)
                        "close" -> withTimeout(10_000) { drained.await() }
                        else -> f.await(PhoneExportPhase.READY)
                    }
                    assertTrue(hold.ended.isCompleted)
                    assertTrue(running.isCompleted)
                    assertNull(actionField.get(f.host))
                } finally {
                    hold.release.complete(Unit)
                    f.close()
                }
            }
        }

    @Test fun cancelBeforeLazyStartMustReleaseAction() = probe("cancel")

    @Test fun discardBeforeLazyStartMustReleaseAction() = probe("discard")

    @Test fun contextChangeBeforeLazyStartMustReleaseAction() = probe("context")

    @Test fun contextResetMustNotExposePreviousRequestToReentrantRetry() =
        runBlocking {
            val owner = Thread.currentThread()
            val delegate = coroutineContext[kotlin.coroutines.ContinuationInterceptor] as CoroutineDispatcher
            val immediate =
                object : CoroutineDispatcher() {
                    override fun isDispatchNeeded(context: kotlin.coroutines.CoroutineContext) = Thread.currentThread() !== owner

                    override fun dispatch(
                        context: kotlin.coroutines.CoroutineContext,
                        block: Runnable,
                    ) = delegate.dispatch(context, block)
                }
            val port = TestExportPort()
            val f = ExportHostFixture(this, port, publication = immediate)
            val response = CompletableDeferred<Unit>()
            var triggered = false
            var observer: Job? = null
            try {
                f.start()
                port.metadata = { _, id ->
                    response.await()
                    port.ready(id)
                }
                f.host.requestExport()
                assertEquals(1, port.calls.size)
                observer =
                    launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                        f.host.state.collect { state ->
                            if (!triggered && port.context.value?.profileId == "bob" && PhoneExportAction.RETRY in state.allowedActions) {
                                triggered = true
                                println("RESET exposed $state under ${port.context.value}")
                                f.host.retry()
                            }
                        }
                    }
                port.context.value = CloudDataExportContext("bob", 2, 1)
                println("RESET triggered=$triggered calls=${port.calls} state=${f.host.state.value}")
                assertFalse("Reset published previous account's retry before clearing requestId", triggered)
            } finally {
                observer?.cancel()
                f.close()
            }
        }

    private fun probe(transition: String): Unit =
        runBlocking {
            val port = TestExportPort()
            val f = ExportHostFixture(this, port)
            var triggered = false
            var observer: Job? = null
            try {
                f.start()
                observer =
                    launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                        f.host.state.collect { state ->
                            if (!triggered && state.phase == PhoneExportPhase.REQUESTING) {
                                triggered = true
                                when (transition) {
                                    "cancel" -> {
                                        f.host.cancel()
                                    }

                                    "discard" -> {
                                        f.host.discard()
                                    }

                                    else -> {
                                        port.context.value = CloudDataExportContext("bob", 2, 1)
                                        f.host.refreshStatus()
                                    }
                                }
                            }
                        }
                    }
                f.host.requestExport()
                assertTrue(triggered)
                val field = PhoneExportHost::class.java.getDeclaredField("action").apply { isAccessible = true }
                val action = field.get(f.host) as Job?
                println("REPRO $transition: state=${f.host.state.value}, actionCompleted=${action?.isCompleted}, calls=${port.calls.size}")
                assertTrue(port.calls.isEmpty())
                assertTrue(action == null || action.isCompleted)
                observer.cancelAndJoin()
                f.host.discard()
                assertEquals(
                    "Completed pre-start job still blocks requests after $transition and discard",
                    setOf(PhoneExportAction.REQUEST),
                    f.host.state.value.allowedActions,
                )
                f.host.requestExport()
                f.await(PhoneExportPhase.READY)
            } finally {
                observer?.cancel()
                f.close()
            }
        }

    @Test fun aCompletedRenameCannotPublishAfterCancelDiscardAccountChangeOrClose() =
        runBlocking {
            for (ioBeforeSuspend in listOf(true, false)) {
                for (transition in listOf("cancel", "discard", "account", "close")) {
                    val gate = PromotionGate(coroutineContext[ContinuationInterceptor] as CoroutineDispatcher, ioBeforeSuspend)
                    val port = TestExportPort()
                    val f = ExportHostFixture(this, port, gate.io, gate)
                    var download: Job? = null
                    var observer: Job? = null
                    val published = mutableListOf<PhoneExportPhase>()
                    try {
                        f.start()
                        port.stream = { sink ->
                            sink.write(port.bytes, 0, port.bytes.size)
                            val part = f.files().single()
                            download = requireNotNull(currentCoroutineContext()[Job])
                            gate.arm(
                                part,
                                File(f.application.cacheDir, "account-exports/${part.nameWithoutExtension}/advantly-data.zip"),
                                requireNotNull(download),
                            )
                        }
                        f.host.requestExport()
                        f.await(PhoneExportPhase.READY)
                        f.host.download()
                        assertEquals(ioBeforeSuspend, withTimeout(10_000) { gate.moved.await() })
                        assertEquals("advantly-data.zip", f.files().single().name)
                        assertEquals(PhoneExportPhase.DOWNLOADING, f.host.state.value.phase)
                        gate.queueOwnerReturn()
                        assertEquals("advantly-data.zip", f.files().single().name)
                        assertEquals(PhoneExportPhase.DOWNLOADING, f.host.state.value.phase)
                        observer =
                            launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                                f.host.state.collect { published += it.phase }
                            }
                        val closed = CompletableDeferred<Unit>()
                        when (transition) {
                            "cancel" -> {
                                f.host.cancel()
                            }

                            "discard" -> {
                                f.host.discard()
                            }

                            "account" -> {
                                port.context.value = CloudDataExportContext("bob", 2, 1)
                                f.host.refreshStatus()
                            }

                            else -> {
                                f.parent.cancel()
                                f.host.close { closed.complete(Unit) }
                                assertFalse(closed.isCompleted)
                            }
                        }
                        assertFalse(PhoneExportAction.SHARE in f.host.state.value.allowedActions)
                        gate.resume()
                        when (transition) {
                            "cancel" -> f.await(PhoneExportPhase.READY)
                            "close" -> withTimeout(10_000) { closed.await() }
                            else -> f.await(PhoneExportPhase.IDLE)
                        }
                        assertTrue(f.files().isEmpty())
                        assertTrue(requireNotNull(download).isCompleted)
                        assertFalse(PhoneExportPhase.LOCAL_FILE in published)
                        assertFalse(PhoneExportAction.SHARE in f.host.state.value.allowedActions)
                        assertEquals(1, port.calls.size)
                    } finally {
                        observer?.cancel()
                        gate.resume()
                        f.close()
                    }
                }
            }
        }

    @Test fun partialOutputStaysOutsideProviderRootAndDiscardWaitsBeforeAnotherRequest() =
        runBlocking {
            val port = TestExportPort()
            val hold = ExportHold()
            val f = ExportHostFixture(this, port)
            try {
                f.start()
                port.stream = { sink ->
                    sink.write(port.bytes, 0, 100)
                    hold.await()
                }
                f.host.requestExport()
                f.await(PhoneExportPhase.READY)
                val originalId = port.calls.single().second
                f.host.download()
                withTimeout(10_000) { hold.entered.await() }
                val partial = f.files().single()
                assertEquals("part", partial.extension)
                assertEquals("account-export-staging", partial.parentFile!!.name)
                assertTrue(File(f.application.cacheDir, "account-exports").walkTopDown().none { it.isFile })
                assertFalse(PhoneExportAction.SHARE in f.host.state.value.allowedActions)
                f.host.discard()
                withTimeout(10_000) { hold.cancelled.await() }
                f.host.requestExport()
                assertEquals(1, port.calls.size)
                hold.release.complete(Unit)
                f.await(PhoneExportPhase.IDLE)
                assertTrue(hold.ended.isCompleted)
                assertTrue(f.files().isEmpty())
                f.host.requestExport()
                f.await(PhoneExportPhase.READY)
                assertNotEquals(originalId, port.calls.last().second)
            } finally {
                hold.release.complete(Unit)
                f.close()
            }
        }

    @Test fun requestUnknownRetryStatusAndDiscardKeepOnlyTheLogicalRequestId() =
        runBlocking {
            val port = TestExportPort()
            val f = ExportHostFixture(this, port)
            try {
                f.start()
                port.metadata = { _, _ -> throw CloudException("export_unavailable", false) }
                f.host.requestExport()
                f.await(PhoneExportPhase.OUTCOME_UNKNOWN)
                val id = port.calls.single().second
                assertTrue(id.matches(Regex("[0-9a-f-]{36}")))
                port.metadata = { _, request -> port.ready(request) }
                f.host.retry()
                f.await(PhoneExportPhase.READY)
                f.host.refreshStatus()
                f.await(PhoneExportPhase.READY)
                assertEquals(listOf(false to id, false to id, true to id), port.calls)
                f.host.discard()
                f.await(PhoneExportPhase.IDLE)
                f.host.requestExport()
                f.await(PhoneExportPhase.READY)
                assertNotEquals(id, port.calls.last().second)
                assertFalse(
                    f.host.state.value
                        .toString()
                        .contains(id),
                )
                assertFalse(
                    f.host.state.value
                        .toString()
                        .contains("alice"),
                )
            } finally {
                f.close()
            }
        }

    @Test fun pendingAnd409WaitLocallyAndNeverPollOrInventAPendingRequest() =
        runBlocking {
            for (busy in listOf(false, true)) {
                val port = TestExportPort()
                val f = ExportHostFixture(this, port)
                try {
                    f.start()
                    port.metadata = { _, id -> if (busy) CloudDataExportStatus.Busy(3) else CloudDataExportStatus.Pending(id, 3) }
                    f.host.requestExport()
                    val phase = if (busy) PhoneExportPhase.SERVER_BUSY else PhoneExportPhase.PENDING
                    f.await(phase)
                    val id = port.calls.single().second
                    assertEquals(4_000L, f.host.state.value.retryAtMs)
                    assertEquals(setOf(PhoneExportAction.DISCARD), f.host.state.value.allowedActions)
                    f.host.retry()
                    f.host.refreshStatus()
                    f.host.download()
                    f.host.requestExport()
                    assertEquals(1, port.calls.size)
                    f.clock.advance(3_000, wallDelta = -20_000)
                    withTimeout(10_000) { f.host.state.first { it.retryAtMs == null } }
                    assertEquals(1, port.calls.size)
                    assertEquals(!busy, PhoneExportAction.REFRESH_STATUS in f.host.state.value.allowedActions)
                    port.metadata = { _, request -> port.ready(request) }
                    f.host.retry()
                    f.await(PhoneExportPhase.READY)
                    assertEquals(id, port.calls.last().second)
                } finally {
                    f.close()
                }
            }
        }

    @Test fun cancellationAndReentryWaitForTheActualTransportDrainAndRetainTheId() =
        runBlocking {
            val port = TestExportPort()
            val hold = ExportHold()
            val f = ExportHostFixture(this, port)
            try {
                f.start()
                port.metadata = { _, id ->
                    hold.await()
                    port.ready(id)
                }
                f.host.requestExport()
                withTimeout(10_000) { hold.entered.await() }
                f.host.requestExport()
                f.host.retry()
                f.host.cancel()
                withTimeout(10_000) { hold.cancelled.await() }
                f.host.refreshStatus()
                assertEquals(1, port.calls.size)
                assertEquals(PhoneExportPhase.REQUESTING, f.host.state.value.phase)
                hold.release.complete(Unit)
                f.await(PhoneExportPhase.OUTCOME_UNKNOWN)
                assertTrue(hold.ended.isCompleted)
                port.metadata = { _, id -> port.ready(id) }
                f.host.retry()
                f.await(PhoneExportPhase.READY)
                assertEquals(port.calls.first().second, port.calls.last().second)
            } finally {
                hold.release.complete(Unit)
                f.close()
            }
        }

    @Test fun contextChangesDuringMetadataOrSinkNeverPublishOrLeaveFiles() =
        runBlocking {
            for (stage in listOf("metadata", "sink")) {
                for (transition in listOf("bob", "alice-again", "logout", "delete", "sdk-only")) {
                    val port = TestExportPort()
                    val hold = ExportHold()
                    val f = ExportHostFixture(this, port)
                    try {
                        f.start()
                        if (stage == "metadata") {
                            port.metadata = { _, id ->
                                hold.await()
                                port.ready(id)
                            }
                        } else {
                            port.stream =
                                { sink ->
                                    sink.write(port.bytes, 0, 10)
                                    hold.await()
                                    sink.write(port.bytes, 10, port.bytes.size - 10)
                                }
                        }
                        f.host.requestExport()
                        if (stage == "sink") {
                            f.await(PhoneExportPhase.READY)
                            f.host.download()
                        }
                        withTimeout(10_000) { hold.entered.await() }
                        port.context.value =
                            when (transition) {
                                "bob" -> CloudDataExportContext("bob", 2, 1)
                                "alice-again" -> CloudDataExportContext("alice", 2, 1)
                                "logout" -> null
                                "delete" -> CloudDataExportContext("alice", 1, 1)
                                else -> port.context.value
                            }
                        if (transition == "sdk-only") {
                            port.sdkCurrent = false
                            f.host.refreshStatus()
                        }
                        withTimeout(10_000) { hold.cancelled.await() }
                        hold.release.complete(Unit)
                        f.await(if (transition in listOf("logout", "sdk-only")) PhoneExportPhase.UNAVAILABLE else PhoneExportPhase.IDLE)
                        assertNull(f.host.state.value.byteLength)
                        assertFalse(PhoneExportAction.SHARE in f.host.state.value.allowedActions)
                    } finally {
                        hold.release.complete(Unit)
                        f.close()
                    }
                }
            }
        }

    @Test fun closeAfterParentCancellationWaitsForTransportSinkAndNonCooperativeFileCleanup() =
        runBlocking {
            for (stage in listOf("metadata", "sink", "cleanup")) {
                val port = TestExportPort()
                val hold = ExportHold()
                val io = ExportIoGate()
                val f = ExportHostFixture(this, port, io)
                try {
                    if (stage == "cleanup") {
                        f.local()
                        io.armed = true
                        f.host.discard()
                        withTimeout(10_000) { io.entered.await() }
                    } else {
                        f.start()
                        if (stage == "metadata") {
                            port.metadata = { _, id ->
                                hold.await()
                                port.ready(id)
                            }
                        } else {
                            port.stream = { sink ->
                                sink.write(port.bytes, 0, 5)
                                hold.await()
                            }
                        }
                        f.host.requestExport()
                        if (stage == "sink") {
                            f.await(PhoneExportPhase.READY)
                            f.host.download()
                        }
                        withTimeout(10_000) { hold.entered.await() }
                    }
                    f.parent.cancel()
                    val first = CompletableDeferred<Unit>()
                    val second = CompletableDeferred<Unit>()
                    f.host.close { first.complete(Unit) }
                    f.host.close { second.complete(Unit) }
                    assertEquals(PhoneExportPhase.CLOSED, f.host.state.value.phase)
                    assertTrue(
                        f.host.state.value.allowedActions
                            .isEmpty(),
                    )
                    assertFalse(first.isCompleted)
                    assertFalse(second.isCompleted)
                    f.host.requestExport()
                    f.host.retry()
                    if (stage == "cleanup") io.release() else hold.release.complete(Unit)
                    withTimeout(10_000) {
                        first.await()
                        second.await()
                    }
                    if (stage != "cleanup") assertTrue(hold.ended.isCompleted)
                    assertTrue(f.files().isEmpty())
                    var repeated = false
                    f.host.close { repeated = true }
                    assertTrue(repeated)
                } finally {
                    io.release()
                    hold.release.complete(Unit)
                    f.close()
                }
            }
        }

    @Test fun expiryIsPublishedWithoutNetworkAndWallRollbackCannotExtendSharing() =
        runBlocking {
            val f = ExportHostFixture(this)
            try {
                f.local()
                assertEquals(1, f.files().size)
                f.clock.advance(60_000, wallDelta = -120_000)
                f.await(PhoneExportPhase.FAILED)
                assertEquals(PhoneExportError.EXPIRED, f.host.state.value.error)
                assertEquals(1, (f.api as TestExportPort).calls.size)
                assertFalse(PhoneExportAction.SHARE in f.host.state.value.allowedActions)
            } finally {
                f.close()
            }
        }

    @Test fun rotationAndContextRevocationDuringShareVerificationNeverUseTheOldActivity() =
        runBlocking {
            for (transition in listOf("rotation", "context", "expiry", "tamper", "cancel", "cancel-expiry")) {
                val io = ExportIoGate()
                val f = ExportHostFixture(this, io = io)
                val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
                try {
                    f.local()
                    io.armed = true
                    f.host.share(activity.get())
                    withTimeout(10_000) { io.entered.await() }
                    when (transition) {
                        "rotation" -> {
                            activity.pause().stop().destroy()
                        }

                        "context" -> {
                            (f.api as TestExportPort).context.value = CloudDataExportContext("bob", 2, 1)
                        }

                        "expiry" -> {
                            f.clock.elapsed.addAndGet(60_000)
                        }

                        "cancel" -> {
                            f.host.cancel()
                        }

                        "cancel-expiry" -> {
                            f.host.cancel()
                            f.clock.elapsed.addAndGet(60_000)
                            f.host.refreshStatus()
                        }

                        else -> {
                            f.files().single().writeBytes(ByteArray(20_000))
                        }
                    }
                    io.release()
                    val expected =
                        when (transition) {
                            "context" -> PhoneExportPhase.IDLE
                            "expiry", "tamper", "cancel-expiry" -> PhoneExportPhase.FAILED
                            else -> PhoneExportPhase.LOCAL_FILE
                        }
                    f.await(expected)
                    assertNull(shadowOf(activity.get()).nextStartedActivity)
                    assertTrue(
                        PhoneExportHost::class.java.declaredFields.none { ComponentActivity::class.java.isAssignableFrom(it.type) },
                    )
                } finally {
                    io.release()
                    if (transition != "rotation") activity.pause().stop().destroy()
                    f.close()
                }
            }
        }

    @Test fun stableCloudReasonsMapWithoutRawMessagesAndOffOwnerActionsAreRejected() =
        runBlocking {
            val reasons =
                mapOf(
                    "export_timeout" to PhoneExportError.TIMEOUT,
                    "export_not_authenticated" to PhoneExportError.SESSION_REJECTED,
                    "export_not_found" to PhoneExportError.NOT_FOUND,
                    "export_expired" to PhoneExportError.EXPIRED,
                    "export_too_large" to PhoneExportError.TOO_LARGE,
                    "export_response_invalid" to PhoneExportError.INVALID_RESPONSE,
                )
            for ((reason, expected) in reasons) {
                val port = TestExportPort()
                val f = ExportHostFixture(this, port)
                try {
                    f.start()
                    port.metadata = { _, _ -> throw CloudException(reason, true, IOException("sensitive account/file")) }
                    f.host.requestExport()
                    f.await(if (expected == PhoneExportError.TIMEOUT) PhoneExportPhase.OUTCOME_UNKNOWN else PhoneExportPhase.FAILED)
                    assertEquals(expected, f.host.state.value.error)
                    assertFalse(
                        f.host.state.value
                            .toString()
                            .contains("sensitive"),
                    )
                    withContext(Dispatchers.Default) {
                        assertThrows(IllegalStateException::class.java) { f.host.requestExport() }
                        assertThrows(IllegalStateException::class.java) { f.host.retry() }
                        assertThrows(IllegalStateException::class.java) { f.host.download() }
                        assertThrows(IllegalStateException::class.java) { f.host.discard() }
                        assertThrows(IllegalStateException::class.java) { f.host.close {} }
                    }
                    assertNotNull(port.calls.single())
                } finally {
                    f.close()
                }
            }
        }
}
