package eu.advantly.ui.phone.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.uikit.LocalUIViewController
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.fileHandleForReadingFromURL
import platform.Foundation.writeToURL
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTTypeData
import platform.UniformTypeIdentifiers.UTTypeJSON
import platform.UniformTypeIdentifiers.UTTypeText
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue
import platform.posix.memcpy

/** `UIDocumentPickerViewController`: Export als Kopie an einen gewählten Ort, Import als Kopie in den App-Container. */
@Composable
actual fun rememberDocumentFiles(): DocumentFiles? {
    val host = LocalUIViewController.current
    return remember(host) { IosDocumentFiles(host) }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private class IosDocumentFiles(
    private val host: UIViewController,
) : DocumentFiles {
    // UIKit hält den Delegate nur schwach.
    private var delegate: PickerDelegate? = null

    override fun save(
        fileName: String,
        content: String,
        onDone: (Boolean?) -> Unit,
    ) {
        val url = NSURL.fileURLWithPath(NSTemporaryDirectory() + fileName)
        val bytes = content.encodeToByteArray()
        val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
        if (!data.writeToURL(url, atomically = true)) {
            onDone(false)
            return
        }
        present(UIDocumentPickerViewController(forExportingURLs = listOf(url), asCopy = true)) { picked ->
            // Die Gesundheitsdaten bleiben nicht im temporären Ordner liegen.
            NSFileManager.defaultManager.removeItemAtURL(url, null)
            onDone(picked?.let { true })
        }
    }

    override fun open(
        maxBytes: Int,
        onDone: (Result<ByteArray>?) -> Unit,
    ) {
        // Auch falsch typisierte Ablagen (Text, beliebige Daten) zulassen; den Inhalt prüft `HealthArchive`.
        val types = listOf(UTTypeJSON, UTTypeText, UTTypeData)
        present(UIDocumentPickerViewController(forOpeningContentTypes = types, asCopy = true)) { picked ->
            val url = picked ?: return@present onDone(null)
            // Nicht auf der Main-Queue: Größe aus den Dateiattributen, dann höchstens maxBytes + 1 Byte lesen.
            dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.convert(), 0u)) {
                val result = read(url, maxBytes)
                NSFileManager.defaultManager.removeItemAtURL(url, null)
                dispatch_async(dispatch_get_main_queue()) { onDone(result) }
            }
        }
    }

    private fun present(
        picker: UIDocumentPickerViewController,
        onPicked: (NSURL?) -> Unit,
    ) {
        val callback =
            PickerDelegate { url ->
                delegate = null
                onPicked(url)
            }
        delegate = callback
        picker.delegate = callback
        (host.presentedViewController ?: host).presentViewController(picker, animated = true, completion = null)
    }

    /** Höchstens [maxBytes] + 1 Byte, damit „zu groß“ auch ohne Größenangabe erkennbar bleibt. */
    private fun read(
        url: NSURL,
        maxBytes: Int,
    ): Result<ByteArray> {
        val size =
            url.path
                ?.let { NSFileManager.defaultManager.attributesOfItemAtPath(it, null) }
                ?.get(NSFileSize) as? NSNumber
        if (size != null && size.longLongValue > maxBytes) return Result.failure(FileTooLargeException())
        val handle =
            NSFileHandle.fileHandleForReadingFromURL(url, null) ?: return Result.failure(IllegalStateException("Datei nicht lesbar"))
        val data = handle.readDataUpToLength((maxBytes + 1).convert(), null)
        handle.closeAndReturnError(null)
        data ?: return Result.failure(IllegalStateException("Datei nicht lesbar"))
        val length = minOf(data.length, maxBytes.toULong() + 1u).toInt()
        val bytes = ByteArray(length)
        if (length > 0) bytes.usePinned { memcpy(it.addressOf(0), data.bytes, length.toULong()) }
        return Result.success(bytes)
    }
}

private class PickerDelegate(
    private val onPicked: (NSURL?) -> Unit,
) : NSObject(),
    UIDocumentPickerDelegateProtocol {
    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>,
    ) = onPicked(didPickDocumentsAtURLs.firstOrNull() as? NSURL)

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) = onPicked(null)
}
