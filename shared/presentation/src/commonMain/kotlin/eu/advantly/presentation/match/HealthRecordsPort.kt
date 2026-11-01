package eu.advantly.presentation.match

import eu.advantly.core.model.MatchId
import eu.advantly.presentation.stats.PulseTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Höchstwerte aus der Gesundheitsaufzeichnung für die Gesamtstatistik am Start; `null` = keine Daten (Anzeige „—“).
 *
 * @property maxBpm höchster gemessener Puls über alle Matches
 * @property maxStepsInSet meiste Schritte in einem Satz über alle Matches
 */
data class HealthRecords(
    val maxBpm: Int? = null,
    val maxStepsInSet: Long? = null,
)

/**
 * Gesundheitsdaten für die Phone-UI (Plan Puls-Highlights): Höchstwerte für den Start und der Pulsverlauf je Match für
 * die Zeitleiste. `ui-phone` darf nicht auf `data` zugreifen, deshalb dieser Port. Bis die Aufzeichnung angebunden ist,
 * liefert der Default ([InMemoryHealthRecords]) nichts. Nur vom UI-Thread benutzen.
 */
interface HealthRecordsPort {
    val records: StateFlow<HealthRecords>

    /** Pulsverlauf des Matches [matchId]; `null` ohne Aufzeichnung (die Zeitleiste zeigt dann nur Highlights). */
    fun pulseTrack(matchId: MatchId): PulseTrack? = null
}

/** Nur im Speicher: Default ohne Aufzeichnung, Tests und Desktop-Preview mit Beispieldaten. */
class InMemoryHealthRecords(
    initial: HealthRecords = HealthRecords(),
) : HealthRecordsPort {
    private val mutable = MutableStateFlow(initial)
    private val tracks = mutableMapOf<MatchId, PulseTrack>()
    override val records: StateFlow<HealthRecords> = mutable.asStateFlow()

    fun setRecords(records: HealthRecords) {
        mutable.value = records
    }

    fun setPulseTrack(
        matchId: MatchId,
        track: PulseTrack,
    ) {
        tracks[matchId] = track
    }

    override fun pulseTrack(matchId: MatchId): PulseTrack? = tracks[matchId]
}
