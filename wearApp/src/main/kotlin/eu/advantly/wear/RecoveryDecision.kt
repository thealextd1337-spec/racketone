package eu.advantly.wear

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import eu.advantly.data.AndroidInstallationGuard
import eu.advantly.data.AndroidInstallationState
import eu.advantly.match.session.MatchSession

/** Neustart nach einer Entscheidung; Tests ersetzen ihn (er beendet den Prozess). */
internal fun Activity.restartForRecovery() {
    startActivity(Intent.makeRestartActivityTask(ComponentName(this, MainActivity::class.java)))
    // Neuer Prozess: Guard, Session und Link starten auf der entschiedenen Installation.
    Runtime.getRuntime().exit(0)
}

/**
 * Recovery-Paket auf der Uhr: ausdrückliche Entscheidung über übernommenen Altbestand (wie am Handy, ohne Konto).
 * Übernehmen ist die Standardwahl; „Neu beginnen“ braucht eine Bestätigung und lässt sich über [showLaterRestore]
 * umkehren. Nach dem Neustart koppelt die Uhr ohne alte Autorität; alte eigene Daten gibt nur das gekoppelte Handy per
 * UID frei.
 */
internal fun Activity.showRecoveryDecision(
    installation: AndroidInstallationState,
    session: MatchSession,
    restart: () -> Unit = { restartForRecovery() },
) {
    val counted = countedHere(session)
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
                setPositiveButton(R.string.recovery_restore) { _, _ ->
                    decide(restart) { AndroidInstallationGuard.requestRestore(this@showRecoveryDecision) }
                }
            }
            if (installation.restorable != null) {
                setNeutralButton(R.string.recovery_start_fresh) { _, _ -> confirmStartFresh(installation, session, restart) }
            }
        }.setNegativeButton(R.string.recovery_continue_offline, null)
        .show()
        .emphasizeRestore()
}

/** Späterer Übernahmeweg (Startbildschirm) nach „Neu beginnen“: dieselbe Sperre wie im Hold. */
internal fun Activity.showLaterRestore(
    session: MatchSession,
    restart: () -> Unit = { restartForRecovery() },
) {
    val counted = countedHere(session)
    AlertDialog
        .Builder(this)
        .setTitle(R.string.recovery_later_title)
        .setMessage(if (counted) R.string.recovery_blocked_body else R.string.recovery_later_body)
        .apply {
            if (!counted) {
                setPositiveButton(R.string.recovery_restore) { _, _ ->
                    decide(restart) { AndroidInstallationGuard.requestRestore(this@showLaterRestore) }
                }
            }
        }.setNegativeButton(android.R.string.cancel, null)
        .show()
        .emphasizeRestore()
}

/** Eine spätere Übernahme ist beim Neustart gescheitert: nichts wurde umgeschaltet. */
internal fun Activity.showLaterRestoreFailed() {
    AlertDialog
        .Builder(this)
        .setTitle(R.string.recovery_later_title)
        .setMessage(R.string.recovery_failed_body)
        .setPositiveButton(android.R.string.ok, null)
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

private fun Activity.confirmStartFresh(
    installation: AndroidInstallationState,
    session: MatchSession,
    restart: () -> Unit,
) {
    AlertDialog
        .Builder(this)
        .setTitle(R.string.recovery_fresh_confirm_title)
        .setMessage(R.string.recovery_fresh_confirm_body)
        .setPositiveButton(R.string.recovery_start_fresh) { _, _ ->
            decide(restart) { AndroidInstallationGuard.keepWithoutRestore(this@confirmStartFresh) }
        }.setNegativeButton(android.R.string.cancel) { _, _ -> showRecoveryDecision(installation, session, restart) }
        .show()
}

// Speicherfehler zählen wie gezählte Matches: dann wird keine Übernahme angeboten.
private fun Activity.countedHere(session: MatchSession) =
    AndroidInstallationGuard.storage(this) {
        session.data.accountIdentities
            .localSources()
            .sumOf { it.eventCount } > 0
    } != false

private fun AlertDialog.emphasizeRestore() {
    getButton(AlertDialog.BUTTON_POSITIVE)?.run {
        typeface = Typeface.DEFAULT_BOLD
        requestFocus()
    }
}

private fun Activity.decide(
    restart: () -> Unit,
    action: () -> Unit,
) {
    // Speicherfehler: nichts umgeschaltet, Hold bzw. bisherige Entscheidung bleiben; der Nutzer sieht den Fehler.
    if (runCatching(action).isFailure) {
        AlertDialog
            .Builder(this)
            .setTitle(R.string.recovery_notice_title)
            .setMessage(R.string.recovery_save_failed)
            .setPositiveButton(android.R.string.ok, null)
            .show()
        return
    }
    restart()
}
