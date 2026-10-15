package eu.advantly.umbrella.watch

// jvm() existiert nur für Tests auf Windows/Linux
internal actual fun platformWatchPrefs(): WatchPrefs = InMemoryWatchPrefs()

internal actual fun platformNowMs(): Long = System.currentTimeMillis()
