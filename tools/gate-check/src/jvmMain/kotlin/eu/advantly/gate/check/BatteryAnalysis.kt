package eu.advantly.gate.check

import kotlin.math.max

/** Akku-Urteil eines Abschnitts (PLAN Gate P1: Uhr < 15 %/h). */
enum class BatteryVerdict(
    val label: String,
) {
    PASSED("BESTANDEN"),
    UNCLEAR("UNKLAR"),
    FAILED("NICHT BESTANDEN"),
}

/**
 * Auswertung eines Abschnitts des Feldtest-Logs (ein Match oder die ganze Aufzeichnung).
 *
 * @property dischargeMinutes Zeit ohne Laden zwischen zwei Messpunkten mit Akkuwert (Grundlage für %/h)
 * @property dropPercent Akkuverbrauch in dieser Zeit (Summe der Differenzen, Ladephasen ausgenommen)
 * @property ratePerHour Verbrauch in %/h, `null` ohne Entladezeit
 * @property chargeDropMah Verbrauch laut Ladungszähler (feiner als 1 %), falls die Uhr ihn liefert
 * @property screenOnPct/ambientPct/offPct Anteile der Bildschirmzustände an der erfassten Zeit
 * @property workoutPct Zeitanteil mit aktivem Workout
 * @property sent/received Nachrichten an/vom Handy (Link-Zähler, Prozessneustarts berücksichtigt)
 * @property restarts Prozesswechsel innerhalb des Abschnitts (App vom System beendet und neu gestartet)
 * @property maxGapMinutes größter Abstand zweier Messpunkte (Tiefschlaf verzögert den Minutentakt)
 * @property segments zusammenhängende Abschnitte (durch Leerlauf/anderes Match getrennt)
 * @property rises Akkuanstiege ohne erkanntes Laden (→ immer UNKLAR)
 */
data class BatterySection(
    val title: String,
    val matchId: String?,
    val sport: String?,
    val startMs: Long,
    val endMs: Long,
    val samples: Int,
    val batteryStart: Int?,
    val batteryEnd: Int?,
    val dischargeMinutes: Double,
    val chargingMinutes: Double,
    val dropPercent: Int,
    val ratePerHour: Double?,
    val chargeDropMah: Double?,
    val screenOnPct: Double?,
    val ambientPct: Double?,
    val offPct: Double?,
    val workoutPct: Double?,
    val sent: Int,
    val received: Int,
    val failed: Int,
    val points: Int?,
    val restarts: Int,
    val maxGapMinutes: Double,
    val segments: Int,
    val rises: Int,
    val verdict: BatteryVerdict,
    val reason: String,
) {
    val durationMinutes: Double get() = (endMs - startMs) / MINUTE
}

private const val MINUTE = 60_000.0
private const val HOUR = 3_600_000.0

/** Auswertung des Feldtest-Logs: %/h je Match und über die ganze Aufzeichnung. */
object BatteryAnalysis {
    /** Gate-Schwelle (PLAN Gate P1, 05 §5.6). */
    const val MAX_RATE_PER_HOUR = 15.0

    /** Kürzeste Entladezeit für ein belastbares Urteil (Akkuwert hat nur 1 % Auflösung). */
    const val MIN_DISCHARGE_MINUTES = 20.0

    /**
     * Je Match: ausgewertet werden nur **zusammenhängende** Paare aufeinanderfolgender Messpunkte des vollständigen Logs,
     * die beide zu diesem Match gehören. Lade-, Leerlauf- oder Fremd-Match-Zeilen dazwischen trennen die Abschnitte, statt
     * herausgefiltert und überbrückt zu werden (sonst würde ein Ladevorgang den Verbrauch verdecken).
     */
    fun perMatch(
        log: FieldLog,
        maxRate: Double = MAX_RATE_PER_HOUR,
    ): List<BatterySection> {
        val sorted = log.samples.sortedWith(ORDER)
        val pairs = sorted.zipWithNext()
        return sorted
            .mapNotNull { it.matchId }
            .distinct()
            .map { id ->
                val samples = sorted.filter { it.matchId == id }
                val own = pairs.filter { (prev, cur) -> prev.matchId == id && cur.matchId == id }
                section("Match ${IntegrityCheck.short(id)}", id, samples.firstNotNullOfOrNull { it.sport }, samples, own, maxRate)
            }.sortedBy { it.startMs }
    }

    fun whole(
        log: FieldLog,
        maxRate: Double = MAX_RATE_PER_HOUR,
    ): BatterySection? = log.samples.takeIf { it.size >= 2 }?.let { section("Gesamte Aufzeichnung", null, null, it, maxRate) }

    /** Ein lückenloser Abschnitt: alle aufeinanderfolgenden Paare von [samples]. */
    fun section(
        title: String,
        matchId: String?,
        sport: String?,
        samples: List<FieldSample>,
        maxRate: Double = MAX_RATE_PER_HOUR,
    ): BatterySection {
        val sorted = samples.sortedWith(ORDER)
        return section(title, matchId, sport, sorted, sorted.zipWithNext(), maxRate)
    }

    private val ORDER = compareBy<FieldSample>({ it.wallMs }, { it.elapsedMs })

    private fun section(
        title: String,
        matchId: String?,
        sport: String?,
        sorted: List<FieldSample>,
        pairs: List<Pair<FieldSample, FieldSample>>,
        maxRate: Double,
    ): BatterySection {
        var dischargeMs = 0L
        var chargingMs = 0L
        var drop = 0
        var chargeDropUah = 0L
        var chargeKnown = false
        var on = 0L
        var ambient = 0L
        var off = 0L
        var workoutMs = 0L
        var spanMs = 0L
        var sent = 0
        var received = 0
        var failed = 0
        var restarts = 0
        var maxGap = 0L
        var rises = 0
        var segments = 0
        var last: FieldSample? = null
        pairs.forEach { (prev, cur) ->
            if (last !== prev) segments++
            last = cur
            val dt = max(0L, cur.wallMs - prev.wallMs)
            spanMs += dt
            maxGap = max(maxGap, dt)
            val samePid = prev.pid == cur.pid
            if (!samePid) restarts++
            val charging = prev.charging == true || cur.charging == true
            val knownState = prev.charging != null && cur.charging != null
            if (charging) {
                chargingMs += dt
            } else if (knownState && prev.battery != null && cur.battery != null) {
                // Anstieg ohne erkanntes Laden = unklarer Ladeübergang: darf nie als Verbrauchsnachweis gelten
                if (cur.battery > prev.battery) rises++
                dischargeMs += dt
                drop += prev.battery - cur.battery
                if (prev.chargeUah != null && cur.chargeUah != null) {
                    chargeKnown = true
                    chargeDropUah += prev.chargeUah - cur.chargeUah
                }
            }
            if (prev.workout) workoutMs += dt
            if (samePid) {
                on += cur.onMs
                ambient += cur.ambientMs
                off += cur.offMs
            }
            sent += delta(prev.sent, cur.sent, samePid)
            received += delta(prev.received, cur.received, samePid)
            failed += delta(prev.failed, cur.failed, samePid)
        }
        val rate = if (dischargeMs > 0) drop / (dischargeMs / HOUR) else null
        val screenTotal = (on + ambient + off).toDouble()

        fun share(part: Long) = if (screenTotal > 0) part * 100.0 / screenTotal else null
        val dischargeMinutes = dischargeMs / MINUTE
        val (verdict, reason) =
            when {
                rises > 0 -> {
                    BatteryVerdict.UNCLEAR to "Akkuanstieg ohne erkanntes Laden ($rises×) – unklarer Ladeübergang"
                }

                rate == null -> {
                    BatteryVerdict.UNCLEAR to "keine Entladephase mit Akkuwerten"
                }

                dischargeMinutes < MIN_DISCHARGE_MINUTES -> {
                    BatteryVerdict.UNCLEAR to "nur %.0f min ohne Laden (< %.0f min)".format(dischargeMinutes, MIN_DISCHARGE_MINUTES)
                }

                rate < maxRate -> {
                    BatteryVerdict.PASSED to "%.1f %%/h < %.0f %%/h".format(rate, maxRate)
                }

                else -> {
                    BatteryVerdict.FAILED to "%.1f %%/h ≥ %.0f %%/h".format(rate, maxRate)
                }
            }
        return BatterySection(
            title = title,
            matchId = matchId,
            sport = sport,
            startMs = sorted.firstOrNull()?.wallMs ?: 0,
            endMs = sorted.lastOrNull()?.wallMs ?: 0,
            samples = sorted.size,
            batteryStart = sorted.firstOrNull { it.battery != null }?.battery,
            batteryEnd = sorted.lastOrNull { it.battery != null }?.battery,
            dischargeMinutes = dischargeMinutes,
            chargingMinutes = chargingMs / MINUTE,
            dropPercent = drop,
            ratePerHour = rate,
            chargeDropMah = if (chargeKnown) chargeDropUah / 1000.0 else null,
            screenOnPct = share(on),
            ambientPct = share(ambient),
            offPct = share(off),
            workoutPct = if (spanMs > 0) workoutMs * 100.0 / spanMs else null,
            sent = sent,
            received = received,
            failed = failed,
            points = sorted.lastOrNull { it.points != null }?.points,
            restarts = restarts,
            maxGapMinutes = maxGap / MINUTE,
            segments = segments,
            rises = rises,
            verdict = verdict,
            reason = reason,
        )
    }

    /** Zuwachs eines kumulativen Zählers; nach Prozessneustart zählt der neue Stand ab 0. */
    private fun delta(
        prev: Int,
        cur: Int,
        samePid: Boolean,
    ): Int = if (samePid && cur >= prev) cur - prev else cur
}
