package eu.advantly.ui.phone.friends

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceGray
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreImage.CIContext
import platform.CoreImage.CIFilter
import platform.CoreImage.createCGImage
import platform.CoreImage.filterWithName
import platform.Foundation.NSData
import platform.Foundation.create
import platform.Foundation.setValue

/**
 * CoreImage `CIQRCodeGenerator` (Fehlerkorrektur M) liefert ein Bild mit einem Pixel je Modul. Das Raster entsteht aus
 * einer 8-Bit-Graustufen-Kopie; die Ruhezone des Generators fällt über die Begrenzung der dunklen Module weg (die drei
 * Suchmuster reichen bis an den Rand des Symbols). Ausgeführt nur in der macOS-CI bzw. auf Geräten.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal actual fun qrModules(text: String): QrMatrix {
    val bytes = text.encodeToByteArray()
    val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.convert()) }
    val filter = checkNotNull(CIFilter.filterWithName("CIQRCodeGenerator"))
    filter.setValue(data, forKey = "inputMessage")
    filter.setValue("M", forKey = "inputCorrectionLevel")
    val image = checkNotNull(filter.outputImage)
    val width = image.extent.useContents { size.width.toInt() }
    val height = image.extent.useContents { size.height.toInt() }
    val cgImage = checkNotNull(CIContext.contextWithOptions(null).createCGImage(image, fromRect = image.extent))
    val pixels = ByteArray(width * height)
    try {
        pixels.usePinned { pinned ->
            val space = CGColorSpaceCreateDeviceGray()
            val context =
                CGBitmapContextCreate(
                    pinned.addressOf(0),
                    width.convert(),
                    height.convert(),
                    8u,
                    width.convert(),
                    space,
                    CGImageAlphaInfo.kCGImageAlphaNone.value,
                )
            CGContextDrawImage(context, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()), cgImage)
            CGContextRelease(context)
            CGColorSpaceRelease(space)
        }
    } finally {
        CGImageRelease(cgImage)
    }

    fun dark(
        x: Int,
        y: Int,
    ) = (pixels[y * width + x].toInt() and 0xFF) < 128
    val rows = (0 until height).filter { y -> (0 until width).any { x -> dark(x, y) } }
    val columns = (0 until width).filter { x -> (0 until height).any { y -> dark(x, y) } }
    val top = rows.first()
    val left = columns.first()
    val size = rows.last() - top + 1
    check(size == columns.last() - left + 1)
    return QrMatrix(size, BooleanArray(size * size) { dark(left + it % size, top + it / size) })
}
