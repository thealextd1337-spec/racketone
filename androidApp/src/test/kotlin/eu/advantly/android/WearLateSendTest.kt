package eu.advantly.android

import android.app.Application
import android.os.Looper
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.PutDataRequest
import eu.advantly.device.link.LinkTransport
import eu.advantly.device.link.WearDataLayerTransport
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = Application::class,
    shadows = [HeldMessageClient::class, CountingDataClient::class, ReachableWatchCapability::class, UnsupportedDirectorySyncLinux::class],
)
class WearLateSendTest {
    @Test
    fun runningKeepAliveStopsImmediatelyOnBackgroundStorageFailure() {
        val app = RuntimeEnvironment.getApplication()
        HeldMessageClient.pending = TaskCompletionSource()
        HeldMessageClient.calls = 0
        val session =
            eu.advantly.match.session.AndroidMatchSessions
                .get(app, eu.advantly.sync.protocol.PeerKind.PHONE)
        val self =
            session.data.players
                .ensureSelf("Phone", 0) {
                    eu.advantly.core.model
                        .PlayerId("phone-self")
                }.id

        fun hello() {
            val bytes =
                eu.advantly.sync.protocol.SyncCodec.encode(
                    eu.advantly.sync.protocol.Hello(
                        eu.advantly.core.model
                            .DeviceId("watch-device"),
                        eu.advantly.sync.protocol.PeerKind.WATCH,
                    ),
                )
            eu.advantly.device.link.WearDataLayerInbound.deliverMessage(
                "watch",
                WearDataLayerTransport.MESSAGE_PATH,
                byteArrayOf(0xad.toByte(), 1, 1, 0, 1, 109) + bytes,
            )
            shadowOf(Looper.getMainLooper()).idle()
        }
        hello()
        val match =
            session.create(
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
                scorer = eu.advantly.match.session.ScorerChoice.MyWatch,
            )
        assertEquals(match, session.state.value.watchMatchId)
        val subscription =
            eu.advantly.ui.phone.live
                .AndroidTelemetrySubscription(app, session, match, "watch")
        org.junit.Assert.assertTrue(subscription.keepAlive())
        val sent = HeldMessageClient.calls
        val thread =
            Thread {
                eu.advantly.data.AndroidInstallationGuard.storageFailed(
                    app,
                    android.database.sqlite.SQLiteDiskIOException("runtime revoked"),
                )
            }
        thread.start()
        thread.join()
        // Der UI-Looper wurde seit Widerruf noch nicht abgearbeitet.
        org.junit.Assert.assertFalse(subscription.keepAlive())
        assertEquals(sent, HeldMessageClient.calls)
        subscription.close()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun oldFailureCannotEnqueueIntoRestartedTransport() {
        HeldMessageClient.pending = TaskCompletionSource()
        CountingDataClient.writes = 0
        val old = HeldMessageClient.pending
        val transport = WearDataLayerTransport(RuntimeEnvironment.getApplication())
        val listener =
            object : LinkTransport.Listener {
                override fun onFrame(
                    peer: String,
                    frame: ByteArray,
                    onConsumed: () -> Unit,
                ) = Unit

                override fun onPeersChanged(
                    peers: List<String>,
                    available: Boolean,
                ) = Unit
            }
        var results = 0
        transport.start(listener)
        transport.send("watch", byteArrayOf(1)) { results++ }
        transport.stop()
        HeldMessageClient.pending = TaskCompletionSource()
        transport.start(listener)
        old.setException(IOException("old generation failed late"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, CountingDataClient.writes)
        assertEquals(0, results)
        transport.send("watch", byteArrayOf(2)) { results++ }
        HeldMessageClient.pending.setException(IOException("current live failure"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, CountingDataClient.writes)
        transport.stop()
        CountingDataClient.pending.setException(IOException("late queue failure"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, results)
    }

    @Test
    fun revokedCapabilityBlocksFallbackBeforeDeferredStop() {
        HeldMessageClient.pending = TaskCompletionSource()
        CountingDataClient.writes = 0
        var allowed = true
        val transport = WearDataLayerTransport(RuntimeEnvironment.getApplication(), allowed = { allowed })
        transport.start(
            object : LinkTransport.Listener {
                override fun onFrame(
                    peer: String,
                    frame: ByteArray,
                    onConsumed: () -> Unit,
                ) = Unit

                override fun onPeersChanged(
                    peers: List<String>,
                    available: Boolean,
                ) = Unit
            },
        )
        var results = 0
        transport.send("watch", byteArrayOf(3)) { results++ }
        allowed = false
        HeldMessageClient.pending.setException(IOException("revoked before main cleanup"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, CountingDataClient.writes)
        assertEquals(0, results)
        transport.stop()
    }

    @Test
    fun failedLiveSendAfterStopCannotWriteDurablePrivateQueue() {
        HeldMessageClient.pending = TaskCompletionSource()
        CountingDataClient.writes = 0
        val transport = WearDataLayerTransport(RuntimeEnvironment.getApplication())
        transport.start(
            object : LinkTransport.Listener {
                override fun onFrame(
                    peer: String,
                    frame: ByteArray,
                    onConsumed: () -> Unit,
                ) = Unit

                override fun onPeersChanged(
                    peers: List<String>,
                    available: Boolean,
                ) = Unit
            },
        )
        var results = 0
        transport.send("watch", byteArrayOf(1, 2, 3)) { results++ }
        transport.stop()
        HeldMessageClient.pending.setException(IOException("late live failure"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, CountingDataClient.writes)
        assertEquals(0, results)
    }
}

@Implements(className = "com.google.android.gms.wearable.internal.zzan", isInAndroidSdk = false)
class ReachableWatchCapability {
    @Implementation
    fun getCapability(
        name: String,
        filter: Int,
    ): Task<com.google.android.gms.wearable.CapabilityInfo> =
        com.google.android.gms.tasks.Tasks.forResult(
            object : com.google.android.gms.wearable.CapabilityInfo {
                override fun getName() = name

                override fun getNodes() =
                    setOf(
                        object : com.google.android.gms.wearable.Node {
                            override fun getId() = "watch"

                            override fun getDisplayName() = "Watch"

                            override fun isNearby() = true
                        },
                    )
            },
        )
}

/** Kontrollierter GMS-Taskadapter, kein Nachweis realer Play Services. */
@Implements(className = "com.google.android.gms.wearable.internal.zzhj", isInAndroidSdk = false)
class HeldMessageClient {
    @Implementation
    fun sendMessage(
        peer: String,
        path: String,
        frame: ByteArray,
    ): Task<Int> {
        calls++
        return pending.task
    }

    companion object {
        lateinit var pending: TaskCompletionSource<Int>
        var calls = 0
    }
}

@Implements(className = "com.google.android.gms.wearable.internal.zzdt", isInAndroidSdk = false)
class CountingDataClient {
    @Implementation
    fun putDataItem(request: PutDataRequest): Task<DataItem> {
        writes++
        pending = TaskCompletionSource()
        return pending.task
    }

    companion object {
        var writes = 0
        lateinit var pending: TaskCompletionSource<DataItem>
    }
}
