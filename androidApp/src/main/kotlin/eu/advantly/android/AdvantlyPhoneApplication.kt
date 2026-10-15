package eu.advantly.android

import android.app.Application

/**
 * Session-Start bei Bedarf durch Activity oder Event-/Queue-Zweig des Sync-Service (W1-I, Sofort-Backup).
 * Ein reiner Telemetrie-Weckruf darf weder Datenbank noch Geräte-Link kalt aufbauen.
 */
class AdvantlyPhoneApplication : Application()
