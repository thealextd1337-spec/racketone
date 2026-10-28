package eu.advantly.sync.protocol

/**
 * Protokollversion des Geräte-Syncs (Feld `v` jeder Nachricht, 01 §4.3).
 *
 * Regeln:
 * - Ein Gerät liest Nachrichten mit `v` in [MIN_SUPPORTED]..[CURRENT] und schreibt je Gegenstelle die über [negotiate]
 *   ausgehandelte Version. Datennachrichten (`Summary`, `Events`, `MatchMeta`, `Deleted`) entstehen ohne Angabe in
 *   [MIN_SUPPORTED] (ohne Belege); erst der Sender hebt sie für die Gegenstelle an (ab 4 mit Belegen).
 * - `Hello` wird unabhängig von `v` gelesen (nur `deviceId`, `kind`, `v`, `minV` sind nötig), damit beide Seiten die
 *   Kompatibilität feststellen können. Der Bereich im `Hello` ist der, den die Session tatsächlich spricht (ohne Konto
 *   nur 3, siehe unten).
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
 * - **4** (Welle 3, 2026-10-23, p2-plan §5.7): Konto-Protokoll zwischen Handy und eigener Uhr. Herkunftsbelege
 *   (`MatchDataStamp`) an `Events`, `Summary`, `MatchMeta` und `Deleted`, geprüft vor jeder Mutation und jedem Ack;
 *   Lifecycle-Zustellung von Zugriff und Redaktion (`AccessState`, `RedactedChunk` und Acks, nur mit `IdentityEnvelope`);
 *   `Summary.lifecycle`; 64-KiB-Grenze je Paket inklusive Envelope; `PlayerAssigned` ohne Klarnamen (Referenz).
 * - **5** (0.3.0, 2026-10-28): neue Event-Inhalte `ResultCorrected` und `GameServersCorrected` (Ergebnis und
 *   Aufschläger nach Matchende am Handy korrigieren). Ein Gerät mit Version 4 kann sie nicht dekodieren und verwürfe
 *   ganze `Events`-Nachrichten. Mit Konto meldet die Session deshalb `minV = 5`: 4↔5 ist auf beiden Seiten ein
 *   Updatezustand (wie 3↔4). Ohne Konto bleibt es bei 3, und die Session bietet keine Korrektur an.
 *
 * **Warum 3↔4 mit Konto inkompatibel ist:** Eine Uhr mit Protokoll 3 versteht weder Belege noch Redaktion. Über 3 kämen
 * Events an, Zugriffsentzug und Redaktion nie – die Uhr behielte gelöschte Klarnamen (Datenschutzfehler, kein
 * Anzeigefehler), und das Handy hielte sie trotzdem für synchron. Mit Konto meldet die Session deshalb `minV = 4`: Gegen
 * ein Gerät mit 3 entsteht auf beiden Seiten ein Updatezustand, außer `Hello` fließt nichts (alte Uhr zählt lokal weiter,
 * ihre Events kommen nach dem Update per Anti-Entropie).
 *
 * **Warum [MIN_SUPPORTED] = 3 bleibt:** Ohne Konto (P1) spricht die Session nur 3 (Belege, Zustellung und namenfreie
 * Zuweisung gibt es erst mit Konto). Ein P1-Log enthält dann nur Inhalte, die 3 lesen kann; ein Gerät ohne Konto bleibt
 * mit einer v3-Gegenstelle kompatibel, ohne Teilsync-Risiko. Ein Gerät, das je ein Konto hatte, bleibt kontogebunden
 * (auch als Gast nach Abmeldung) und damit bei `minV = 4`.
 *
 * **Grundregel für Versionen mit neuen Event-Inhalten:** Events lassen sich nicht in eine ältere Version
 * „herunterschreiben“ – ein Log, das die neuen Inhalte enthält, käme bei einem älteren Gerät nur teilweise an (es verwirft
 * die unlesbaren `Events`-Nachrichten still, Anti-Entropie schickt sie endlos nach). Das wäre ein halb-synchroner Zustand
 * mit falschem Spielstand. Deshalb waren 1/2 wie 2/3 **nicht** kompatibel: Über `Hello` meldet das neue Gerät die
 * Gegenstelle als `PEER_TOO_OLD`, das alte das neue als `PEER_TOO_NEW` (beide prüfen die Überlappung der Bereiche);
 * beide tauschen danach nichts mehr aus, und die UI zeigt „Bitte Uhr/Handy aktualisieren“. Hello wird unabhängig von `v`
 * gelesen, daher erkennt auch ein älteres Gerät den Unterschied.
 */
object ProtocolVersion {
    /** Höchste Version, die dieses Gerät spricht und schreibt (mit Konto). */
    const val CURRENT: Int = 5

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
