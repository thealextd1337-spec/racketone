package eu.advantly.android.auth

import android.app.Application
import eu.advantly.cloud.sync.CloudDataExportStatus
import eu.advantly.presentation.auth.PhoneExportError
import eu.advantly.presentation.auth.PhoneExportPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PrivateExportCacheTest {
    @Test fun eofDeclaredLengthHashAndIoAreRequiredBeforePromotion() =
        runBlocking {
            for (fault in listOf("hash", "short", "long", "io", "invalid-sha", "zero", "too-large")) {
                val port = TestExportPort()
                val f = ExportHostFixture(this, port)
                try {
                    f.start()
                    port.metadata = { _, id ->
                        val ready = port.ready(id)
                        when (fault) {
                            "hash" -> ready.copy(sha256 = "0".repeat(64))
                            "invalid-sha" -> ready.copy(sha256 = "bad")
                            "zero" -> ready.copy(byteLength = 0)
                            "too-large" -> ready.copy(byteLength = PrivateExportCache.MAX_BYTES + 1)
                            else -> ready
                        }
                    }
                    port.stream = { sink ->
                        when (fault) {
                            "short" -> {
                                sink.write(port.bytes, 0, port.bytes.size - 1)
                            }

                            "long" -> {
                                sink.write(port.bytes, 0, port.bytes.size)
                                sink.write(byteArrayOf(1), 0, 1)
                            }

                            else -> {
                                sink.write(port.bytes, 0, port.bytes.size)
                            }
                        }
                    }
                    f.host.requestExport()
                    if (fault !in listOf("invalid-sha", "zero", "too-large")) {
                        f.await(PhoneExportPhase.READY)
                        if (fault == "io") File(f.application.cacheDir, "account-export-staging").writeText("collision")
                        f.host.download()
                    }
                    f.await(PhoneExportPhase.FAILED)
                    assertEquals(
                        when (fault) {
                            "io" -> PhoneExportError.LOCAL_IO
                            "invalid-sha", "zero" -> PhoneExportError.INVALID_RESPONSE
                            "too-large" -> PhoneExportError.TOO_LARGE
                            else -> PhoneExportError.INTEGRITY
                        },
                        f.host.state.value.error,
                    )
                    assertTrue(f.files().none { it.name == "advantly-data.zip" || it.extension == "part" })
                } finally {
                    f.close()
                }
            }
        }

    @Test fun maximumSizeStreamsAndOneExtraByteIsRejectedWithoutRetainingFrames() =
        runBlocking {
            for (extra in listOf(false, true)) {
                val port = TestExportPort()
                val f = ExportHostFixture(this, port)
                try {
                    f.start()
                    val frame = ByteArray(65_536) { 7 }
                    val expected = MessageDigest.getInstance("SHA-256")
                    repeat(256) { expected.update(frame) }
                    port.metadata = { _, id ->
                        CloudDataExportStatus.Ready(
                            id,
                            1_000,
                            61_000,
                            PrivateExportCache.MAX_BYTES,
                            expected.digest().joinToString("") { "%02x".format(it) },
                        )
                    }
                    port.stream = { sink ->
                        repeat(256) {
                            frame.fill(7)
                            sink.write(frame, 0, frame.size)
                            frame.fill(0)
                        }
                        if (extra) sink.write(frame, 0, 1)
                    }
                    f.host.requestExport()
                    f.await(PhoneExportPhase.READY)
                    f.host.download()
                    f.await(if (extra) PhoneExportPhase.FAILED else PhoneExportPhase.LOCAL_FILE)
                    if (extra) {
                        assertEquals(PhoneExportError.TOO_LARGE, f.host.state.value.error)
                        assertTrue(f.files().isEmpty())
                    } else {
                        val file = f.files().single()
                        assertEquals(PrivateExportCache.MAX_BYTES, file.length())
                        withContext(Dispatchers.IO) {
                            file.inputStream().use { assertEquals(7, it.read()) }
                        }
                        assertFalse(f.files().any { it.extension == "part" })
                    }
                } finally {
                    f.close()
                }
            }
        }

    @Test fun startupDeletesOnlyOwnProcessRemnantsAndNeverResumesNetwork() =
        runBlocking {
            val application = RuntimeEnvironment.getApplication()
            val exported = File(application.cacheDir, "account-exports/11111111-1111-1111-1111-111111111111/advantly-data.zip")
            val partial = File(application.cacheDir, "account-export-staging/old.part")
            exported.parentFile!!.mkdirs()
            partial.parentFile!!.mkdirs()
            exported.writeBytes(byteArrayOf(1, 2, 3))
            partial.writeBytes(byteArrayOf(4, 5))
            val unrelated = File(application.cacheDir, "unrelated-cache")
            unrelated.writeBytes(byteArrayOf(9))
            val port = TestExportPort()
            val f = ExportHostFixture(this, port)
            try {
                f.start()
                assertTrue(f.files().isEmpty())
                assertTrue(port.calls.isEmpty())
                assertArrayEquals(byteArrayOf(9), unrelated.readBytes())
                f.local()
                assertArrayEquals(port.bytes, f.files().single().readBytes())
            } finally {
                f.close()
            }
        }
}
