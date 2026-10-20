package eu.advantly.android

import android.app.Application
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
        val session = AndroidMatchSessions.get(this, PeerKind.PHONE)
        // „Gar nicht“ (P2-J) leert den flüchtigen Telemetrie-Eingang prozessweit – auch ohne laufende Activity
        LiveTelemetryStore.Phone.clearWhenTelemetryOff(session)
        bootstrapped = true
        return true
    }
}
