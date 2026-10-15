package eu.advantly.wear.sync

/** Ob die Wear-UI gerade sichtbar ist (MainActivity zwischen `onResume` und `onPause`). Nur Main-Thread. */
object UiVisibility {
    var resumed: Boolean = false
}
