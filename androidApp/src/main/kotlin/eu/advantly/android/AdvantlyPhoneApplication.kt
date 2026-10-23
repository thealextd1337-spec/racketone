package eu.advantly.android

import android.app.Application
import eu.advantly.android.sync.scheduleSyncAfterMatchEnd
import eu.advantly.data.AndroidInstallationAvailability
import eu.advantly.data.AndroidInstallationGuard
import eu.advantly.match.session.AndroidMatchSessions
import eu.advantly.presentation.live.LiveTelemetryStore
import eu.advantly.presentation.live.clearWhenTelemetryOff
import eu.advantly.sync.protocol.PeerKind

/**
 * Startet die prozessweite Match-Session schon beim Prozessstart (W1-I, Sofort-Backup): Auch wenn das System den
 * Prozess nur für den [eu.advantly.android.sync.PhoneSyncListenerService] startet, sind Datenbank und Link bereit,
 * und die Events der zählenden Uhr werden sofort gespeichert – ohne dass eine Activity läuft. Ebenso prozessweit:
 * Telemetrie-Sichtbarkeit „Gar nicht“ leert den flüchtigen Telemetrie-Eingang sofort.
 */
class AdvantlyPhoneApplication : Application() {
    private var bootstrapped = false

    override fun onCreate() {
        super.onCreate()
        bootstrap()
    }

    /** Nur eine erfolgreich geprüfte Installation darf prozessweite Session und Beobachter starten. */
    fun bootstrap(): Boolean {
        if (AndroidInstallationGuard.check(this) !is AndroidInstallationAvailability.Ready) return false
        if (bootstrapped) return true
        return AndroidInstallationGuard.storage(this) {
            AndroidMatchSessions.prepare(this, PeerKind.PHONE) { session, stage ->
                var removeTelemetry: (() -> Unit)? = null
                var removeSyncTrigger: (() -> Unit)? = null
                stage.onDiscard {
                    bootstrapped = false
                    removeTelemetry?.invoke()
                    removeSyncTrigger?.invoke()
                }
                stage.afterCommit {
                    removeTelemetry = LiveTelemetryStore.Phone.clearWhenTelemetryOff(session)
                    removeSyncTrigger = scheduleSyncAfterMatchEnd(this, session)
                }
            }
            bootstrapped = true
            true
        } == true
    }
}
