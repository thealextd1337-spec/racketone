package eu.advantly.wear.diagnostics

import android.os.Trace
import eu.advantly.match.session.SessionTrace

/**
 * Messabschnitte des Tap-Pfads (Paket 5) als `android.os.Trace`-Sections mit Präfix `advantly:` – sichtbar in Perfetto bzw.
 * `atrace`, wenn die App verfolgt wird. Ohne laufende Aufzeichnung kostet ein Abschnitt nur eine Flag-Abfrage.
 *
 * Abschnitte je Tap (geschachtelt, Main-Thread): `inputUp` (Loslassen bis Ende der Tap-Behandlung) ⊃ `accepted` (Tap nach
 * Entprellung angenommen) ⊃ `commit` (Event dauerhaft gespeichert) → `engine` → `haptic` (Vibration ausgelöst) → `header`
 * → `send` (Kodieren und Senden) → `publish` (Session-Listener) → `feedbackState` (UI-Modell neu). Die sichtbare Anzeige
 * folgt mit dem nächsten Frame (`Choreographer#doFrame`/`DrawFrame` nach `inputUp`).
 *
 * Messen (Release-Build vom Play Store genügt nicht: App-Sections erscheinen nur bei `debuggable` oder `profileable`, also
 * Debug-Build oder ein Release mit `<profileable android:shell="true"/>`):
 * ```
 * adb shell atrace --async_start -a eu.advantly -b 16384 gfx input view
 * # tippen …
 * adb shell atrace --async_stop -o /data/local/tmp/tap.trace && adb pull /data/local/tmp/tap.trace
 * ```
 * In Perfetto (ui.perfetto.dev) öffnen: „Loslassen → Haptik“ = Beginn `advantly:inputUp` bis Beginn `advantly:haptic`
 * (bzw. Vibrator-Slices in `system_server`), „Loslassen → Anzeige“ = Beginn `advantly:inputUp` bis Ende des ersten
 * `DrawFrame` im RenderThread danach.
 */
object AndroidTrace : SessionTrace {
    override fun begin(section: String) = Trace.beginSection("advantly:$section")

    override fun end() = Trace.endSection()
}
