package eu.advantly.wear

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import eu.advantly.data.AndroidInstallationGuard
import eu.advantly.data.AndroidInstallationState
import eu.advantly.match.session.MatchSession

/**
 * Recovery-Paket auf der Uhr: ausdrückliche Entscheidung über übernommenen Altbestand (wie am Handy, ohne Konto). Nach dem
 * Neustart koppelt die Uhr ohne alte Autorität; alte eigene Daten gibt nur das gekoppelte Handy per UID frei.
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
            if (installation.restorable != null && !counted) {
                setPositiveButton(
                    R.string.recovery_restore,
                ) { _, _ -> decide { AndroidInstallationGuard.requestRestore(this@showRecoveryDecision) } }
            }
            if (installation.restorable != null) {
                setNeutralButton(R.string.recovery_start_fresh) { _, _ ->
                    decide {
                        AndroidInstallationGuard.keepWithoutRestore(this@showRecoveryDecision)
                    }
                }
            }
        }.setNegativeButton(R.string.recovery_continue_offline, null)
        .show()
}

/** Ein vertrautes Handy mit neuer Kopplung wurde abgelehnt: nur auf ausdrücklichen Wunsch neu koppeln. */
internal fun Activity.showPairingRepair(
    session: MatchSession,
    current: () -> Boolean,
) {
    AlertDialog
        .Builder(this)
        .setTitle(R.string.pairing_conflict_title)
        .setMessage(R.string.pairing_conflict_body)
        .setPositiveButton(R.string.pairing_repair) { _, _ -> if (current()) session.repairPairing() }
        .setNegativeButton(R.string.pairing_later, null)
        .show()
}

private fun Activity.decide(action: () -> Unit) {
    // Speicherfehler: nichts umgeschaltet, der Hold bleibt und die Entscheidung wird beim nächsten Start erneut angeboten.
    if (runCatching(action).isFailure) return
    startActivity(Intent.makeRestartActivityTask(ComponentName(this, MainActivity::class.java)))
    // Neuer Prozess: Guard, Session und Link starten auf der entschiedenen Installation.
    Runtime.getRuntime().exit(0)
}
