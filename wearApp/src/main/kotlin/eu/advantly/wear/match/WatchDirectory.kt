package eu.advantly.wear.match

import eu.advantly.core.model.PlayerId
import eu.advantly.data.LocalData
import eu.advantly.data.MatchRecord

/** Gast-Slots eines Uhr-Matches; je Slot ein stabiler Gast-Spieler pro Installation (keine Gast-Flut). */
enum class GuestSlot { PARTNER, OPPONENT_1, OPPONENT_2 }

/**
 * Lesesicht der Uhr auf ihre lokale Datenbank: eigener Spieler, Gast-Spieler, „Wie letztes Mal“, letzte Ergebnisse
 * und fortsetzbare Matches. Schreibt nur Spieler (Self/Gäste); alles Match-Bezogene geht über die `MatchSession`.
 *
 * Nur auf dem Session-Thread verwenden (Main-Thread, `LocalData` ist nicht thread-sicher).
 *
 * @param guestName lokalisierter Anzeigename eines neu angelegten Gast-Slots („Gegner 1“ …)
 */
class WatchDirectory(
    private val data: LocalData,
    private val nowMs: () -> Long,
    private val newId: () -> PlayerId,
    private val guestName: (GuestSlot) -> String,
) {
    /** Eigener Spieler (Side A, „Wir“). Leerer Name: Anzeige „Du“ (wie am Handy, W1-C). */
    fun self(): PlayerId = data.players.ensureSelf(displayName = "", nowMs = nowMs(), newId = newId).id

    /** Stabiler Gast für [slot]; wird beim ersten Aufruf angelegt und danach wiederverwendet. */
    fun guest(slot: GuestSlot): PlayerId {
        val id = PlayerId(data.settings.getOrPut(GUEST_KEY_PREFIX + slot.name) { newId().value })
        if (data.players.get(id) == null) data.players.addGuest(id, guestName(slot), nowMs())
        return id
    }

    /** Alle Slots für [SetupCatalog.lineup]. */
    fun lineupPlayers(): LineupPlayers =
        LineupPlayers(self(), guest(GuestSlot.PARTNER), guest(GuestSlot.OPPONENT_1), guest(GuestSlot.OPPONENT_2))

    /** Anzeigename oder `null` (unbekannt bzw. leer = Standardbezeichnung der UI). */
    fun displayName(id: PlayerId): String? =
        data.players
            .get(id)
            ?.displayName
            ?.takeIf { it.isNotBlank() }

    /**
     * „Wie letztes Mal“: Format, Besetzung und Metadaten des zuletzt **angelegten** Matches (auch vom Handy). Folge-Matches
     * („Noch einer?“, Modus Offen) zählen nicht – wiederholt wird das ursprüngliche Format.
     */
    fun lastSetup(): WatchSetup? =
        data.matches
            .recent(RECENT_SCAN)
            .filter { it.previousMatchId == null }
            .maxWithOrNull(compareBy<MatchRecord> { it.createdAtMs }.thenBy { it.id.value })
            ?.let { WatchSetup(it.format, it.lineup, it.meta) }

    /** Beendete Matches, neueste zuerst (die Historie enthält auch laufende – die fallen hier heraus). */
    fun recentResults(limit: Int = RESULTS): List<MatchRecord> =
        data.matches
            .history(limit = RECENT_SCAN)
            .filter { it.isFinished }
            .take(limit)

    /** Nicht beendetes, gespeichertes Match zum Fortsetzen (z. B. nach Neustart), zuletzt geändertes zuerst. */
    fun resumable(): MatchRecord? = data.matches.active().firstOrNull()

    private companion object {
        const val GUEST_KEY_PREFIX = "wear.guest."
        const val RECENT_SCAN = 30L
        const val RESULTS = 5
    }
}
