package eu.advantly.android

import android.app.Activity
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import eu.advantly.data.AndroidInstallationAvailability
import eu.advantly.data.AndroidInstallationGuard

internal const val INSTALLATION_RETRY_COMPLETE = "installation_retry_complete"

/** Ohne Session, Konto oder Cache: ein Speicherfehler bietet nur dieselbe Prüfung erneut an. */
internal fun Activity.showInstallationError() {
    val content =
        LinearLayout(this).apply {
            tag = "installation_error"
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val padding = (32 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
    content.addView(
        TextView(this).apply {
            setText(R.string.installation_error_title)
            textSize = 20f
            isAccessibilityHeading = true
        },
    )
    content.addView(TextView(this).apply { setText(R.string.installation_error_body) })
    content.addView(
        Button(this).apply {
            tag = "installation_retry"
            setText(R.string.installation_error_retry)
            setOnClickListener {
                isEnabled = false
                // Kopie und quick_check nicht auf dem Main-Thread; der Knopf bleibt bis zum Ergebnis gesperrt.
                AndroidInstallationGuard.retryAsync(this@showInstallationError) { availability ->
                    if (isDestroyed) return@retryAsync
                    val ready = availability is AndroidInstallationAvailability.Ready
                    val bootstrapped = ready && ((application as? AdvantlyPhoneApplication)?.bootstrap() != false)
                    if (bootstrapped) {
                        intent.putExtra(INSTALLATION_RETRY_COMPLETE, true)
                        recreate()
                    } else {
                        isEnabled = true
                        content.announceForAccessibility(getString(R.string.installation_error_body))
                    }
                }
            }
        },
    )
    setContentView(ScrollView(this).apply { addView(content) })
}

/** Bis die Installationsprüfung im Hintergrund fertig ist: nur ein Ladezustand, keine Session und kein Konto. */
internal fun Activity.showInstallationLoading() {
    val content =
        LinearLayout(this).apply {
            tag = "installation_loading"
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
    content.addView(ProgressBar(this).apply { isIndeterminate = true })
    content.addView(TextView(this).apply { setText(R.string.installation_loading) })
    setContentView(content)
}
