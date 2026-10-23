package eu.advantly.android

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import eu.advantly.cloud.sync.PhoneAuthStorage
import eu.advantly.data.AndroidInstallationGuard
import eu.advantly.data.AndroidInstallationState
import eu.advantly.match.session.MatchSession

private const val RECOVERY_DONE = "recovery_done"

/**
 * Wirkung einer Entscheidung; Tests ersetzen beides (ein Neustart beendet den Prozess).
 *
 * @property resetAuth alte Sitzung und Installationskennung verwerfen: sie gehören zur alten Datenbank
 */
internal class RecoveryEffects(
    val resetAuth: () -> Unit,
    val restart: (restored: Boolean) -> Unit,
)

internal fun Activity.recoveryEffects() =
    RecoveryEffects(
        resetAuth = { PhoneAuthStorage(applicationContext).resetForRecovery() },
        restart = { restored ->
            startActivity(
                Intent.makeRestartActivityTask(ComponentName(this, MainActivity::class.java)).putExtra(RECOVERY_DONE, restored),
            )
            // Neuer Prozess: Guard, Session und Link starten auf der entschiedenen Installation.
            Runtime.getRuntime().exit(0)
        },
    )

/**
 * Recovery-Paket: ausdrückliche Entscheidung über übernommenen Altbestand. Übernehmen ist die Standardwahl und geht nur,
 * solange auf diesem Gerät seit dem Hold nichts gezählt wurde (sonst würden diese Matches unsichtbar). „Ohne sie
 * starten“ braucht eine Bestätigung und lässt sich über [showLaterRestore] umkehren. Beide Entscheidungen wirken nach
 * einem Neustart des Prozesses; „Später“ lässt alles gesperrt.
 */
internal fun Activity.showRecoveryDecision(
    installation: AndroidInstallationState,
    session: MatchSession,
    effects: RecoveryEffects = recoveryEffects(),
) {
    val counted = countedHere(session)
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
                setPositiveButton(R.string.recovery_restore) { _, _ ->
                    decide(true, effects) { AndroidInstallationGuard.requestRestore(this@showRecoveryDecision) }
                }
            }
            if (installation.restorable != null) {
                setNeutralButton(R.string.recovery_start_fresh) { _, _ -> confirmStartFresh(installation, session, effects) }
            }
        }.setNegativeButton(R.string.recovery_continue_offline, null)
        .show()
        .emphasizeRestore()
}

/** Späterer Übernahmeweg (Profil) nach „Ohne sie starten“: dieselbe Sperre wie im Hold. */
internal fun Activity.showLaterRestore(
    session: MatchSession,
    effects: RecoveryEffects = recoveryEffects(),
) {
    val counted = countedHere(session)
    AlertDialog
        .Builder(this)
        .setTitle(R.string.recovery_later_title)
        .setMessage(if (counted) R.string.recovery_blocked_body else R.string.recovery_later_body)
        .apply {
            if (!counted) {
                setPositiveButton(R.string.recovery_restore) { _, _ ->
                    decide(true, effects) { AndroidInstallationGuard.requestRestore(this@showLaterRestore) }
                }
            }
        }.setNegativeButton(android.R.string.cancel, null)
        .show()
        .emphasizeRestore()
}

/** Nach dem Neustart einmal: Übernahme gelungen (Freigabe nur über dasselbe Konto, Uhr neu koppeln) oder gescheitert. */
internal fun Activity.showRecoveryDoneOnce(installation: AndroidInstallationState) {
    val done = intent.getBooleanExtra(RECOVERY_DONE, false)
    intent.removeExtra(RECOVERY_DONE)
    // Im Hold zeigt der Entscheidungsdialog einen Fehlschlag selbst.
    if (!done || !installation.privateTransportAllowed) return
    AlertDialog
        .Builder(this)
        .setTitle(if (installation.restoreFailed) R.string.recovery_later_title else R.string.recovery_done_title)
        .setMessage(if (installation.restoreFailed) R.string.recovery_failed_body else R.string.recovery_done_body)
        .setPositiveButton(android.R.string.ok, null)
        .show()
}

private fun Activity.confirmStartFresh(
    installation: AndroidInstallationState,
    session: MatchSession,
    effects: RecoveryEffects,
) {
    AlertDialog
        .Builder(this)
        .setTitle(R.string.recovery_fresh_confirm_title)
        .setMessage(R.string.recovery_fresh_confirm_body)
        .setPositiveButton(R.string.recovery_start_fresh) { _, _ ->
            decide(false, effects) { AndroidInstallationGuard.keepWithoutRestore(this@confirmStartFresh) }
        }.setNegativeButton(android.R.string.cancel) { _, _ -> showRecoveryDecision(installation, session, effects) }
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
    restored: Boolean,
    effects: RecoveryEffects,
    action: () -> Unit,
) {
    // Scheitert das Verwerfen der alten Sitzung, wird nichts entschieden: alte Kennung + neue Geräte-ID sperrten die Cloud.
    // Scheitert danach die Entscheidung selbst, ist nichts umgeschaltet; nur die Sitzung ist schon weg (neu anmelden).
    if (runCatching(effects.resetAuth).isFailure || runCatching(action).isFailure) {
        AlertDialog
            .Builder(this)
            .setTitle(R.string.recovery_notice_title)
            .setMessage(R.string.recovery_save_failed)
            .setPositiveButton(android.R.string.ok, null)
            .show()
        return
    }
    effects.restart(restored)
}
