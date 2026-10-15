package eu.advantly.wear.diagnostics

import android.view.Display

/**
 * Feldtest-Modus (P1-Gate, `docs/release/p1-gate-protokoll.md`): reine Logik des Minuten-Protokolls der Uhr.
 * Geschrieben wird es von [FieldTestRecorder], ausgewertet von `tools/gate-check` (`battery`).
 *
 * Format `fieldtest.jsonl`, Version [VERSION]: eine JSON-Zeile je Messpunkt, Schlüssel in fester Reihenfolge, `null`-Werte
 * weggelassen. **Keine Gesundheitsdaten** (kein Puls, keine Schritte, keine Kalorien) – nur Akku, Display, Workout an/aus,
 * Sync-Zähler und Spielstand-Zähler. Formatänderungen gemeinsam mit `FieldLogParser` (tools/gate-check) anpassen.
 */
object FieldTestLogic {
    const val VERSION = 1

    /** Systemdisplay-Zustand → Protokollwert (Ambient/Always-on läuft als Doze bzw. On-Suspend). */
    fun displayMode(state: Int): DisplayMode =
        when (state) {
            Display.STATE_ON, Display.STATE_VR -> DisplayMode.ON
            Display.STATE_DOZE, Display.STATE_DOZE_SUSPEND, Display.STATE_ON_SUSPEND -> DisplayMode.AMBIENT
            Display.STATE_OFF -> DisplayMode.OFF
            else -> DisplayMode.UNKNOWN
        }

    /**
     * Zusätzliche Messpunkte bei einem Match-Wechsel: `match_end` mit dem **letzten** Stand des alten Matches (sonst fehlen
     * die Punkte nach dem letzten Minutentakt), `match_start` für das neue.
     */
    fun transitions(
        previous: SessionReading?,
        next: SessionReading,
    ): List<Pair<String, SessionReading>> {
        if (previous?.matchId == next.matchId) return emptyList()
        return buildList {
            if (previous?.matchId != null) add(KIND_MATCH_END to previous)
            if (next.matchId != null) add(KIND_MATCH_START to next)
        }
    }

    const val KIND_START = "start"
    const val KIND_TICK = "tick"
    const val KIND_MATCH_START = "match_start"
    const val KIND_MATCH_END = "match_end"
    const val KIND_STOP = "stop"
}

enum class DisplayMode(
    val wire: String,
) {
    ON("on"),
    AMBIENT("ambient"),
    OFF("off"),
    UNKNOWN("unknown"),
}

/** Akkustand; `null` = von der Uhr nicht geliefert. [chargeUah] = Ladungszähler (feiner als 1 %), nicht auf allen Uhren. */
data class BatteryReading(
    val percent: Int?,
    val charging: Boolean?,
    val chargeUah: Long?,
    val powerSave: Boolean?,
)

/** Workout-Zustand ohne Messwerte. */
data class WorkoutReading(
    val active: Boolean,
    val phase: String,
    val countOnly: Boolean,
)

/**
 * Session-Ausschnitt: offenes Match und Sync-Zähler (`LinkStats`, kumulativ seit Prozessstart).
 *
 * @property points wirksam gezählte Ballwechsel, @property events Events im Log des Matches
 */
data class SessionReading(
    val matchId: String?,
    val sport: String?,
    val scorer: Boolean?,
    val points: Int?,
    val events: Int?,
    val pending: Int,
    val peers: Int,
    val link: String,
    val sent: Int,
    val received: Int,
    val acked: Int,
    val retries: Int,
    val failed: Int,
)

/** Display-Zeiten seit dem vorherigen Messpunkt. */
data class DisplayTimes(
    val current: DisplayMode,
    val onMs: Long,
    val ambientMs: Long,
    val offMs: Long,
)

/** Gerät/App, nur in der `start`-Zeile. */
data class DeviceInfo(
    val model: String,
    val os: String,
    val app: String,
)

/** Ein Messpunkt; [toJsonLine] erzeugt die Zeile im Format [FieldTestLogic.VERSION]. */
data class FieldTestSample(
    val kind: String,
    val wallMs: Long,
    val elapsedMs: Long,
    val pid: Int,
    val battery: BatteryReading,
    val display: DisplayTimes,
    val workout: WorkoutReading,
    val session: SessionReading,
    val device: DeviceInfo? = null,
    val reason: String? = null,
) {
    fun toJsonLine(): String {
        val fields =
            linkedMapOf<String, Any?>(
                "v" to FieldTestLogic.VERSION,
                "kind" to kind,
                "wallMs" to wallMs,
                "elapsedMs" to elapsedMs,
                "pid" to pid,
                "battery" to battery.percent,
                "charging" to battery.charging,
                "chargeUah" to battery.chargeUah,
                "display" to display.current.wire,
                "onMs" to display.onMs,
                "ambientMs" to display.ambientMs,
                "offMs" to display.offMs,
                "powerSave" to battery.powerSave,
                "workout" to workout.active,
                "workoutPhase" to workout.phase,
                "countOnly" to workout.countOnly,
                "matchId" to session.matchId,
                "sport" to session.sport,
                "scorer" to session.scorer,
                "points" to session.points,
                "events" to session.events,
                "pending" to session.pending,
                "peers" to session.peers,
                "link" to session.link,
                "sent" to session.sent,
                "received" to session.received,
                "acked" to session.acked,
                "retries" to session.retries,
                "failed" to session.failed,
                "model" to device?.model,
                "os" to device?.os,
                "app" to device?.app,
                "reason" to reason,
            )
        return fields.entries
            .filter { it.value != null }
            .joinToString(separator = ",", prefix = "{", postfix = "}") { (key, value) -> "\"$key\":${json(value)}" }
    }

    private fun json(value: Any?): String =
        when (value) {
            is Number, is Boolean -> value.toString()
            else -> "\"" + escape(value.toString()) + "\""
        }

    private fun escape(text: String): String =
        buildString {
            text.forEach { c ->
                when {
                    c == '"' -> append("\\\"")
                    c == '\\' -> append("\\\\")
                    c < ' ' -> append("\\u%04x".format(c.code))
                    else -> append(c)
                }
            }
        }
}

/**
 * Summiert die Zeit je Display-Zustand zwischen zwei Messpunkten (monotone Zeit `elapsedRealtime`, läuft im Tiefschlaf
 * weiter). [drain] liefert die Summen seit dem letzten Aufruf und beginnt neu.
 */
class DisplayTimeAccumulator(
    initial: DisplayMode,
    nowElapsedMs: Long,
) {
    private var mode = initial
    private var since = nowElapsedMs
    private var on = 0L
    private var ambient = 0L
    private var off = 0L

    fun onChange(
        next: DisplayMode,
        nowElapsedMs: Long,
    ) {
        add(nowElapsedMs)
        mode = next
    }

    fun drain(nowElapsedMs: Long): DisplayTimes {
        add(nowElapsedMs)
        val times = DisplayTimes(mode, on, ambient, off)
        on = 0
        ambient = 0
        off = 0
        return times
    }

    private fun add(now: Long) {
        val delta = (now - since).coerceAtLeast(0)
        since = maxOf(since, now)
        when (mode) {
            DisplayMode.ON -> on += delta
            DisplayMode.AMBIENT -> ambient += delta
            DisplayMode.OFF -> off += delta
            DisplayMode.UNKNOWN -> Unit
        }
    }
}
