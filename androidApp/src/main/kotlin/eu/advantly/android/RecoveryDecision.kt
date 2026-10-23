package eu.advantly.android

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import eu.advantly.cloud.sync.PhoneAuthStorage
import eu.advantly.data.AndroidInstallationGuard
import eu.advantly.data.AndroidInstallationState
import eu.advantly.match.session.MatchSession

private const val RECOVERY_DONE = "recovery_done"

/**
 * Recovery-Paket: ausdrückliche Entscheidung über übernommenen Altbestand. Übernehmen geht nur, solange auf diesem Gerät
 * seit dem Hold nichts gezählt wurde (sonst würden diese Matches unsichtbar). Beide Entscheidungen wirken nach einem
 * Neustart des Prozesses; „Später“ lässt alles gesperrt.
 */
internal fun Activity.showRecoveryDecision(
    installation: AndroidInstallationState,
    session: MatchSession,
) {
    // Speicherfehler zählen wie gezählte Matches: dann wird keine Übernahme angeboten.
    val counted =
        AndroidInstallationGuard.storage(this) {
            session.data.accountIdentities
                .localSources()
                .sumOf { it.eventCount } > 0
        } != false
    val restorable = installation.restorable?.takeUnless { counted }
    val message =
        when {
            installation.restorable == null -> R.string.recovery_notice_body
            counted -> R.string.recovery_blocked_body
            installation.restoreFailed -> R.string.recovery_failed_body
            else -> R.string.recovery_decision_body
        }
    AlertDialog
        .Builder(this)
        .setTitle(R.string.recovery_notice_title)
        .setMessage(message)
        .apply {
            if (restorable != null) {
                setPositiveButton(
                    R.string.recovery_restore,
                ) { _, _ -> decide(restored = true) { AndroidInstallationGuard.requestRestore(this@showRecoveryDecision) } }
            }
            if (installation.restorable != null) {
                setNeutralButton(
                    R.string.recovery_start_fresh,
                ) { _, _ -> decide(restored = false) { AndroidInstallationGuard.keepWithoutRestore(this@showRecoveryDecision) } }
            }
        }.setNegativeButton(R.string.recovery_continue_offline, null)
        .show()
}

/** Nach erfolgreicher Übernahme einmal: Freigabe nur über dasselbe Konto, Uhr neu koppeln. */
internal fun Activity.showRecoveryDoneOnce(installation: AndroidInstallationState) {
    val done = intent.getBooleanExtra(RECOVERY_DONE, false)
    intent.removeExtra(RECOVERY_DONE)
    if (!done || !installation.privateTransportAllowed) return
    AlertDialog
        .Builder(this)
        .setTitle(R.string.recovery_done_title)
        .setMessage(R.string.recovery_done_body)
        .setPositiveButton(android.R.string.ok, null)
        .show()
}

private fun Activity.decide(
    restored: Boolean,
    action: () -> Unit,
) {
    // Alte Sitzung und Installationskennung gehören zur alten Datenbank; ohne nutzbaren Speicher gibt es ohnehin keinen Login.
    runCatching { PhoneAuthStorage(applicationContext).resetForRecovery() }
    // Speicherfehler: nichts umgeschaltet, der Hold bleibt und die Entscheidung wird beim nächsten Start erneut angeboten.
    if (runCatching(action).isFailure) return
    startActivity(
        Intent.makeRestartActivityTask(ComponentName(this, MainActivity::class.java)).putExtra(RECOVERY_DONE, restored),
    )
    // Neuer Prozess: Guard, Session und Link starten auf der entschiedenen Installation.
    Runtime.getRuntime().exit(0)
}
