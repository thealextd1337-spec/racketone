package eu.advantly.android.auth

import android.content.Context
import android.os.SystemClock
import eu.advantly.cloud.sync.CloudDataExportSink
import eu.advantly.cloud.sync.CloudDataExportStatus
import eu.advantly.presentation.auth.PhoneExportError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID

internal class ExportFileFailure(
    val error: PhoneExportError,
) : IOException()

/** Eine rückwärts gestellte Wanduhr verlängert weder Wartezeit noch lokale Freigabe. */
internal class ExportDeadline(
    val wallAtMs: Long,
    durationMs: Long,
    private val nowMs: () -> Long,
    private val elapsedMs: () -> Long,
) {
    private val startedMs = elapsedMs()
    private val durationMs = durationMs.coerceAtLeast(0)

    fun remainingMs(): Long = minOf(wallAtMs - nowMs(), durationMs - (elapsedMs() - startedMs).coerceAtLeast(0)).coerceAtLeast(0)

    fun valid(): Boolean = remainingMs() > 0
}

/** Ausschließlich private Exportdateien; ein Slot gehört genau einem Host-Versuch. */
internal class PrivateExportCache(
    context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val root = File(context.applicationContext.cacheDir, "account-exports")
    private val staging = File(context.applicationContext.cacheDir, "account-export-staging")

    class Slot internal constructor(
        val part: File,
        val file: File,
    )

    fun slot(): Slot {
        val id = UUID.randomUUID().toString()
        return Slot(File(staging, "$id.part"), File(File(root, id), "advantly-data.zip"))
    }

    suspend fun clear(revoke: (File) -> Unit) =
        withContext(NonCancellable + io) {
            for (directory in listOf(root, staging)) {
                removeTree(directory.toPath()) { if (directory == root) revoke(it.toFile()) }
            }
        }

    suspend fun delete(slot: Slot) =
        withContext(NonCancellable + io) {
            Files.deleteIfExists(slot.part.toPath())
            Files.deleteIfExists(slot.file.toPath())
            Files.deleteIfExists(slot.file.parentFile!!.toPath())
            Unit
        }

    suspend fun receive(
        slot: Slot,
        ready: CloudDataExportStatus.Ready,
        fence: () -> Unit,
        stream: suspend (CloudDataExportSink) -> Unit,
    ) {
        if (ready.byteLength > MAX_BYTES) throw ExportFileFailure(PhoneExportError.TOO_LARGE)
        if (ready.byteLength < 1 || !Regex("[0-9a-fA-F]{64}").matches(ready.sha256)) {
            throw ExportFileFailure(PhoneExportError.INVALID_RESPONSE)
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        // Die Referenz entsteht vor dem rückkehrenden Dispatcherwechsel, auch bei Cancellation.
        var output: java.io.OutputStream? = null
        try {
            fence()
            withContext(io) {
                ensureDirectory(staging)
                output = Files.newOutputStream(slot.part.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).buffered()
            }
            fence()
            var sinkFailure: Exception? = null
            val sink =
                CloudDataExportSink { bytes, offset, length ->
                    try {
                        fence()
                        if (offset < 0 || length < 0 || offset > bytes.size - length) {
                            throw ExportFileFailure(PhoneExportError.INVALID_RESPONSE)
                        }
                        if (count + length > MAX_BYTES) throw ExportFileFailure(PhoneExportError.TOO_LARGE)
                        if (count + length > ready.byteLength) throw ExportFileFailure(PhoneExportError.INTEGRITY)
                        withContext(io) {
                            output!!.write(bytes, offset, length)
                            digest.update(bytes, offset, length)
                        }
                        count += length
                        fence()
                    } catch (failure: Exception) {
                        sinkFailure = failure
                        throw failure
                    }
                }
            try {
                stream(sink)
            } catch (failure: Exception) {
                // Der HTTP-Adapter klassifiziert fremde Sinkfehler als Transportfehler.
                // Die lokale Ursache bleibt hier ohne Rohmeldung erhalten.
                throw sinkFailure ?: failure
            }
            fence()
            val hashMatches =
                withContext(io) {
                    output!!.close()
                    digest.digest().toHex().equals(ready.sha256, ignoreCase = true)
                }
            fence()
            if (count != ready.byteLength || !hashMatches) {
                throw ExportFileFailure(PhoneExportError.INTEGRITY)
            }
            fence()
            withContext(io) {
                ensureDirectory(root)
                ensureDirectory(slot.file.parentFile!!)
                Files.move(slot.part.toPath(), slot.file.toPath(), StandardCopyOption.ATOMIC_MOVE)
            }
            fence()
        } finally {
            withContext(NonCancellable + io) { output?.close() }
        }
    }

    suspend fun verify(
        slot: Slot,
        ready: CloudDataExportStatus.Ready,
    ) = withContext(io) {
        if (!isPromoted(slot.file, root) || slot.file.length() != ready.byteLength || ready.byteLength !in 1..MAX_BYTES) {
            throw ExportFileFailure(PhoneExportError.INTEGRITY)
        }
        val digest = MessageDigest.getInstance("SHA-256")
        slot.file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                total += count
                if (total > ready.byteLength) throw ExportFileFailure(PhoneExportError.INTEGRITY)
                digest.update(buffer, 0, count)
            }
            if (total != ready.byteLength || !digest.digest().toHex().equals(ready.sha256, ignoreCase = true)) {
                throw ExportFileFailure(PhoneExportError.INTEGRITY)
            }
        }
    }

    private fun ensureDirectory(directory: File) {
        Files.createDirectories(directory.toPath())
        if (Files.isSymbolicLink(directory.toPath()) ||
            (directory.parentFile == root && Files.isSymbolicLink(root.toPath()))
        ) {
            throw IOException()
        }
    }

    private fun removeTree(
        path: Path,
        beforeDelete: (Path) -> Unit,
    ) {
        if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        // walkFileTree folgt keinen symbolischen Links, auch nicht bei alten Prozessresten.
        Files.walkFileTree(
            path,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): java.nio.file.FileVisitResult {
                    beforeDelete(file)
                    Files.delete(file)
                    return java.nio.file.FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(
                    dir: Path,
                    exc: IOException?,
                ): java.nio.file.FileVisitResult {
                    if (exc != null) throw exc
                    Files.delete(dir)
                    return java.nio.file.FileVisitResult.CONTINUE
                }
            },
        )
    }

    companion object {
        const val MAX_BYTES = 16_777_216L

        fun isPromoted(
            file: File,
            root: File,
        ): Boolean =
            file.name == "advantly-data.zip" &&
                file.parentFile?.name?.matches(Regex("[0-9a-f-]{36}")) == true &&
                file.parentFile?.parentFile == root &&
                file.canonicalFile == File(File(root.canonicalFile, file.parentFile!!.name), file.name) &&
                !Files.isSymbolicLink(root.toPath()) && !Files.isSymbolicLink(file.parentFile!!.toPath()) && file.isFile

        fun deadline(
            ready: CloudDataExportStatus.Ready,
            nowMs: () -> Long = System::currentTimeMillis,
            elapsedMs: () -> Long = SystemClock::elapsedRealtime,
        ): ExportDeadline =
            ExportDeadline(ready.expiresAtMs, minOf(ready.expiresAtMs - nowMs(), ready.expiresAtMs - ready.exportedAtMs), nowMs, elapsedMs)
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
