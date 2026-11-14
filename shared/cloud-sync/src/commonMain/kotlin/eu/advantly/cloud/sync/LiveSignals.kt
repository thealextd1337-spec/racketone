package eu.advantly.cloud.sync

import kotlinx.coroutines.flow.Flow

/** Zustand des Live-Signalkanals (Plan W12 Paket 3); Inhalte kommen nie über den Kanal, nur der Anstoß zum Neuladen. */
enum class LiveSignal {
    /** Kanal steht: verpasste Änderungen sofort nachladen, danach genügt seltenes Polling. */
    SUBSCRIBED,

    /** Live-Stand oder Sichtbarkeit hat sich geändert (Ereignis `changed`, leerer Payload). */
    CHANGED,

    /** Kanal getrennt oder Beitritt abgelehnt: Polling wie ohne Realtime, bis wieder [SUBSCRIBED] kommt. */
    LOST,
}

/** Privater Kanal `live:<profileId>` (Migration 20261114000300); ohne Port bleibt es beim Polling (iOS). */
interface LiveSignalsPort {
    /** Signale für [profileId], solange gesammelt wird; Abbruch des Sammelns verlässt den Kanal. */
    fun signals(profileId: String): Flow<LiveSignal>
}
