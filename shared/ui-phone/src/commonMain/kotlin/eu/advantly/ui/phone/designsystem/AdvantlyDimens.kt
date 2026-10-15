package eu.advantly.ui.phone.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** 4-pt-Raster aus 04 §5.5. */
object Spacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 48.dp

    /** Bildschirmrand Handy kompakt / ab 600 dp Breite. */
    val screenEdgeCompact = 16.dp
    val screenEdgeWide = 24.dp

    /** Mindest-Touchfläche Handy. */
    val minTouchTarget = 48.dp
}

/** Radien aus 04 §5.5: 8 Chips/Inputs · 14 Karten (Icon-Rundung 22 %) · 24 Sheets · voll für Pills. */
object Radii {
    val chip = 8.dp
    val card = 14.dp
    val sheet = 24.dp

    /** Live-Score-Panels (Mockup H6). */
    val scorePanel = 22.dp

    val chipShape = RoundedCornerShape(chip)
    val cardShape = RoundedCornerShape(card)
    val sheetShape = RoundedCornerShape(sheet)
    val scorePanelShape = RoundedCornerShape(scorePanel)
    val pillShape = RoundedCornerShape(percent = 50)
}
