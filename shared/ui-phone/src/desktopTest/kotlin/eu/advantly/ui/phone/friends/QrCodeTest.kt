package eu.advantly.ui.phone.friends

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import eu.advantly.presentation.friends.InviteLinks
import kotlin.test.Test
import kotlin.test.assertEquals

/** plan-einladungen §6 „QR“: Rundlauf Erzeugen → Lesen mit dem ZXing-Decoder für einen 87-Zeichen-Link. */
class QrCodeTest {
    @Test fun invitationLinkRoundTripsThroughTheDecoder() {
        val link = InviteLinks.link("0123456789abcdef".repeat(4))
        assertEquals(87, link.length)
        val matrix = qrModules(link)
        assertEquals(41, matrix.size, "Version 6 bei Fehlerkorrektur M")
        // Mit Ruhezone (4 Module) und 4 Pixeln je Modul rendern, wie es der Bildschirm zeigt.
        val scale = 4
        val side = (matrix.size + 8) * scale
        val pixels =
            IntArray(side * side) { index ->
                val x = index % side / scale - 4
                val y = index / side / scale - 4
                val dark = x in 0 until matrix.size && y in 0 until matrix.size && matrix[x, y]
                if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        val result = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels))))
        assertEquals(link, result.text)
    }
}
