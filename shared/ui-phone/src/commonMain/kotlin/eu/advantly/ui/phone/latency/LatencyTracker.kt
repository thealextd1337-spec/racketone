package eu.advantly.ui.phone.latency

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.ceil
import kotlin.math.roundToLong

/** Woher der Eingabe-Zeitpunkt t0 stammt. */
enum class InputClockSource {
    /** Zeitstempel des Plattform-Ereignisses (MotionEvent/UIEvent/AWT) – enthält die Zustellzeit bis zum Handler. */
    InputEvent,

    /** Zeitpunkt, an dem der Compose-Handler lief (Fallback, z. B. Bedienhilfe-Klick). */
    Handler,

    /** Auto-Benchmark ohne echte Eingabe (misst nur Zustand → Frame). */
    Synthetic,
}

/** Ringpuffer für Messwerte in Millisekunden mit Perzentilen (Nearest-Rank). */
class SampleWindow(
    private val capacity: Int = 500,
) {
    private val values = ArrayDeque<Double>()

    val count: Int get() = values.size

    fun add(millis: Double) {
        if (values.size == capacity) values.removeFirst()
        values.addLast(millis)
    }

    fun clear() = values.clear()

    fun percentile(p: Double): Double? = percentileOf(values, p)

    fun max(): Double? = values.maxOrNull()
}

/** Nearest-Rank-Perzentil (p in 0..100); `null` bei leerer Liste. */
fun percentileOf(
    values: Collection<Double>,
    p: Double,
): Double? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val rank = ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
    return sorted[rank - 1]
}

data class LatencySummary(
    val tapToFrameP50: Double?,
    val tapToFrameP95: Double?,
    val tapToFrameMax: Double?,
    val tapToDrawP50: Double?,
    val tapToDrawP95: Double?,
    val samples: Int,
    val source: InputClockSource?,
) {
    fun toLogLine(): String =
        "ADVANTLY_BENCH tapToFrame p50=${formatMillis(tapToFrameP50)} p95=${formatMillis(tapToFrameP95)} " +
            "max=${formatMillis(tapToFrameMax)} tapToDraw p50=${formatMillis(tapToDrawP50)} p95=${formatMillis(tapToDrawP95)} " +
            "n=$samples source=$source"
}

/** „12.3“ – eine Nachkommastelle, ohne Plattform-Formatierer; „–“ ohne Wert. */
fun formatMillis(value: Double?): String {
    if (value == null) return "–"
    val tenths = (value * 10).roundToLong()
    return "${tenths / 10}.${tenths % 10}"
}

/**
 * Misst **Tap → Frame** (Spike S4):
 * 1. `t0` = Eingabe (Zeitstempel des Plattform-Ereignisses, sonst Handler-Zeit) – [onInput].
 * 2. `tDraw` = Draw-Pass, der den neuen Zustand zeichnet – [onDrawn] (Wurzel-Modifier des Screens).
 * 3. `tFrame` = nächster Frame-Callback danach (Android: Choreographer, iOS: CADisplayLink, Desktop: Compose-Frame-Clock)
 *    – ab hier ist der gezeichnete Frame an den Compositor übergeben, er wird mit der nächsten Bildwiederholung sichtbar.
 *
 * „Tap → Frame“ = `tFrame - t0` ist damit eine Untergrenze der Ende-zu-Ende-Latenz (ohne Touch-Panel-Abtastung und
 * Display-Scanout). Alle Aufrufe auf dem UI-Thread.
 */
@Stable
class LatencyTracker(
    capacity: Int = 500,
) {
    private class Pending(
        val generation: Int,
        val t0: Long,
    ) {
        var tDraw: Long = 0L
    }

    private val pending = ArrayList<Pending>()
    private val awaitingFrame = ArrayList<Pending>()

    val tapToDraw = SampleWindow(capacity)
    val tapToFrame = SampleWindow(capacity)

    /** Zählt jede neue Messung hoch; das Overlay liest diesen Wert, um sich neu zu zeichnen. */
    var version by mutableIntStateOf(0)
        private set

    var lastSource by mutableStateOf<InputClockSource?>(null)
        private set

    fun onInput(
        generation: Int,
        t0Nanos: Long,
        source: InputClockSource,
    ) {
        pending += Pending(generation, t0Nanos)
        lastSource = source
    }

    /** Aus dem Draw-Pass: `true`, wenn jetzt auf den nächsten Frame gewartet werden muss. */
    fun onDrawn(
        drawnGeneration: Int,
        nowNanos: Long,
    ): Boolean {
        if (pending.isEmpty()) return false
        val done = pending.filter { it.generation <= drawnGeneration }
        if (done.isEmpty()) return false
        pending.removeAll(done)
        done.forEach {
            it.tDraw = nowNanos
            awaitingFrame += it
        }
        return true
    }

    fun onNextFrame(nowNanos: Long) {
        if (awaitingFrame.isEmpty()) return
        awaitingFrame.forEach {
            tapToDraw.add((it.tDraw - it.t0) / 1_000_000.0)
            tapToFrame.add((nowNanos - it.t0) / 1_000_000.0)
        }
        awaitingFrame.clear()
        version++
    }

    fun reset() {
        pending.clear()
        awaitingFrame.clear()
        tapToDraw.clear()
        tapToFrame.clear()
        lastSource = null
        version++
    }

    fun summary(): LatencySummary =
        LatencySummary(
            tapToFrameP50 = tapToFrame.percentile(50.0),
            tapToFrameP95 = tapToFrame.percentile(95.0),
            tapToFrameMax = tapToFrame.max(),
            tapToDrawP50 = tapToDraw.percentile(50.0),
            tapToDrawP95 = tapToDraw.percentile(95.0),
            samples = tapToFrame.count,
            source = lastSource,
        )
}
