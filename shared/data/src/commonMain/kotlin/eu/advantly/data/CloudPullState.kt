package eu.advantly.data

/** Dauerhafter D1-Stand; ein Metadatencursor bestätigt keine Eventvollständigkeit. */
data class CloudPullProgress(
    val profileId: String,
    val visibilityEpoch: Long?,
    val targetEpoch: Long?,
    val scanRun: Long,
    /** Einzelne Ausführung; schützt lokale Commits gegen eine jüngere Ausführung. */
    val runSerial: Long,
    val cursor: Long,
    val metadataComplete: Boolean,
    /** Bleibt über Budgetabbrüche und Wiederöffnung erhalten; nur eine neue Prüfrunde erhöht diesen Wert. */
    val checkRound: Long,
    val roundActive: Boolean,
    /** Mindestens eine Endseite dieser Runde ist gespeichert; spätere Katalogseiten dürfen mit Eventarbeit wechseln. */
    val roundCatalogReady: Boolean,
    /** Dauerhafte faire Phasenwahl, kein Nachweis eines erfolgreichen Eventempfangs. */
    val eventRequestsSinceMetadata: Long,
)

enum class CloudPullEntityKind { MATCH, PLAYER, FRIENDSHIP }

/** Vollständiger Serverstand bleibt auch bei geschütztem lokalem Inhalt erhalten. */
data class CloudPullSnapshot(
    val profileId: String,
    val kind: CloudPullEntityKind,
    val id: String,
    val serverVersion: Long,
    val json: String,
    val access: String?,
    val accessEpoch: Long?,
    val redactionObserved: Long,
    val redactionApplied: Long,
    val materializedVersion: Long?,
    val seenRun: Long,
    val removalPending: Boolean,
) {
    val redactionPending: Boolean get() = redactionObserved > redactionApplied
}

data class CloudPullMatch(
    val id: String,
    val serverVersion: Long,
    val json: String,
    val access: String,
    val accessEpoch: Long?,
    val redactionEpoch: Long,
    val deleted: Boolean,
    val record: MatchRecord?,
)

data class CloudPullPlayer(
    val id: String,
    val serverVersion: Long,
    val json: String,
    val owned: Boolean,
    val player: LocalPlayer,
)

data class CloudPullFriendship(
    val id: String,
    val serverVersion: Long,
    val json: String,
)

data class CloudPullMetadataPage(
    val matches: List<CloudPullMatch>,
    val players: List<CloudPullPlayer>,
    val friendships: List<CloudPullFriendship>,
    val cursor: Long,
    val hasMore: Boolean,
    val visibilityEpoch: Long,
)

data class CloudPullEventWork(
    val profileId: String,
    val matchId: String,
    val repairing: Boolean,
    val repairRun: Long,
    val cursor: Long,
    /** Faire Versuchsauswahl, ausdrücklich unabhängig von einer erfolgreichen Wasserstandsquittierung. */
    val lastAttempt: Long,
    /** Ausschließlich erfolgreich gegen Empfangsnachweise geprüfte Runde. */
    val checkedRound: Long,
    val failedRound: Long,
    val failure: String?,
    /** Die letzte Eventseite ist atomar gespeichert; ihr Wasserstandsvergleich steht noch aus. */
    val needsVerification: Boolean,
    val materializationPending: Boolean,
)

/** Kein Payloadduplikat und kein Geräte-Ack: nur Empfang in diesem Kontokontext. */
data class CloudPullReceipt(
    val event: StoredEvent,
    val serverSeq: Long,
)

data class CloudPullWatermark(
    val deviceId: String,
    val maxSeq: Long,
    val eventCount: Long,
)

/** Offener Redaktionstransfer (G, E); H und Cursor sind erst nach der ersten authentisierten Seite bekannt. */
data class CloudPullRedactionWork(
    val matchId: String,
    val generation: Long,
    val redactionEpoch: Long,
    val highWatermark: Long?,
    val cursor: RedactionCursor,
)

/** Readiness-Anfrage für (Match, G, E); H = null heißt: Server hält H beim Abruf fest (nur E = 0). */
data class CloudPullReadinessWork(
    val matchId: String,
    val generation: Long,
    val redactionEpoch: Long,
    val highWatermark: Long?,
)

enum class CloudPullReadinessResult { READY, MISSING_EVENTS, GAP, STALE_SNAPSHOT }
