package eu.advantly.android

import android.app.Application
import eu.advantly.match.session.AndroidMatchSessions
import eu.advantly.sync.protocol.PeerKind

/**
 * Startet die prozessweite Match-Session schon beim Prozessstart (W1-I, Sofort-Backup): Auch wenn das System den
 * Prozess nur für den [eu.advantly.android.sync.PhoneSyncListenerService] startet, sind Datenbank und Link bereit,
 * und die Events der zählenden Uhr werden sofort gespeichert – ohne dass eine Activity läuft.
 */
class AdvantlyPhoneApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidMatchSessions.get(this, PeerKind.PHONE)
    }
}
