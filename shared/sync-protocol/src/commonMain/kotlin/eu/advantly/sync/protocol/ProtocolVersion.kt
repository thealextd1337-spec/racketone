package eu.advantly.sync.protocol

/**
 * Protokollversion des Geräte-Syncs (Feld `v` jeder Nachricht, 01 §4.3).
 *
 * Regeln:
 * - Ein Gerät schreibt immer [CURRENT] und liest Nachrichten mit `v` in [MIN_SUPPORTED]..[CURRENT].
 * - `Hello` wird unabhängig von `v` gelesen (nur `deviceId`, `kind`, `v`, `minV` sind nötig), damit beide Seiten die
 *   Kompatibilität feststellen können.
 * - Eine künftige Version N+1 muss gegenüber einem Gerät mit [CURRENT] = N in Version N schreiben (ausgehandelt über
 *   [negotiate]); neue Felder sind für ältere Geräte unsichtbar (`ignoreUnknownKeys`).
 */
object ProtocolVersion {
    /** Version, die dieses Gerät spricht und schreibt. */
    const val CURRENT: Int = 1

    /** Älteste Version, die dieses Gerät noch lesen kann. */
    const val MIN_SUPPORTED: Int = 1

    /** `true`, wenn eine Nachricht mit Version [v] gelesen werden darf. */
    fun accepts(v: Int): Boolean = v in MIN_SUPPORTED..CURRENT

    /**
     * Prüft die Gegenstelle aus ihrem `Hello` (`v` = [remoteVersion], `minV` = [remoteMinSupported]) gegen die eigenen
     * Grenzen [localVersion]/[localMinSupported]. Kompatibel heißt: die Versionsbereiche überlappen.
     */
    fun negotiate(
        remoteVersion: Int,
        remoteMinSupported: Int,
        localVersion: Int = CURRENT,
        localMinSupported: Int = MIN_SUPPORTED,
    ): Compatibility =
        when {
            remoteVersion < localMinSupported -> Compatibility(CompatibilityStatus.PEER_TOO_OLD, null)
            remoteMinSupported > localVersion -> Compatibility(CompatibilityStatus.PEER_TOO_NEW, null)
            else -> Compatibility(CompatibilityStatus.COMPATIBLE, minOf(localVersion, remoteVersion))
        }
}

/** Ergebnis von [ProtocolVersion.negotiate]. */
enum class CompatibilityStatus {
    COMPATIBLE,

    /** Gegenstelle ist zu alt – sie muss aktualisiert werden. */
    PEER_TOO_OLD,

    /** Gegenstelle ist zu neu – dieses Gerät muss aktualisiert werden. */
    PEER_TOO_NEW,
}

/**
 * Ausgehandelte Kompatibilität mit einer Gegenstelle.
 *
 * @property version gemeinsame Version, in der geschrieben wird; `null`, wenn nicht kompatibel
 */
data class Compatibility(
    val status: CompatibilityStatus,
    val version: Int?,
) {
    val isCompatible: Boolean get() = status == CompatibilityStatus.COMPATIBLE
}
