package eu.advantly.android.auth

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import eu.advantly.cloud.sync.CloudDataExportContext
import eu.advantly.presentation.auth.PhoneExportAction
import eu.advantly.presentation.auth.PhoneExportPhase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.ContinuationInterceptor

class ExportTestApplication : Application() {
    val revoked = mutableListOf<Pair<Uri, Int>>()

    override fun revokeUriPermission(
        uri: Uri,
        modeFlags: Int,
    ) {
        revoked += uri to modeFlags
        super.revokeUriPermission(uri, modeFlags)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ExportTestApplication::class)
class ExportShareTest {
    @Test fun providerRejectsChangedContextBeforeTheHostCollectorCanRevokeTheGrant() =
        runBlocking {
            for (transition in listOf("account", "session", "delete", "logout", "sdk-only")) {
                val dispatcher = ExportSerialDispatcher(coroutineContext[ContinuationInterceptor] as CoroutineDispatcher)
                val port = TestExportPort()
                val f = ExportHostFixture(this, port, publication = dispatcher)
                val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
                try {
                    f.local()
                    f.host.share(activity.get())
                    f.await(PhoneExportPhase.LOCAL_FILE)
                    val uri = AccountExportProvider.uriForFile(f.application, f.files().single())
                    val info = f.application.packageManager.resolveContentProvider(uri.authority!!, PackageManager.GET_META_DATA)!!
                    val provider = Robolectric.buildContentProvider(AccountExportProvider::class.java).create(info).get()
                    provider.openFile(uri, "r").close()
                    dispatcher.pause()
                    when (transition) {
                        "account" -> port.context.value = CloudDataExportContext("bob", 2, 1)
                        "session" -> port.context.value = CloudDataExportContext("alice", 2, 1)
                        "delete" -> port.context.value = CloudDataExportContext("alice", 1, 1)
                        "logout" -> port.context.value = null
                        else -> port.sdkCurrent = false
                    }
                    assertEquals(PhoneExportPhase.LOCAL_FILE, f.host.state.value.phase)
                    assertTrue(PhoneExportAction.SHARE in f.host.state.value.allowedActions)
                    assertThrows(SecurityException::class.java) { provider.openFile(uri, "r").close() }
                    assertThrows(SecurityException::class.java) { provider.query(uri, null, null, null, null).close() }
                    assertThrows(SecurityException::class.java) { provider.getType(uri) }
                    assertThrows(SecurityException::class.java) { provider.openAssetFile(uri, "r")?.close() }
                    assertThrows(SecurityException::class.java) { provider.openTypedAssetFile(uri, "application/zip", null)?.close() }
                } finally {
                    dispatcher.resume()
                    activity.pause().stop().destroy()
                    f.close()
                }
            }
        }

    @Test fun binderWaitDoesNotHoldTheRegistryLockAndCloseDeniesTheQueuedOpen() =
        runBlocking {
            val dispatcher = ExportSerialDispatcher(coroutineContext[ContinuationInterceptor] as CoroutineDispatcher)
            val f = ExportHostFixture(this, publication = dispatcher)
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            try {
                f.local()
                f.host.share(activity.get())
                f.await(PhoneExportPhase.LOCAL_FILE)
                val uri = AccountExportProvider.uriForFile(f.application, f.files().single())
                val info = f.application.packageManager.resolveContentProvider(uri.authority!!, PackageManager.GET_META_DATA)!!
                val provider = Robolectric.buildContentProvider(AccountExportProvider::class.java).create(info).get()
                dispatcher.pause()
                val binder = async(Dispatchers.IO) { runCatching { provider.openFile(uri, "r").use { } } }
                withTimeout(10_000) {
                    select<Unit> {
                        dispatcher.queued.onReceive { }
                        binder.onAwait { throw AssertionError("Provider bypassed the serial authorization context") }
                    }
                }
                assertFalse(binder.isCompleted)
                val drained = CompletableDeferred<Unit>()
                f.parent.cancel()
                f.host.close { drained.complete(Unit) }
                assertFalse(drained.isCompleted)
                assertEquals(PhoneExportPhase.CLOSED, f.host.state.value.phase)
                dispatcher.resume()
                withTimeout(10_000) {
                    assertTrue(binder.await().exceptionOrNull() is SecurityException)
                    drained.await()
                }
                assertTrue(f.files().isEmpty())
            } finally {
                dispatcher.resume()
                activity.pause().stop().destroy()
                f.close()
            }
        }

    @Test fun manifestHasOnlyTheDedicatedPrivateProviderPath() =
        runBlocking {
            val f = ExportHostFixture(this)
            try {
                f.start()
                val info =
                    f.application.packageManager.resolveContentProvider(
                        "${f.application.packageName}.account.exports",
                        PackageManager.GET_META_DATA,
                    )!!
                assertEquals(AccountExportProvider::class.java.name, info.name)
                assertFalse(info.exported)
                assertTrue(info.grantUriPermissions)
                val xml = f.application.resources.getXml(info.metaData.getInt("android.support.FILE_PROVIDER_PATHS"))
                val paths = mutableListOf<Triple<String, String?, String?>>()
                xml.use {
                    while (xml.next() != XmlPullParser.END_DOCUMENT) {
                        if (xml.eventType == XmlPullParser.START_TAG && xml.name != "paths") {
                            paths += Triple(xml.name, xml.getAttributeValue(null, "name"), xml.getAttributeValue(null, "path"))
                        }
                    }
                }
                assertEquals(listOf(Triple("cache-path", "account-exports", "account-exports/")), paths)
            } finally {
                f.close()
            }
        }

    @Test fun onlyPromotedRegisteredFilesAreSharedThroughAnExplicitReadOnlyChooser() =
        runBlocking {
            val f = ExportHostFixture(this)
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            try {
                f.local()
                val file = f.files().single()
                val authority = "${f.application.packageName}.account.exports"
                val info = f.application.packageManager.resolveContentProvider(authority, PackageManager.GET_META_DATA)!!
                val provider = Robolectric.buildContentProvider(AccountExportProvider::class.java).create(info).get()
                val uri = AccountExportProvider.uriForFile(f.application, file)
                assertThrows(SecurityException::class.java) { provider.openFile(uri, "r") }
                val partial = File(f.application.cacheDir, "account-export-staging/test.part")
                assertThrows(IllegalArgumentException::class.java) { AccountExportProvider.uriForFile(f.application, partial) }
                f.host.share(activity.get())
                f.await(PhoneExportPhase.LOCAL_FILE)
                val chooser = shadowOf(activity.get()).nextStartedActivity
                assertNotNull(chooser)
                assertEquals(Intent.ACTION_CHOOSER, chooser.action)
                val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
                assertEquals(Intent.ACTION_SEND, send.action)
                assertEquals("application/zip", send.type)
                assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, send.flags)
                assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, chooser.flags)
                assertEquals(uri, send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
                assertEquals(uri, send.clipData!!.getItemAt(0).uri)
                assertEquals("application/zip", provider.getType(uri))
                provider.openFile(uri, "r").use { descriptor ->
                    assertEquals((f.api as TestExportPort).bytes[0].toInt(), FileInputStream(descriptor.fileDescriptor).read())
                    FileOutputStream(descriptor.fileDescriptor).use { output ->
                        assertThrows(IOException::class.java) { output.write(1) }
                    }
                }
                val binderRead =
                    async(Dispatchers.IO) {
                        provider.openFile(uri, "r").use { FileInputStream(it.fileDescriptor).read() }
                    }
                assertEquals((f.api as TestExportPort).bytes[0].toInt(), withTimeout(10_000) { binderRead.await() })
                provider.openAssetFile(uri, "r")!!.use { assertTrue(it.parcelFileDescriptor.statSize > 0) }
                provider.openTypedAssetFile(uri, "application/zip", null)!!.use { assertTrue(it.parcelFileDescriptor.statSize > 0) }
                provider.openTypedAssetFile(uri, "*/*", null)!!.use { assertTrue(it.parcelFileDescriptor.statSize > 0) }
                provider.query(uri, null, null, null, null).use { assertTrue(it.moveToFirst()) }
                for (mode in listOf("w", "rw", "rwt", "wa")) assertThrows(SecurityException::class.java) { provider.openFile(uri, mode) }
                for (bad in listOf(
                    "$uri/../advantly-data.zip",
                    "$uri?x=1",
                    "content://$authority/account-exports/../secret",
                    "$uri%2f..%2fsecret",
                )) {
                    assertThrows(SecurityException::class.java) { provider.openFile(Uri.parse(bad), "r") }
                }
                assertThrows(SecurityException::class.java) { provider.delete(uri, null, null) }
                f.host.discard()
                withTimeout(10_000) { f.host.state.first { PhoneExportAction.REQUEST in it.allowedActions } }
                assertThrows(SecurityException::class.java) { provider.openFile(uri, "r") }
                assertThrows(SecurityException::class.java) { provider.openAssetFile(uri, "r") }
                assertThrows(SecurityException::class.java) { provider.openTypedAssetFile(uri, "application/zip", null) }
                assertTrue((f.application as ExportTestApplication).revoked.contains(uri to Intent.FLAG_GRANT_READ_URI_PERMISSION))
                assertTrue(f.files().isEmpty())
            } finally {
                activity.pause().stop().destroy()
                f.close()
            }
        }

    @Test fun expiryAccountRevisionAndCloseDenyAllPreviousGrants() =
        runBlocking {
            for (transition in listOf("expiry", "account", "session", "delete", "logout", "close")) {
                val port = TestExportPort()
                val f = ExportHostFixture(this, port)
                val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
                try {
                    f.local()
                    f.host.share(activity.get())
                    f.await(PhoneExportPhase.LOCAL_FILE)
                    val chooser = shadowOf(activity.get()).nextStartedActivity
                    val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
                    val uri = send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)!!
                    val info = f.application.packageManager.resolveContentProvider(uri.authority!!, PackageManager.GET_META_DATA)!!
                    val provider = Robolectric.buildContentProvider(AccountExportProvider::class.java).create(info).get()
                    when (transition) {
                        "expiry" -> f.clock.advance(60_000, -120_000)
                        "account" -> port.context.value = CloudDataExportContext("bob", 2, 1)
                        "session" -> port.context.value = CloudDataExportContext("alice", 2, 1)
                        "delete" -> port.context.value = CloudDataExportContext("alice", 1, 1)
                        "logout" -> port.context.value = null
                        else -> f.close()
                    }
                    if (transition != "close") {
                        withTimeout(10_000) { f.host.state.first { PhoneExportAction.SHARE !in it.allowedActions } }
                    }
                    assertThrows(SecurityException::class.java) { provider.openFile(uri, "r") }
                    assertTrue((f.application as ExportTestApplication).revoked.contains(uri to Intent.FLAG_GRANT_READ_URI_PERMISSION))
                } finally {
                    activity.pause().stop().destroy()
                    f.close()
                }
            }
        }
}
