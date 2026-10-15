package eu.advantly.wear.match

import eu.advantly.core.model.EventId
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.Placeholders
import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId
import eu.advantly.core.scoring.ServerChosen
import eu.advantly.data.LocalData
import eu.advantly.data.MatchRecord
import eu.advantly.data.freshPlaceholders
import eu.advantly.data.resolvedLineup
import eu.advantly.data.toScoreEventOrNull
import eu.advantly.data.watchPeople

/** Optionale Personenplätze eines Uhr-Matches. */
enum class PlayerSlot { PARTNER, OPPONENT_1, OPPONENT_2 }

/** Lokale Lesesicht der Uhr; Match-Änderungen gehen ausschließlich über die Session. */
class WatchDirectory(
    private val data: LocalData,
    private val nowMs: () -> Long,
    private val newId: () -> PlayerId,
) {
    /**
     * Eigener Spieler (Side A, „Wir“) – bei **jedem** neuen Match frisch gelesen: Nach dem ersten `Hello` übernimmt die
     * Session die Identität des Handys (`LocalData.adoptSelf`, match-session README „Identität“). Ohne Handy legt die
     * Uhr einen eigenen Self an (leerer Name, Anzeige „Du“), der später Alias wird.
     */
    fun self(): PlayerId = data.players.ensureSelf(displayName = "", nowMs = nowMs(), newId = newId).id

    /** [id] ist der Besitzer (aktueller Self oder ein früherer Self/Alias) – Anzeige „Du“. */
    fun isSelf(id: PlayerId): Boolean = data.isSelf(id)

    init {
        data.migrateWatchPlaceholders()
    }

    /** Ein unbesetzter Platz hat nur eine Log-ID; keine Spieler- oder Settings-Zeile. */
    fun placeholder(slot: PlayerSlot): PlayerId = Placeholders.id(slot.name.lowercase() + ":" + newId().value)

    fun lineupPlayers(selected: Map<PlayerSlot, PlayerId> = emptyMap()): LineupPlayers =
        LineupPlayers(
            self(),
            selected[PlayerSlot.PARTNER] ?: placeholder(PlayerSlot.PARTNER),
            selected[PlayerSlot.OPPONENT_1] ?: placeholder(PlayerSlot.OPPONENT_1),
            selected[PlayerSlot.OPPONENT_2] ?: placeholder(PlayerSlot.OPPONENT_2),
        )

    /** Handy-Verzeichnis mit lokaler Offline-Historie, ohne archivierte Personen. */
    fun recentPlayers(): List<Player> = data.watchPeople().map { it.toPlayer() }

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
            ?.let { WatchSetup(it.format, data.freshPlaceholders(data.resolvedLineup(it), newId), it.meta) }

    /** Beendete Matches, neueste zuerst (die Historie enthält auch laufende – die fallen hier heraus). */
    fun recentResults(limit: Int = RESULTS): List<MatchRecord> =
        data.matches
            .history(limit = RECENT_SCAN)
            .filter { it.isFinished }
            .take(limit)

    /** Alle gespeicherten `ServerChosen` des Matches (nur diese Event-Art wird dekodiert). */
    fun serverChosen(matchId: MatchId): List<Pair<EventId, ServerChosen>> =
        data.events
            .eventsFor(matchId.value)
            .filter { it.type == SERVER_CHOSEN }
            .mapNotNull { it.toScoreEventOrNull() }
            .mapNotNull { event -> (event.payload as? ServerChosen)?.let { event.id to it } }

    /** Nicht beendetes, gespeichertes Match zum Fortsetzen (z. B. nach Neustart), zuletzt geändertes zuerst. */
    fun resumable(): MatchRecord? = data.matches.active().firstOrNull()

    private companion object {
        const val SERVER_CHOSEN = "ServerChosen"
        const val RECENT_SCAN = 30L
        const val RESULTS = 5
    }
}
