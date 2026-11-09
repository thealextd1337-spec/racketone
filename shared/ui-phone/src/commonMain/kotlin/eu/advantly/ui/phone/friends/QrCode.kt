package eu.advantly.ui.phone.friends

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.floor

/** Quadratisches Modulraster eines QR-Codes ohne Ruhezone; `true` = dunkles Modul. */
class QrMatrix(
    val size: Int,
    private val modules: BooleanArray,
) {
    init {
        require(size > 0 && modules.size == size * size)
    }

    operator fun get(
        x: Int,
        y: Int,
    ): Boolean = modules[y * size + x]
}

/**
 * QR-Code für [text] mit Fehlerkorrektur M über die Plattform (plan-einladungen §3.2): Android und Desktop mit ZXing,
 * iOS mit CoreImage `CIQRCodeGenerator`. Kein eigener Encoder.
 */
internal expect fun qrModules(text: String): QrMatrix

/** Ruhezone nach ISO/IEC 18004: vier Module. */
private const val QUIET_ZONE = 4

/**
 * Zeichnet [text] als QR-Code, immer schwarz auf weiß (auch im Dark-Theme, sonst lesen Kameras ihn schlecht), mit
 * Ruhezone und ganzzahliger Modulgröße (keine Haarlinien zwischen Modulen).
 */
@Composable
internal fun QrCode(
    text: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    val matrix = remember(text) { qrModules(text) }
    Canvas(modifier.aspectRatio(1f).semantics { contentDescription = description }) {
        drawRect(Color.White)
        val cells = matrix.size + 2 * QUIET_ZONE
        val cell = floor(size.minDimension / cells).coerceAtLeast(1f)
        val origin = (size.minDimension - cell * matrix.size) / 2
        for (y in 0 until matrix.size) {
            for (x in 0 until matrix.size) {
                if (matrix[x, y]) drawRect(Color.Black, Offset(origin + x * cell, origin + y * cell), Size(cell, cell))
            }
        }
    }
}
