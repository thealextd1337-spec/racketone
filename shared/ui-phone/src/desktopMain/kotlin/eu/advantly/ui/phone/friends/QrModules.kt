package eu.advantly.ui.phone.friends

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

internal actual fun qrModules(text: String): QrMatrix {
    val matrix = Encoder.encode(text, ErrorCorrectionLevel.M).matrix
    val size = matrix.width
    return QrMatrix(size, BooleanArray(size * size) { matrix[it % size, it / size].toInt() == 1 })
}
