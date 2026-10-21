package eu.advantly.wear

import android.app.Activity
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
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
                val ready = AndroidInstallationGuard.retry(this@showInstallationError) is AndroidInstallationAvailability.Ready
                val bootstrapped = ready && ((application as? AdvantlyWearApplication)?.bootstrap() != false)
                if (bootstrapped) {
                    intent.putExtra(INSTALLATION_RETRY_COMPLETE, true)
                    recreate()
                } else {
                    isEnabled = true
                    content.announceForAccessibility(getString(R.string.installation_error_body))
                }
            }
        },
    )
    setContentView(ScrollView(this).apply { addView(content) })
}
