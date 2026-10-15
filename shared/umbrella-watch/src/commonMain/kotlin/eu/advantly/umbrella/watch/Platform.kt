package eu.advantly.umbrella.watch

import eu.advantly.data.EventStore

/** watchOS: SQLDelight-native-driver auf Datei; JVM (nur Tests): In-Memory. */
internal expect fun platformEventStore(): EventStore

/** watchOS: WatchConnectivity; JVM: nicht verfügbar. */
internal expect fun platformLinkDebug(): LinkDebug
