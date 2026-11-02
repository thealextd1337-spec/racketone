package eu.advantly.match.session

import eu.advantly.core.model.DeviceId
import eu.advantly.core.model.HealthConsent
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.TelemetryVisibility
import eu.advantly.core.scoring.MatchCreated
import eu.advantly.core.scoring.ScoreEvent
import eu.advantly.data.LocalData
import eu.advantly.data.StoredHealthSeries
import eu.advantly.health.HealthQuality
import eu.advantly.health.HealthRecorder
import eu.advantly.health.HealthSeries
import eu.advantly.sync.protocol.HealthAck
import eu.advantly.sync.protocol.HealthConsentSetting
import eu.advantly.sync.protocol.HealthMessage
import eu.advantly.sync.protocol.HealthSeriesMessage
import eu.advantly.sync.protocol.PeerKind
import eu.advantly.sync.protocol.SyncCodec
import kotlin.math.abs

/**
 * Pulsverlauf eines Geräts (Plan Puls H5/H6), gemeinsam für Wear OS und watchOS: Einwilligung, Stufe je Match, Aufnahme
 * mit Zwischenspeicher, Übertragung Uhr → Handy bis zur Bestätigung und Löschung in beide Richtungen. Die Plattform liefert
 * nur Samples (Sample-Zeit als Wanduhr-ms des eigenen Geräts). Nur auf dem Scheduler-Thread der [MatchSession].
 *
 * Ablauf: Die Uhr nimmt nur auf, wenn Einwilligung, Telemetrie (nicht „Gar nicht“) und die Stufe des Matches es erlauben,
 * speichert etwa einmal pro Minute in `health_series` (Fortsetzen nach Prozess-Tod) und sendet am Ende sowie bei jedem
 * Wiederverbinden alle eigenen Serien, bis das Handy sie mit [HealthAck] bestätigt; dann – bei laufendem Match erst nach
 * dessen Ende, spätestens nach 7 Tagen (E5) – löscht sie sie. Das Handy speichert idempotent über
 * `(matchId, sourceDeviceId, revision)`.
 */
internal class HealthSync(
    private val data: LocalData,
    private val kind: PeerKind,
    private val deviceId: () -> DeviceId,
    private val nowMs: () -> Long,
    private val consentKey: () -> String,
    private val broadcast: (HealthMessage) -> Unit,
) {
    var consent: HealthConsent = loadConsent()
        private set

    /** Plan 0.5.1 §5.4: Merker „vom Gerät ohne eigene Entscheidung übernommen“, überdauert Neustarts. */
    private var adoptedUnconfirmed = data.settings.get(confirmKey()) != null

    /**
     * Handy: „an“ kam per Abgleich von der Uhr, ohne dass das Handy je selbst entschieden hat (Zähler 0) – einmal
     * nachfragen. Eine eigene Entscheidung beendet die Frage; ein späteres „aus“ macht sie gegenstandslos.
     */
    val toConfirm: Boolean get() = adoptedUnconfirmed && consent.enabled

    private val recorders = HashMap<MatchId, HealthRecorder>()
    private val savedAtMs = HashMap<MatchId, Long>()

    /** Uhr: kleinstes `Empfang − event.wallClockMs` fremder Events je Match (Plan Puls §3.6). */
    private val offsets = HashMap<MatchId, Long>()

    fun reload() {
        consent = loadConsent()
        adoptedUnconfirmed = data.settings.get(confirmKey()) != null
        recorders.clear()
        savedAtMs.clear()
        offsets.clear()
    }

    // --- Einwilligung und Stufe -------------------------------------------------------------------------------------

    /**
     * Übernimmt [next] (lokal oder mit [merged] per Abgleich); ein Widerruf löscht alle Gesundheitsdaten dieses Geräts.
     * `true` = geändert.
     */
    fun adoptConsent(
        next: HealthConsent,
        merged: Boolean = false,
    ): Boolean {
        if (next == consent) return false
        val setting = checkNotNull(HealthConsentSetting.of(next)) { "Einwilligung ohne Versionsangabe" }
        data.settings.put(consentKey(), SyncCodec.json.encodeToString(HealthConsentSetting.serializer(), setting))
        val unconfirmed =
            if (merged) adoptedUnconfirmed || (kind == PeerKind.PHONE && consent.isDefault && next.enabled) else false
        if (unconfirmed != adoptedUnconfirmed) {
            if (unconfirmed) data.settings.put(confirmKey(), "1") else data.settings.remove(confirmKey())
            adoptedUnconfirmed = unconfirmed
        }
        val revoked = consent.enabled && !next.enabled
        consent = next
        if (revoked) deleteAll()
        return true
    }

    /** Stufe des Matches: nachträgliche Wahl auf diesem Gerät, sonst die beim Start (`MatchMeta`), sonst „Gar nicht“. */
    fun visibility(matchId: MatchId): TelemetryVisibility =
        TelemetryVisibility.fromWire(data.settings.get(PULSE_PREFIX + matchId.value))
            ?: data.matches
                .get(matchId)
                ?.meta
                ?.pulseVisibility
            ?: TelemetryVisibility.OFF

    /** „Gar nicht“ löscht den Pulsverlauf des Matches hier und (per Nachricht) auf den eigenen Geräten. */
    fun setVisibility(
        matchId: MatchId,
        visibility: TelemetryVisibility,
    ) {
        data.settings.put(PULSE_PREFIX + matchId.value, visibility.wire)
        if (visibility == TelemetryVisibility.OFF) {
            forget(matchId)
            broadcast(deletion(matchId))
        }
    }

    /** „Alle Gesundheitsdaten löschen“: Später eintreffende Serien, die vorher begonnen haben, lehnt dieses Gerät ab. */
    fun deleteAll() {
        data.health.deleteAll()
        data.settings.put(DELETED_BEFORE_KEY, nowMs().toString())
        recorders.clear()
        savedAtMs.clear()
        offsets.clear()
    }

    fun forgetMatch(matchId: MatchId) {
        recorders.remove(matchId)
        data.settings.remove(PULSE_PREFIX + matchId.value)
        data.settings.remove(DONE_PREFIX + matchId.value)
    }

    // --- Aufnahme (Uhr) -------------------------------------------------------------------------------------------

    fun allowed(
        matchId: MatchId,
        telemetry: TelemetryVisibility,
    ): Boolean = consent.enabled && telemetry.allowsLive && visibility(matchId) != TelemetryVisibility.OFF

    fun isRecording(
        matchId: MatchId,
        telemetry: TelemetryVisibility,
    ): Boolean = matchId in recorders && allowed(matchId, telemetry)

    fun record(
        matchId: MatchId,
        atMs: Long,
        telemetry: TelemetryVisibility,
        sample: (HealthRecorder) -> Boolean,
    ): Boolean {
        if (!allowed(matchId, telemetry)) return false
        val recorder = recorders[matchId] ?: start(matchId, atMs) ?: return false
        val accepted = sample(recorder)
        if (nowMs() - (savedAtMs[matchId] ?: Long.MIN_VALUE / 2) >= SAVE_INTERVAL_MS) save(matchId)
        return accepted
    }

    /** Ende der Aufnahme (Workout-Ende): letzter Stand speichern, danach senden. */
    fun finish(matchId: MatchId): Boolean {
        if (matchId !in recorders) return false
        save(matchId)
        data.settings.put(DONE_PREFIX + matchId.value, "1")
        recorders.remove(matchId)
        savedAtMs.remove(matchId)
        offsets.remove(matchId)
        return true
    }

    /** Vor Schließen der Session: laufende Aufnahmen sichern (Fortsetzen nach Neustart). */
    fun saveAll() = recorders.keys.toList().forEach(::save)

    /** Uhr: Offset-Kandidaten aus neu empfangenen Events eines aufgenommenen Matches. */
    fun observe(
        matchId: MatchId,
        events: List<ScoreEvent>,
    ) {
        if (matchId !in recorders) return
        val own = deviceId()
        val now = nowMs()
        for (event in events) {
            if (event.deviceId == own || event.payload is MatchCreated) continue
            val sample = now - event.wallClockMs
            // Nachgelieferte Events (Minuten alt) taugen nicht als Messung; echte Uhrenabweichung liegt weit darunter.
            if (abs(sample) <= MAX_OFFSET_MS) offsets[matchId] = minOf(offsets[matchId] ?: sample, sample)
        }
    }

    private fun start(
        matchId: MatchId,
        atMs: Long,
    ): HealthRecorder? {
        // Nach dem Ende nimmt nur eine noch laufende Aufnahme Nachzügler an (keine neue Serie nach dem Senden).
        if (data.matches.get(matchId)?.isFinished != false) return null
        val stored = own(matchId)
        val recorder = stored?.let { HealthRecorder.resume(it.series) } ?: HealthRecorder(atMs)
        stored
            ?.series
            ?.clockOffsetMs
            ?.takeIf { it != 0L }
            ?.let { offsets.getOrPut(matchId) { it } }
        recorders[matchId] = recorder
        return recorder
    }

    private fun save(matchId: MatchId) {
        val recorder = recorders[matchId] ?: return
        val revision = maxOf((own(matchId)?.revision ?: 0) + 1, nowMs())
        val offset = if (kind == PeerKind.PHONE) 0 else offsets[matchId] ?: 0
        data.health.upsert(StoredHealthSeries(matchId, deviceId(), revision, recorder.series(offset)))
        savedAtMs[matchId] = nowMs()
    }

    private fun own(matchId: MatchId): StoredHealthSeries? = data.health.forMatch(matchId).firstOrNull { it.sourceDeviceId == deviceId() }

    private fun forget(matchId: MatchId) {
        data.health.deleteForMatch(matchId)
        data.settings.remove(DONE_PREFIX + matchId.value)
        recorders.remove(matchId)
        savedAtMs.remove(matchId)
        offsets.remove(matchId)
    }

    // --- Übertragung -------------------------------------------------------------------------------------------------

    /**
     * Eigene, abgeschlossene Serien (Aufnahme beendet oder Match zu Ende) bis zur Bestätigung; abgelaufene unbestätigte
     * vorher löschen (E5). Eine nach Prozess-Tod unterbrochene Aufnahme eines laufenden Matches bleibt, bis sie fortgesetzt
     * und beendet ist – sonst ersetzte die Fortsetzung die schon bestätigte Serie durch ihren Rest.
     */
    fun pending(): List<HealthSeriesMessage> {
        if (kind == PeerKind.WATCH) data.health.deleteStartedBefore(nowMs() - RETENTION_MS)
        return data.health
            .forSource(deviceId())
            .filter {
                it.matchId !in recorders &&
                    (data.settings.get(DONE_PREFIX + it.matchId.value) != null || data.matches.get(it.matchId)?.isFinished == true)
            }.map { stored ->
                with(stored.series) {
                    HealthSeriesMessage(
                        stored.matchId,
                        stored.sourceDeviceId,
                        stored.revision,
                        startMs,
                        intervalMs,
                        bpm,
                        steps,
                        clockOffsetMs,
                        quality.name,
                    )
                }
            }
    }

    /**
     * Empfang von [sender] (Geräte-ID und [senderKind] aus dessen `Hello`). Liefert die Antwort an den Absender (Bestätigung
     * bzw. Löschung) oder `null`; [changed] meldet geänderte gespeicherte Daten.
     */
    fun receive(
        sender: DeviceId?,
        senderKind: PeerKind?,
        message: HealthMessage,
        changed: () -> Unit,
    ): HealthMessage? =
        when (message) {
            is HealthAck -> {
                val stored = own(message.matchId)
                if (message.sourceDeviceId == deviceId() && message.matchId !in recorders && stored?.revision == message.revision) {
                    // Läuft das Match noch, bleibt die Serie zum Fortsetzen (neues Workout im selben Match): Eine frische
                    // Aufnahme mit höherer Revision ersetzte sonst die bestätigte Serie beim Handy durch ihren Rest.
                    // Bis zum nächsten Ende nicht erneut senden; Reste räumt die 7-Tage-Grenze ab.
                    val running = data.matches.get(message.matchId)?.isFinished == false
                    if (!running) data.health.delete(message.matchId, message.sourceDeviceId)
                    data.settings.remove(DONE_PREFIX + message.matchId.value)
                }
                null
            }

            is HealthSeriesMessage -> {
                when {
                    // Löschung nur vom Absender selbst; das Handy besitzt die Stufe und löscht alles, sonst nur dessen Serie
                    message.deleted && message.sourceDeviceId != sender -> {
                        null
                    }

                    message.deleted -> {
                        if (senderKind == PeerKind.PHONE) {
                            data.settings.put(PULSE_PREFIX + message.matchId.value, TelemetryVisibility.OFF.wire)
                            forget(message.matchId)
                        } else {
                            data.health.delete(message.matchId, message.sourceDeviceId)
                        }
                        changed()
                        null
                    }

                    // nur die eigene Serie der Gegenstelle; ohne Einwilligung (noch nicht abgeglichen) nichts speichern
                    message.sourceDeviceId != sender || !consent.enabled || data.matches.get(message.matchId) == null -> {
                        null
                    }

                    visibility(message.matchId) == TelemetryVisibility.OFF || message.startMs < deletedBefore() -> {
                        deletion(message.matchId)
                    }

                    else -> {
                        store(message)?.also { changed() }
                    }
                }
            }
        }

    private fun store(message: HealthSeriesMessage): HealthAck? {
        val validBpm = message.bpm.all { it == null || it in HealthRecorder.BPM_RANGE }
        if (!validBpm || message.steps.any { it != null && it < 0 }) return null
        val quality = HealthQuality.entries.firstOrNull { it.name == message.quality } ?: HealthQuality.of(message.bpm, message.intervalMs)
        val series = HealthSeries(message.startMs, message.intervalMs, message.bpm, message.steps, message.clockOffsetMs, quality)
        data.health.upsert(StoredHealthSeries(message.matchId, message.sourceDeviceId, message.revision, series))
        val stored = data.health.forMatch(message.matchId).firstOrNull { it.sourceDeviceId == message.sourceDeviceId } ?: return null
        return if (stored.revision >= message.revision) HealthAck(message.matchId, message.sourceDeviceId, message.revision) else null
    }

    private fun deletion(matchId: MatchId) = HealthSeriesMessage(matchId, deviceId(), revision = 0, deleted = true)

    private fun deletedBefore(): Long = data.settings.get(DELETED_BEFORE_KEY)?.toLongOrNull() ?: Long.MIN_VALUE

    private fun confirmKey(): String = consentKey() + CONFIRM_SUFFIX

    private fun loadConsent(): HealthConsent {
        val stored = data.settings.get(consentKey()) ?: return HealthConsent()
        return try {
            SyncCodec.json.decodeFromString(HealthConsentSetting.serializer(), stored).toConsent()
        } catch (_: IllegalArgumentException) {
            null
        } ?: HealthConsent()
    }

    companion object {
        /** Zwischenspeichern während der Aufnahme (Plan Puls §3.2: etwa 1×/min). */
        const val SAVE_INTERVAL_MS: Long = 60_000

        /** Unbestätigte Serien bleiben höchstens so lange auf der Uhr (E5). */
        const val RETENTION_MS: Long = 7L * 24 * 60 * 60 * 1000

        /** Größere Abstände Empfang − Event-Zeit gelten als Nachlieferung, nicht als Uhrenabweichung. */
        const val MAX_OFFSET_MS: Long = 60_000

        const val CONSENT_KEY = "session.healthConsent"
        private const val CONFIRM_SUFFIX = ".toConfirm"
        private const val PULSE_PREFIX = "health.pulse."
        private const val DELETED_BEFORE_KEY = "health.deletedBefore"

        /** Aufnahme zu diesem Match wurde beendet (Workout-Ende): senden, auch wenn das Match noch läuft. */
        private const val DONE_PREFIX = "health.done."
    }
}
