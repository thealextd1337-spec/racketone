package eu.advantly.android

import android.app.Application
import eu.advantly.match.session.DeviceStatus
import eu.advantly.sync.protocol.PeerKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [UnsupportedDirectorySyncLinux::class])
class WatchAppInstallerTest {
    private val withApp = DeviceStatus("pixel", PeerKind.WATCH, name = "Pixel", appInstalled = true)
    private val withoutApp = DeviceStatus("galaxy", PeerKind.WATCH, name = "Galaxy", appInstalled = false)

    @Test
    fun noWatchMeansNoHint() = assertEquals(emptyList<DeviceStatus>(), watchInstallTargets(emptyList(), emptySet()))

    @Test
    fun watchWithAppOrUnknownStateMeansNoHint() =
        assertEquals(
            emptyList<DeviceStatus>(),
            watchInstallTargets(listOf(withApp, DeviceStatus("seen", PeerKind.WATCH, appInstalled = null)), emptySet()),
        )

    @Test
    fun watchWithoutAppGetsHint() = assertEquals(listOf(withoutApp), watchInstallTargets(listOf(withoutApp), emptySet()))

    @Test
    fun onlyWatchesWithoutAppAmongSeveral() {
        val second = DeviceStatus("fossil", PeerKind.WATCH, appInstalled = false)
        assertEquals(listOf(withoutApp, second), watchInstallTargets(listOf(withApp, withoutApp, second), emptySet()))
    }

    @Test
    fun dismissedWatchIsHiddenOthersStay() {
        val second = DeviceStatus("fossil", PeerKind.WATCH, appInstalled = false)
        assertEquals(listOf(second), watchInstallTargets(listOf(withoutApp, second), setOf("galaxy")))
    }

    @Test
    fun phonesNeverGetHint() =
        assertEquals(
            emptyList<DeviceStatus>(),
            watchInstallTargets(listOf(DeviceStatus("other", PeerKind.PHONE, appInstalled = false)), emptySet()),
        )

    @Test
    fun dismissIsRememberedAcrossAppStarts() {
        val context = RuntimeEnvironment.getApplication()
        val devices = MutableStateFlow(listOf(withoutApp))
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            WatchAppInstaller(context, devices, scope).dismiss("galaxy")
            assertEquals(emptyList<DeviceStatus>(), WatchAppInstaller(context, devices, scope).targets.value)
        } finally {
            scope.cancel()
        }
    }
}
