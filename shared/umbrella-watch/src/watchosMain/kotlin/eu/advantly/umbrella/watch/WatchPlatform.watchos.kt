package eu.advantly.umbrella.watch

import platform.Foundation.NSDate
import platform.Foundation.NSUserDefaults
import platform.Foundation.timeIntervalSince1970

internal actual fun platformWatchPrefs(): WatchPrefs = UserDefaultsPrefs

internal actual fun platformNowMs(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

/** Bleibt über App-Neustarts erhalten (App-Container der Uhr). */
private object UserDefaultsPrefs : WatchPrefs {
    private val defaults = NSUserDefaults.standardUserDefaults

    override fun get(key: String): String? = defaults.stringForKey(key)

    override fun set(
        key: String,
        value: String?,
    ) {
        if (value == null) defaults.removeObjectForKey(key) else defaults.setObject(value, forKey = key)
    }
}
