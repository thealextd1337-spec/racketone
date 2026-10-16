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
 * - **3** (W3-M, 2026-10-15): neuer Endgrund `MatchEnded.reason = STOPPED` („Beenden, wie es steht“, 02 §9.27) und
 *   die Uhr-Zuweisung `MatchMeta.watchMatch` für mehrere parallele Matches am Handy (01 §4.3, F21/F22). Ein Gerät mit
 *   Version 2 kann `STOPPED` nicht dekodieren und würde dem falschen Match folgen.
 *
 *   Gemeinsam mit W3-S (F20, 2026-10-15): `PlayerAssigned`, Ersteller-ID und Platzhalter-Kennzeichnung; auch dieses Event
 *   kann Version 2 nicht dekodieren. Keine verlustbehaftete Rückübersetzung; gespeicherte Logs der Versionen 1/2 bleiben
 *   lesbar.
 *
 *   Additiv in Version 3 (P2-J): `MatchMeta.telemetry` (Telemetrie-Sichtbarkeit). Ältere Geräte der Version 3 ignorieren
 *   das Feld; fehlt es, behält der Empfänger seinen Wert. Kein Versionssprung nötig.
 *
 * **Warum [MIN_SUPPORTED] = [CURRENT]:** Events lassen sich nicht in eine ältere Version „herunterschreiben“ – ein Log,
 * das die neuen Inhalte enthält, käme bei einem älteren Gerät nur teilweise an (es verwirft die unlesbaren
 * `Events`-Nachrichten still, Anti-Entropie schickt sie endlos nach). Das wäre ein halb-synchroner Zustand mit falschem
 * Spielstand. Deshalb sind aufeinanderfolgende Versionen mit neuen Event-Inhalten **nicht** kompatibel (1/2 wie 2/3):
 * Über `Hello` meldet das neue Gerät die Gegenstelle als `PEER_TOO_OLD`, das alte das neue als `PEER_TOO_NEW` (beide
 * prüfen die Überlappung der Bereiche); beide tauschen danach nichts mehr aus, und die UI zeigt „Bitte Uhr/Handy
 * aktualisieren“. Hello wird unabhängig von `v` gelesen, daher erkennt auch ein älteres Gerät den Unterschied.
 */
object ProtocolVersion {
    /** Version, die dieses Gerät spricht und schreibt. */
    const val CURRENT: Int = 3

    /** Älteste Version, die dieses Gerät noch lesen kann. */
    const val MIN_SUPPORTED: Int = 3

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
