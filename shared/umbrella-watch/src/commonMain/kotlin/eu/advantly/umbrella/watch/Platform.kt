package eu.advantly.umbrella.watch

import eu.advantly.data.LocalData
import eu.advantly.match.session.MatchSession

/** watchOS: SQLDelight-native-driver auf Datei `advantly.db`; JVM (nur Tests): In-Memory. */
internal expect fun platformLocalData(): LocalData

/**
 * Gestartete Match-Session der Uhr (Gerätart `WATCH`) auf [data]. watchOS: WatchConnectivity-Link aus `device-link`,
 * Main-Queue-Scheduler, Thread-Prüfung; JVM: ohne Link (nur Tests).
 */
internal expect fun platformWatchSession(data: LocalData): MatchSession
