package eu.advantly.presentation.auth

import kotlinx.coroutines.flow.StateFlow

/** [CONNECTION_FAILED]: Request nicht erfolgreich (offline, Server); [FAILED]: lokaler oder Antwortfehler. */
enum class PhonePullResult { COMPLETE, LIMIT_REACHED, BLOCKED, CONNECTION_FAILED, FAILED, CANCELLED }

/** Grund eines blockierten Abgleichs; bei mehreren Rückständen der zuerst genannte. */
enum class PhonePullBlock { PERMISSION, REMOVALS, REDACTIONS, MATERIALIZATION, READINESS }

/** Ergebnis des letzten begrenzten Cloud-Abgleichs dieses Kontos; keine Aussage über spätere Serveränderungen. */
data class PhonePullState(
    val available: Boolean = false,
    val busy: Boolean = false,
    val result: PhonePullResult? = null,
    val blocked: PhonePullBlock? = null,
    /**
     * Dieser Lauf hat den Metadatenabgleich (Matches, Spieler, Freundschaften) bis zur letzten Seite übernommen –
     * auch wenn Event-, Redaktions- oder Materialisierungsrückstände das Ergebnis BLOCKED/FAILED machen.
     */
    val metadataComplete: Boolean = false,
)

/** Phone-Präsentation ohne SDK-, Netzwerk- oder Datenbanktypen. Alle Aufrufe auf dem seriellen Host-Kontext. */
interface PhonePullPort {
    val state: StateFlow<PhonePullState>

    fun pullNow()

    /** Wie [PhoneUploadPort.close]: [onDrained] erst nach vollständigem Ende des Pull-/Bindungs-Childs. */
    fun close(onDrained: () -> Unit)
}
