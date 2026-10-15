package eu.advantly.ui.phone.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.advantly.core.model.Sport

/**
 * Einfaches Sport-Symbol (Ball, Schläger, Federball …) in der Sportfarbe. Rein dekorativ – daneben steht immer der
 * Name der Sportart (04 §5.2: Farbe nie allein).
 */
@Composable
fun SportIcon(
    sport: Sport,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    color: Color = sportColor(sport),
) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = Stroke(width = w * 0.08f)
        when (sport) {
            Sport.TENNIS -> {
                drawCircle(color, radius = w * 0.42f)
                val seam =
                    Path().apply {
                        moveTo(w * 0.22f, h * 0.18f)
                        quadraticTo(w * 0.62f, h * 0.5f, w * 0.22f, h * 0.82f)
                        moveTo(w * 0.78f, h * 0.18f)
                        quadraticTo(w * 0.38f, h * 0.5f, w * 0.78f, h * 0.82f)
                    }
                drawPath(seam, Color.Black.copy(alpha = 0.55f), style = Stroke(width = w * 0.06f))
            }

            Sport.PADEL -> {
                drawRoundRect(color, Offset(w * 0.2f, h * 0.06f), Size(w * 0.6f, h * 0.62f), CornerRadius(w * 0.3f))
                drawRect(color, Offset(w * 0.44f, h * 0.62f), Size(w * 0.12f, h * 0.32f))
                for (dx in listOf(0.38f, 0.5f, 0.62f)) {
                    drawCircle(Color.Black.copy(alpha = 0.5f), radius = w * 0.04f, center = Offset(w * dx, h * 0.34f))
                }
            }

            Sport.PICKLEBALL -> {
                drawCircle(color, radius = w * 0.42f)
                for ((dx, dy) in listOf(0.5f to 0.3f, 0.32f to 0.55f, 0.68f to 0.55f, 0.5f to 0.74f)) {
                    drawCircle(Color.Black.copy(alpha = 0.5f), radius = w * 0.06f, center = Offset(w * dx, h * dy))
                }
            }

            Sport.BADMINTON -> {
                val skirt =
                    Path().apply {
                        moveTo(w * 0.22f, h * 0.1f)
                        lineTo(w * 0.78f, h * 0.1f)
                        lineTo(w * 0.6f, h * 0.62f)
                        lineTo(w * 0.4f, h * 0.62f)
                        close()
                    }
                drawPath(skirt, color)
                drawCircle(color, radius = w * 0.16f, center = Offset(w * 0.5f, h * 0.76f))
            }

            Sport.TABLE_TENNIS -> {
                drawCircle(color, radius = w * 0.32f, center = Offset(w * 0.42f, h * 0.4f))
                drawLine(color, Offset(w * 0.6f, h * 0.6f), Offset(w * 0.86f, h * 0.88f), strokeWidth = w * 0.14f)
                drawCircle(color, radius = w * 0.08f, center = Offset(w * 0.82f, h * 0.18f), style = stroke)
            }
        }
    }
}
