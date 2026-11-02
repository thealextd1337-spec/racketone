package eu.advantly.presentation.match

import eu.advantly.core.model.MatchId
import eu.advantly.core.model.TelemetryVisibility
import eu.advantly.data.StoredHealthSeries
import eu.advantly.match.session.HealthArchive
import eu.advantly.match.session.HealthArchiveContent
import eu.advantly.match.session.HealthImportPlacement
import eu.advantly.match.session.HealthImportResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Telemetrie-Sichtbarkeit für Puls und Schritte (P2-J, F19/F29): „Teilen“, „Nur für mich“ (Standard) oder „Gar nicht“,
 * dazu die Einwilligung „Pulsverlauf aufzeichnen“ und die Stufe je Match (Plan Puls H5). Wirkt auf Uhr und Handy; die
 * App-Implementierung speichert über die Match-Session und gleicht mit der eigenen Uhr ab. Nur vom UI-Thread benutzen.
 */
interface TelemetrySettingsPort {
    val telemetryVisibility: StateFlow<TelemetryVisibility>

    fun setTelemetryVisibility(visibility: TelemetryVisibility)

    /** Einwilligung „Pulsverlauf aufzeichnen“ (E1, Standard aus); wirkt nur, solange die Telemetrie nicht „Gar nicht“ ist. */
    val healthConsent: StateFlow<Boolean>

    /** Ein Widerruf löscht alle Pulsverläufe auf Handy und Uhr (die Rückfrage stellt die UI). */
    fun setHealthConsent(enabled: Boolean)

    /** Auf diesem Gerät noch nie entschieden (`HealthConsent.isDefault`): Einstieg und „Was ist neu?“ fragen (Plan 0.5.1 §5/§6). */
    val healthConsentOpen: StateFlow<Boolean>

    /** „an“ kam von der Uhr, ohne eigene Entscheidung des Handys: einmal nachfragen (Plan 0.5.1 §5.4). */
    val healthConsentToConfirm: StateFlow<Boolean>

    /** Antwort auf die Rückfrage: [keep] = beibehalten, sonst Widerruf (löscht wie [setHealthConsent]). */
    fun confirmHealthConsent(keep: Boolean)

    /** „Alle Gesundheitsdaten löschen“ auf diesem Gerät. */
    fun deleteAllHealthData()

    /**
     * „Pulsverläufe exportieren“ (Plan 0.5.1 §7b): alle lesbaren Serien, leer ohne Serien. Die Datei baut der Aufrufer mit
     * [HealthArchive.encodeParts] abseits des UI-Threads.
     */
    fun exportHealthSeries(): List<StoredHealthSeries>

    /**
     * „Pulsverläufe importieren“: [content] hat der Aufrufer abseits des UI-Threads geprüft ([HealthArchive.decode]);
     * ordnet über die Match-ID zu, höhere Revision gewinnt.
     */
    fun importHealth(content: HealthArchiveContent): HealthImportResult

    /** Steigt, wenn sich Pulsverläufe oder Stufen geändert haben können ([pulseSetting] erneut lesen). */
    val healthRevision: StateFlow<Long>

    fun pulseSetting(matchId: MatchId): PulseSetting

    /** Stufe nachträglich ändern; „Gar nicht“ löscht den Pulsverlauf auf Handy und Uhr (Rückfrage in der UI). */
    fun setPulseVisibility(
        matchId: MatchId,
        visibility: TelemetryVisibility,
    )
}

/**
 * Pulsverlauf eines Matches im Match-Detail (E2).
 *
 * @property visibility wirksame Stufe („Gar nicht“, „Nur für mich“, „Für alle“)
 * @property recorded es liegt ein Pulsverlauf vor; ohne sind nur „Gar nicht“ wählbar
 */
data class PulseSetting(
    val visibility: TelemetryVisibility = TelemetryVisibility.OFF,
    val recorded: Boolean = false,
)

/** Nur im Speicher (Tests, Previews). */
class InMemoryTelemetrySettings(
    initial: TelemetryVisibility = TelemetryVisibility.DEFAULT,
    consent: Boolean = false,
    toConfirm: Boolean = false,
) : TelemetrySettingsPort {
    private val mutable = MutableStateFlow(initial)
    private val consentFlow = MutableStateFlow(consent)
    private val openFlow = MutableStateFlow(!consent)
    private val toConfirmFlow = MutableStateFlow(toConfirm && consent)
    override val healthConsentOpen: StateFlow<Boolean> = openFlow.asStateFlow()
    override val healthConsentToConfirm: StateFlow<Boolean> = toConfirmFlow.asStateFlow()
    private val revisionFlow = MutableStateFlow(0L)
    private val pulse = mutableMapOf<MatchId, PulseSetting>()
    override val telemetryVisibility: StateFlow<TelemetryVisibility> = mutable.asStateFlow()
    override val healthConsent: StateFlow<Boolean> = consentFlow.asStateFlow()
    override val healthRevision: StateFlow<Long> = revisionFlow.asStateFlow()

    override fun setTelemetryVisibility(visibility: TelemetryVisibility) {
        mutable.value = visibility
    }

    override fun setHealthConsent(enabled: Boolean) {
        if (consentFlow.value == enabled) return
        if (consentFlow.value) deleteAllHealthData()
        consentFlow.value = enabled
        openFlow.value = false
        toConfirmFlow.value = false
    }

    override fun confirmHealthConsent(keep: Boolean) {
        if (!toConfirmFlow.value) return
        toConfirmFlow.value = false
        if (!keep) setHealthConsent(false)
    }

    override fun deleteAllHealthData() {
        pulse.keys.toList().forEach { pulse[it] = pulse.getValue(it).copy(recorded = false) }
        revisionFlow.value++
    }

    /** Ohne Serien im Speicher: keine Datei. */
    override fun exportHealthSeries(): List<StoredHealthSeries> = emptyList()

    /** Wie die App; ohne Matches im Speicher fehlt jeder Serie das passende Match. */
    override fun importHealth(content: HealthArchiveContent): HealthImportResult =
        HealthArchive.import(content, consentFlow.value) { HealthImportPlacement.UNKNOWN_MATCH }

    override fun pulseSetting(matchId: MatchId): PulseSetting = pulse[matchId] ?: PulseSetting()

    /** Tests/Previews: Ausgangslage eines Matches. */
    fun setPulse(
        matchId: MatchId,
        setting: PulseSetting,
    ) {
        pulse[matchId] = setting
        revisionFlow.value++
    }

    override fun setPulseVisibility(
        matchId: MatchId,
        visibility: TelemetryVisibility,
    ) {
        val current = pulseSetting(matchId)
        setPulse(matchId, PulseSetting(visibility, current.recorded && visibility != TelemetryVisibility.OFF))
    }
}
