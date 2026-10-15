package eu.advantly.ui.phone.latency

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlin.math.ceil
import kotlin.math.roundToLong

/** Woher der Eingabe-Zeitpunkt t0 stammt. Jede Messung behält ihre Quelle; ausgewertet wird getrennt. */
enum class InputClockSource {
    /** Zeitstempel des Plattform-Ereignisses (MotionEvent/UIEvent/AWT) – enthält die Zustellzeit bis zum Handler. */
    InputEvent,

    /**
     * Zeitpunkt, an dem der Handler lief (Bedienhilfe-Klick, Knopf ohne Ereigniszeit oder unplausibler Zeitstempel).
     * Fehlt die Zustellzeit → zu optimistisch, zählt **nicht** für das Go-Kriterium G1.
     */
    Handler,

    /** Auto-Benchmark ohne echte Eingabe (misst nur Zustand → Frame-Beginn), Grundlage für G7 (Simulator). */
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

/** Auswertung EINER Quelle. */
data class LatencySummary(
    val source: InputClockSource,
    val inputToNextFrameP50: Double?,
    val inputToNextFrameP95: Double?,
    val inputToNextFrameMax: Double?,
    val inputToDrawP50: Double?,
    val inputToDrawP95: Double?,
    val samples: Int,
) {
    /** Maschinenlesbare Zeile (CI wertet sie aus, siehe `.github/scripts/check_s4_bench.py`). */
    fun toLogLine(): String =
        "ADVANTLY_BENCH source=$source inputToNextFrame p50=${formatMillis(inputToNextFrameP50)} " +
            "p95=${formatMillis(inputToNextFrameP95)} max=${formatMillis(inputToNextFrameMax)} " +
            "inputToDraw p50=${formatMillis(inputToDrawP50)} p95=${formatMillis(inputToDrawP95)} n=$samples"
}

/** „12.3“ – eine Nachkommastelle, ohne Plattform-Formatierer; „–“ ohne Wert. */
fun formatMillis(value: Double?): String {
    if (value == null) return "–"
    val tenths = (value * 10).roundToLong()
    return "${tenths / 10}.${tenths % 10}"
}

/**
 * Misst **Eingabe → nächster Frame-Beginn nach Draw** (Spike S4):
 * 1. `t0` = Eingabe (Zeitstempel des Plattform-Ereignisses, sonst Handler-Zeit) – [onInput].
 * 2. `tDraw` = Draw-Pass, der den neuen Zustand aufzeichnet – [onDrawn] (Wurzel-Modifier des Screens).
 * 3. `tNext` = Beginn des nächsten Frame-Callbacks danach (Android: Choreographer, iOS: CADisplayLink-getriebene
 *    Frame-Clock, Desktop: Compose-Frame-Clock).
 *
 * Das ist **kein Präsentationsnachweis**: Wann der Frame tatsächlich auf dem Display erscheint (RenderThread/GPU,
 * Compositor, Scanout) und die Touch-Abtastung sind nicht enthalten. Kalibrierung siehe Spike-Bericht
 * (`dumpsys gfxinfo … framestats`, Perfetto, Zeitlupenvideo). Alle Aufrufe auf dem UI-Thread.
 */
@Stable
class LatencyTracker(
    private val capacity: Int = 500,
) {
    private class Pending(
        val generation: Int,
        val t0: Long,
        val source: InputClockSource,
    ) {
        var tDraw: Long = 0L
    }

    private val pending = ArrayList<Pending>()
    private val awaitingFrame = ArrayList<Pending>()
    private val draw = InputClockSource.entries.associateWith { SampleWindow(capacity) }
    private val nextFrame = InputClockSource.entries.associateWith { SampleWindow(capacity) }

    /** Zählt jede neue Messung hoch; das Overlay liest diesen Wert, um sich neu zu zeichnen. */
    var version by mutableIntStateOf(0)
        private set

    fun inputToDraw(source: InputClockSource): SampleWindow = draw.getValue(source)

    fun inputToNextFrame(source: InputClockSource): SampleWindow = nextFrame.getValue(source)

    fun onInput(
        generation: Int,
        t0Nanos: Long,
        source: InputClockSource,
    ) {
        pending += Pending(generation, t0Nanos, source)
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
            draw.getValue(it.source).add((it.tDraw - it.t0) / 1_000_000.0)
            nextFrame.getValue(it.source).add((nowNanos - it.t0) / 1_000_000.0)
        }
        awaitingFrame.clear()
        version++
    }

    fun reset() {
        pending.clear()
        awaitingFrame.clear()
        draw.values.forEach { it.clear() }
        nextFrame.values.forEach { it.clear() }
        version++
    }

    fun summary(source: InputClockSource): LatencySummary {
        val frame = nextFrame.getValue(source)
        val drawn = draw.getValue(source)
        return LatencySummary(
            source = source,
            inputToNextFrameP50 = frame.percentile(50.0),
            inputToNextFrameP95 = frame.percentile(95.0),
            inputToNextFrameMax = frame.max(),
            inputToDrawP50 = drawn.percentile(50.0),
            inputToDrawP95 = drawn.percentile(95.0),
            samples = frame.count,
        )
    }

    /** Auswertungen aller Quellen mit Messwerten, in der Reihenfolge von [InputClockSource]. */
    fun summaries(): List<LatencySummary> = InputClockSource.entries.map(::summary).filter { it.samples > 0 }
}
