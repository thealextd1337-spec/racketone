package eu.advantly.match.session

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.Uuid7
import eu.advantly.sync.protocol.AntiEntropy
import eu.advantly.sync.protocol.AppBuild
import eu.advantly.sync.protocol.PeerKind
import eu.advantly.sync.protocol.ProtocolVersion
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Wanduhr in Epoch-Millisekunden (nur Anzeige/Statistik und Doppel-Tap-Fenster, nie für die Ordnung). */
fun interface WallClock {
    fun nowMs(): Long
}

/** Systemuhr. */
@OptIn(ExperimentalTime::class)
object SystemWallClock : WallClock {
    override fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()
}

/**
 * Prüft, ob der Aufruf auf dem Thread des Schedulers läuft (Android: Main-Looper, Apple: Main-Queue). Öffentliche
 * Session-Aufrufe von anderen Threads schlagen mit [SessionThreadViolation] fehl, statt still Zustand zu zerstören.
 */
fun interface ThreadGuard {
    fun isSchedulerThread(): Boolean

    companion object {
        /** Keine Prüfung (Tests mit `ManualScheduler`, Desktop). */
        val NONE: ThreadGuard = ThreadGuard { true }
    }
}

/** Eigener Fehlertyp, damit Facades einen falschen Aufruf-Thread von Speicherfehlern unterscheiden können. */
class SessionThreadViolation : IllegalStateException("MatchSession nur auf dem Scheduler-Thread aufrufen (Main-Thread)")

/** Quelle für UUIDv7 (Match- und Event-IDs). In Tests deterministisch ersetzbar. */
fun interface IdGenerator {
    fun newUuid7(nowMs: Long): Uuid7
}

/** UUIDv7 aus Zeitstempel + 74 Zufallsbits. */
class RandomIdGenerator(
    private val random: Random = Random.Default,
) : IdGenerator {
    override fun newUuid7(nowMs: Long): Uuid7 = Uuid7.fromParts(nowMs.coerceIn(0, MAX_TIMESTAMP), random.nextInt(), random.nextLong())

    private companion object {
        const val MAX_TIMESTAMP = (1L shl 48) - 1
    }
}

/**
 * Einstellungen einer [MatchSession].
 *
 * @property deviceId stabile Installations-ID (Teil der Event-Ordnung), siehe [MatchSessions.deviceId]
 * @property kind Art dieses Geräts (Anzeige bei den Gegenstellen)
 * @property antiEntropyIntervalMs Abgleich während eines aktiven Matches (01 §4.3: 30 s)
 * @property summaryMatchLimit so viele zuletzt geänderte Matches stehen zusätzlich zu den offenen im `Summary`
 * @property autoFollow ein von einer Gegenstelle gestartetes Match automatisch öffnen (Regeln: `MatchSession.maybeFollow`)
 * @property parallelMatches mehrere Matches gleichzeitig offen halten und das Uhr-Match zuweisen (F22) – Standard nur am
 *   Handy; Uhren halten genau ein Match offen und folgen der Zuweisung des Handys
 * @property protocolVersions lesbare Protokollversionen und höchste gesprochene (E1, Welle 3). Standard sind die
 *   Konstanten von [ProtocolVersion]; ein anderer Bereich (Tests) ändert sie nicht. Ohne Konto spricht die Session nur
 *   die älteste Version des Bereichs, mit Konto nur noch ab 4 (`minV = 4`); ab Version 4 gelten Belege und
 *   Lifecycle-Zustellung.
 */
data class SessionConfig(
    val deviceId: DeviceId,
    val kind: PeerKind,
    val antiEntropyIntervalMs: Long = AntiEntropy.INTERVAL_MS,
    val summaryMatchLimit: Long = 8,
    val autoFollow: Boolean = true,
    val parallelMatches: Boolean = kind == PeerKind.PHONE,
    val appBuild: AppBuild? = null,
    val protocolVersions: IntRange = ProtocolVersion.MIN_SUPPORTED..ProtocolVersion.CURRENT,
)
