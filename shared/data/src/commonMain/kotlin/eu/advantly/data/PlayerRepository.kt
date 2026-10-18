package eu.advantly.data

import eu.advantly.core.model.Placeholders
import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId
import eu.advantly.data.db.PlayerQueries
import eu.advantly.data.db.Player as PlayerRow

/** Art eines lokalen Spielers. */
enum class PlayerKind {
    /** Der Besitzer des Geräts („Ich“) – genau einer je Installation, stabile lokale UUID. */
    SELF,

    /** Gast bzw. Mitspieler ohne Account (auch von einem anderen Gerät übernommen). */
    GUEST,

    /** Nur für migrierte Uhr-Slots; niemals in Personenlisten. */
    PLACEHOLDER,

    /** Aus der Auswahl entfernt; Name und ID bleiben für vergangene Matches erhalten. */
    ARCHIVED,
}

/** Lokal bekannter Spieler. Regeln referenzieren nur [id]; [displayName] ist reine Anzeige. */
data class LocalPlayer(
    val id: PlayerId,
    val displayName: String,
    val kind: PlayerKind,
    val createdAtMs: Long,
    val lastUsedAtMs: Long?,
) {
    /** Anzeigeform für Lineup/Sync. */
    fun toPlayer(): Player = Player(id, displayName, isPlaceholder = kind == PlayerKind.PLACEHOLDER)
}

/** Lokale Spieler: Self-Player, Gäste, von anderen Geräten übernommene Namen (P1 ohne Account). */
class PlayerRepository internal constructor(
    private val queries: PlayerQueries,
    private val locallyCreated: (String) -> Unit = {},
) {
    /** Der Self-Player oder `null`, wenn noch keiner angelegt ist. */
    fun self(): LocalPlayer? = queries.selectSelf().executeAsOneOrNull()?.toLocal()

    /**
     * Liefert den Self-Player und legt ihn beim ersten Aufruf mit [newId] (stabile lokale UUID, extern erzeugt) an.
     * Spätere Aufrufe ändern nichts – auch nicht den Namen (dafür [rename]).
     */
    fun ensureSelf(
        displayName: String,
        nowMs: Long,
        newId: () -> PlayerId,
    ): LocalPlayer =
        queries.transactionWithResult {
            self() ?: run {
                val id = newId()
                queries.insertOrIgnore(id.value, displayName, PlayerKind.SELF.name, nowMs, null)
                locallyCreated(id.value)
                checkNotNull(get(id))
            }
        }

    /** Neuer Gast (oder unveränderter, falls [id] schon existiert). */
    fun addGuest(
        id: PlayerId,
        displayName: String,
        nowMs: Long,
    ): LocalPlayer =
        queries.transactionWithResult {
            require(!Placeholders.isPlaceholder(id)) { "Ein Platzhalter ist kein Gast" }
            val exists = get(id) != null
            queries.insertOrIgnore(id.value, displayName, PlayerKind.GUEST.name, nowMs, null)
            if (!exists) locallyCreated(id.value)
            checkNotNull(get(id))
        }

    /**
     * Übernimmt einen Spieler von einem anderen Gerät (`MatchMeta`): unbekannt → Gast; bekannt → Name aktualisieren.
     * Der eigene Self-Player wird nie umbenannt.
     */
    fun upsertRemote(
        player: Player,
        nowMs: Long,
    ) {
        queries.transaction {
            if (Placeholders.isPlaceholder(player.id)) return@transaction
            if (player.isPlaceholder) {
                if (canMarkPlaceholder(player.id)) markPlaceholder(player.id, nowMs)
                return@transaction
            }
            val existing = get(player.id)
            if (existing?.kind == PlayerKind.PLACEHOLDER && !isDefaultSlotName(player.displayName) && player.displayName.isNotBlank()) {
                queries.updateKind(PlayerKind.GUEST.name, player.id.value)
                queries.rename(player.displayName, player.id.value)
                return@transaction
            }
            when {
                existing == null -> {
                    queries.insertOrIgnore(player.id.value, player.displayName, PlayerKind.GUEST.name, nowMs, null)
                }

                (existing.kind == PlayerKind.GUEST || (existing.kind == PlayerKind.ARCHIVED && existing.displayName.isBlank())) &&
                    existing.displayName != player.displayName -> {
                    queries.rename(
                        player.displayName,
                        player.id.value,
                    )
                }

                else -> {
                    Unit
                }
            }
        }
    }

    /**
     * Macht [owner] zum Self-Player (Uhr übernimmt die Identität des Handys, W1-I). Der bisherige Self bleibt als
     * Gast-Zeile erhalten (Alias-Verwaltung: [LocalData.adoptSelf]). Gleiche ID: nur der Name wird übernommen.
     * Liefert den bisherigen Self, falls er ersetzt wurde, sonst `null`.
     */
    fun adoptSelf(
        owner: Player,
        nowMs: Long,
    ): LocalPlayer? =
        queries.transactionWithResult {
            val current = self()
            if (current?.id == owner.id) {
                if (owner.displayName.isNotBlank() &&
                    owner.displayName != current.displayName
                ) {
                    queries.rename(owner.displayName, owner.id.value)
                }
                return@transactionWithResult null
            }
            current?.let { queries.updateKind(PlayerKind.GUEST.name, it.id.value) }
            if (get(owner.id) == null) {
                queries.insertOrIgnore(owner.id.value, owner.displayName, PlayerKind.SELF.name, nowMs, null)
            } else {
                queries.updateKind(PlayerKind.SELF.name, owner.id.value)
                queries.rename(owner.displayName, owner.id.value)
            }
            current
        }

    fun get(id: PlayerId): LocalPlayer? = queries.selectById(id.value).executeAsOneOrNull()?.toLocal()

    /** Kennzeichnung alter Uhr-Gäste ohne Namensverlust; echte Namen können sie zurückkorrigieren. */
    fun markPlaceholder(
        id: PlayerId,
        nowMs: Long,
    ) {
        if (Placeholders.isPlaceholder(id) || get(id)?.kind in setOf(PlayerKind.SELF, PlayerKind.ARCHIVED)) return
        queries.insertOrIgnore(id.value, "", PlayerKind.PLACEHOLDER.name, nowMs, null)
        queries.updateKind(PlayerKind.PLACEHOLDER.name, id.value)
    }

    fun canMarkPlaceholder(id: PlayerId): Boolean =
        get(id).let {
            it == null || (
                it.kind != PlayerKind.SELF && it.kind != PlayerKind.ARCHIVED &&
                    (isDefaultSlotName(it.displayName) || (it.kind == PlayerKind.PLACEHOLDER && it.displayName.isBlank()))
            )
        }

    private fun isDefaultSlotName(name: String) =
        name in setOf("Partner", "Gegner", "Opponent", "Gegner 1", "Opponent 1", "Gegner 2", "Opponent 2")

    fun placeholderIds(): Set<PlayerId> =
        queries
            .placeholderIds()
            .executeAsList()
            .map(::PlayerId)
            .toSet()

    /** Self zuerst, dann zuletzt verwendete Gäste. */
    fun all(): List<LocalPlayer> =
        queries
            .selectAll()
            .executeAsList()
            .filter { it.kind == PlayerKind.SELF.name || it.kind == PlayerKind.GUEST.name }
            .map { it.toLocal() }

    fun archivedIds(): Set<PlayerId> =
        queries
            .selectAll()
            .executeAsList()
            .filter { it.kind == PlayerKind.ARCHIVED.name }
            .map { PlayerId(it.id) }
            .toSet()

    fun rename(
        id: PlayerId,
        displayName: String,
    ) {
        queries.rename(displayName, id.value)
    }

    /** Merkt die Verwendung in einem Match (Sortierung „Zuletzt“ im Wizard). */
    fun markUsed(
        ids: Collection<PlayerId>,
        nowMs: Long,
    ) {
        val people = ids.filter { get(it)?.kind != PlayerKind.PLACEHOLDER && !Placeholders.isPlaceholder(it) }
        if (people.isNotEmpty()) queries.markUsed(nowMs, people.map { it.value })
    }

    /** Archiviert einen Gast ohne Namensverlust. Self, Platzhalter und bereits archivierte bleiben unverändert. */
    fun deleteGuest(id: PlayerId): Boolean =
        queries.transactionWithResult {
            val existing = get(id) ?: return@transactionWithResult false
            if (existing.kind != PlayerKind.GUEST) return@transactionWithResult false
            queries.deleteGuest(id.value)
            true
        }

    /** Archivmarkierung vom Handy; schützt auch vor später eintreffenden alten Metadaten. */
    fun archiveRemote(
        id: PlayerId,
        nowMs: Long,
    ) {
        if (Placeholders.isPlaceholder(id)) return
        queries.transaction {
            queries.insertOrIgnore(id.value, "", PlayerKind.ARCHIVED.name, nowMs, null)
            if (get(id)?.kind == PlayerKind.GUEST) queries.deleteGuest(id.value)
        }
    }

    private fun PlayerRow.toLocal(): LocalPlayer =
        LocalPlayer(PlayerId(id), display_name, PlayerKind.valueOf(kind), created_at_ms, last_used_at_ms)
}
