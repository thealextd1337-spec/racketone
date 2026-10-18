package eu.advantly.android.auth

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import eu.advantly.cloud.sync.CloudDataExportContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.coroutines.CoroutineContext

/** Binder wartet ohne Registry-Lock; Auth-Prüfung und Dateifreigabe laufen gemeinsam seriell. */
internal class ExportAccessGateway(
    val context: CloudDataExportContext,
    hostContext: CoroutineContext,
    private val isHostThread: () -> Boolean,
    private val authorize: (CloudDataExportContext) -> Unit,
) {
    private val hostContext = hostContext.minusKey(Job)

    fun <T> onHost(block: () -> T): T {
        if (isHostThread()) return block()
        return runBlocking(hostContext) {
            if (!isHostThread()) throw SecurityException("Export unavailable")
            block()
        }
    }

    fun requireCurrent() {
        if (!isHostThread()) throw SecurityException("Export unavailable")
        try {
            authorize(context)
        } catch (_: Exception) {
            throw SecurityException("Export unavailable")
        }
    }
}

/** Keine Activity-Referenz im langlebigen Host; Grants gehören ausschließlich dieser Ausgabe. */
internal class ExportShare(
    context: Context,
) {
    private val application = context.applicationContext
    private val authority = "${application.packageName}.account.exports"

    fun send(
        activity: ComponentActivity,
        slot: PrivateExportCache.Slot,
        byteLength: Long,
        deadline: ExportDeadline,
        access: ExportAccessGateway,
    ): Boolean {
        if (activity.isFinishing || activity.isDestroyed ||
            !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        ) {
            return false
        }
        if (!deadline.valid()) return false
        val uri = AccountExportProvider.uriForFile(application, slot.file)
        AccountExportProvider.allow(uri, slot.file, byteLength, deadline, access)
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("advantly-data.zip", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        try {
            activity.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (error: RuntimeException) {
            revoke(slot)
            throw error
        }
        return true
    }

    fun revoke(slot: PrivateExportCache.Slot) = revokeFile(slot.file)

    fun revokeFile(file: File) {
        // Auch Dateireste eines vorigen Prozesses können noch bekannte URI-Grants besitzen.
        val uri = runCatching { AccountExportProvider.uriForFile(application, file) }.getOrNull() ?: return
        AccountExportProvider.deny(uri)
        application.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun revokeAll() {
        AccountExportProvider
            .denyAuthority(
                authority,
            ).forEach { application.revokeUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
}

/** Exakte Prozessfreigaben statt URI-Pfadauflösung; alte und fremde Dateien bleiben unerreichbar. */
class AccountExportProvider : FileProvider() {
    private fun <T> permitted(
        uri: Uri,
        block: (File) -> T,
    ): T {
        val snapshot = synchronized(entries) { entries[uri] } ?: throw SecurityException("Export unavailable")
        return snapshot.access.onHost {
            synchronized(entries) {
                if (entries[uri] !== snapshot) throw SecurityException("Export unavailable")
                val root = File(requireNotNull(context).cacheDir, "account-exports")
                if (uri.query != null || uri.fragment != null || !snapshot.deadline.valid() ||
                    !PrivateExportCache.isPromoted(snapshot.file, root) || snapshot.file.length() != snapshot.byteLength ||
                    uriForFile(requireNotNull(context), snapshot.file) != uri
                ) {
                    throw SecurityException("Export unavailable")
                }
                snapshot.access.requireCurrent()
                // requireCurrent kann synchron den Kontokontext und damit denselben Eintrag entziehen.
                if (entries[uri] !== snapshot || !snapshot.deadline.valid()) throw SecurityException("Export unavailable")
                // Kein weiterer Suspend zwischen aktueller Autorisierung und Öffnung/Metadatenzugriff.
                block(snapshot.file)
            }
        }
    }

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Read only")
        return permitted(uri) { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor =
        permitted(uri) { file ->
            val columns =
                (projection?.toList() ?: listOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
                    .filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }
            MatrixCursor(columns.toTypedArray(), 1).apply {
                addRow(columns.map { if (it == OpenableColumns.DISPLAY_NAME) file.name else file.length() })
            }
        }

    override fun getType(uri: Uri): String = permitted(uri) { "application/zip" }

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw SecurityException("Read only")

    private data class Entry(
        val file: File,
        val byteLength: Long,
        val deadline: ExportDeadline,
        val access: ExportAccessGateway,
    )

    internal companion object {
        private val entries = mutableMapOf<Uri, Entry>()

        /** Keine Dateiauflösung aus fremden URI-Pfaden: genau eine bekannte private Slotform. */
        fun uriForFile(
            context: Context,
            file: File,
        ): Uri {
            val root = File(context.applicationContext.cacheDir, "account-exports")
            require(file.parentFile?.parentFile == root && file.name == "advantly-data.zip")
            val id = requireNotNull(file.parentFile).name
            require(id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
            return Uri
                .Builder()
                .scheme("content")
                .authority("${context.applicationContext.packageName}.account.exports")
                .appendPath("account-exports")
                .appendPath(id)
                .appendPath("advantly-data.zip")
                .build()
        }

        fun allow(
            uri: Uri,
            file: File,
            byteLength: Long,
            deadline: ExportDeadline,
            access: ExportAccessGateway,
        ) {
            synchronized(entries) { entries[uri] = Entry(file, byteLength, deadline, access) }
        }

        fun deny(uri: Uri) {
            synchronized(entries) { entries.remove(uri) }
        }

        fun denyAuthority(authority: String): List<Uri> =
            synchronized(entries) {
                entries.keys.filter { it.authority == authority }.also { uris -> uris.forEach(entries::remove) }
            }
    }
}
