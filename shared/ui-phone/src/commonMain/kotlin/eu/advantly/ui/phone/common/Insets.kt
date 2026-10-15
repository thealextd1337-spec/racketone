package eu.advantly.ui.phone.common

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed

/**
 * Sicherer Bereich für Inhalte (Edge-to-Edge): Statusleiste, Navigationsleiste, Display-Ausschnitt und Tastatur
 * (`WindowInsets.safeDrawing` enthält `ime`). Tests und Previews setzen hier simulierte Insets, weil Desktop keine hat.
 */
val LocalSafeInsets = staticCompositionLocalOf<WindowInsets?> { null }

/** Aktuelle Insets für Inhalte (Default: `WindowInsets.safeDrawing`). */
@Composable
fun safeInsets(): WindowInsets = LocalSafeInsets.current ?: WindowInsets.safeDrawing

/**
 * Inhalt aus Status-/Navigationsleiste, Ausschnitt und Tastatur heraushalten. Hintergründe davor setzen (dürfen unter die
 * Leisten reichen), Inhalt danach. Ersetzt `imePadding()` – die Tastatur ist in `safeDrawing` enthalten.
 */
fun Modifier.safeContentPadding(): Modifier = composed { windowInsetsPadding(safeInsets()) }
