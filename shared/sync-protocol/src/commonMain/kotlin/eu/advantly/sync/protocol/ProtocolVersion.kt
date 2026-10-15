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
 *
 * Versionen:
 * - **1** (W1): Events bis Single-Scorer.
 * - **2** (W2-A, 2026-10-15): neue Event-Inhalte `ServerChosen`, `ContinueDecision.nextSetKind = TIEBREAK_7 |
 *   MATCH_TIEBREAK_10` und `PointDetail` ohne `kind` (02 §7). Ein Gerät mit Version 1 kann sie nicht dekodieren.
 *
 * **Warum [MIN_SUPPORTED] = 2:** Events lassen sich nicht in Version 1 „herunterschreiben“ – ein Log, das die neuen
 * Inhalte enthält, käme bei einem Gerät mit Version 1 nur teilweise an (es verwirft die unlesbaren `Events`-Nachrichten
 * still, Anti-Entropie schickt sie endlos nach). Das wäre ein halb-synchroner Zustand mit falschem Spielstand. Deshalb
 * sind 1 und 2 **nicht** kompatibel: Über `Hello` meldet das neue Gerät die Gegenstelle als `PEER_TOO_OLD`, das alte
 * das neue als `PEER_TOO_NEW` (beide prüfen die Überlappung der Bereiche); beide tauschen danach nichts mehr aus, und
 * die UI zeigt „Bitte Uhr/Handy aktualisieren“. Hello wird unabhängig von `v` gelesen, daher erkennt auch ein Gerät mit
 * Version 1 den Unterschied.
 */
object ProtocolVersion {
    /** Version, die dieses Gerät spricht und schreibt. */
    const val CURRENT: Int = 2

    /** Älteste Version, die dieses Gerät noch lesen kann. */
    const val MIN_SUPPORTED: Int = 2

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
