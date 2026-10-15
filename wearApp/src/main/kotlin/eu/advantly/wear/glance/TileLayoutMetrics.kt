package eu.advantly.wear.glance

import kotlin.math.floor
import kotlin.math.min

/** Eingeschriebenes Rechteck auf runden Uhren; Zeilenbudget berücksichtigt die angeforderte Schriftskalierung. */
class TileLayoutMetrics(
    widthDp: Int,
    heightDp: Int,
    round: Boolean,
    fontScale: Float,
) {
    private val scale = fontScale.takeIf { it.isFinite() && it > 0 } ?: 1f
    private val width = widthDp.takeIf { it > 0 } ?: 192
    private val height = heightDp.takeIf { it > 0 } ?: 192
    private val safeSide = min(width, height) * 0.70710677f - 4f
    val contentWidth = if (round) safeSide else width - 16f
    val contentHeight = if (round) safeSide else height - 16f
    private val detailHeight get() = DETAIL_SIZE * LINE_HEIGHT * scale
    val titleLines = if (idleHeight(2, 0) <= contentHeight) 2 else 1
    val setupLines = (0..3).last { idleHeight(titleLines, it) <= contentHeight }
    val liveLines =
        floor((contentHeight - 12f - (SCORE_SIZE + DETAIL_SIZE) * LINE_HEIGHT * scale) / detailHeight)
            .toInt()
            .coerceIn(0, 3)

    fun idleHeight(
        titleLines: Int = this.titleLines,
        setupLines: Int = this.setupLines,
    ): Float {
        val titleHeight = TITLE_SIZE * LINE_HEIGHT * scale * titleLines
        return maxOf(48f, titleHeight + detailHeight * setupLines + 12f) + maxOf(48f, titleHeight + 12f) + 6f
    }

    companion object {
        const val TITLE_SIZE = 14f
        const val SCORE_SIZE = 26f
        const val DETAIL_SIZE = 12f
        const val LINE_HEIGHT = 1.2f
    }
}
