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
    private val sourcePlayerCreated: (String) -> Unit = {},
    private val redacted: (String) -> Boolean = { false },
    private val writable: (String) -> Boolean = { true },
) {
    /** Der Self-Player oder `null`, wenn noch keiner angelegt ist. */
    fun self(): LocalPlayer? = queries.selectReadableSelf().executeAsOneOrNull()?.toLocal()

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
                checkNotNull(rawGet(id))
            }
        }

    /** Neuer Gast (oder unveränderter, falls [id] schon existiert). */
    fun addGuest(
        id: PlayerId,
        displayName: String,
        nowMs: Long,
    ): LocalPlayer =
        queries.transactionWithResult {
            check(writable(id.value))
            require(!Placeholders.isPlaceholder(id)) { "Ein Platzhalter ist kein Gast" }
            val exists = rawGet(id) != null
            queries.insertOrIgnore(id.value, displayName, PlayerKind.GUEST.name, nowMs, null)
            if (!exists) locallyCreated(id.value)
            checkNotNull(rawGet(id))
        }

    /**
     * Übernimmt einen Spieler von einem anderen Gerät (`MatchMeta`): unbekannt → Gast; bekannt → Name aktualisieren.
     * Der eigene Self-Player wird nie umbenannt.
     */
    fun upsertRemote(
        player: Player,
        nowMs: Long,
    ) {
        if (redacted(player.id.value) || !writable(player.id.value)) return
        queries.transaction {
            if (Placeholders.isPlaceholder(player.id)) return@transaction
            if (player.isPlaceholder) {
                if (canMarkPlaceholder(player.id)) markPlaceholder(player.id, nowMs)
                return@transaction
            }
            val existing = rawGet(player.id)
            if (existing?.kind == PlayerKind.PLACEHOLDER && !isDefaultSlotName(player.displayName) && player.displayName.isNotBlank()) {
                queries.updateKind(PlayerKind.GUEST.name, player.id.value)
                queries.rename(player.displayName, player.id.value)
                return@transaction
            }
            when {
                existing == null -> {
                    queries.insertOrIgnore(player.id.value, player.displayName, PlayerKind.GUEST.name, nowMs, null)
                    sourcePlayerCreated(player.id.value)
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
            val current = queries.selectSelf().executeAsOneOrNull()?.toLocal()
            if (current?.id == owner.id) {
                if (owner.displayName.isNotBlank() &&
                    owner.displayName != current.displayName
                ) {
                    queries.rename(owner.displayName, owner.id.value)
                }
                return@transactionWithResult null
            }
            current?.let { queries.updateKind(PlayerKind.GUEST.name, it.id.value) }
            if (rawGet(owner.id) == null) {
                queries.insertOrIgnore(owner.id.value, owner.displayName, PlayerKind.SELF.name, nowMs, null)
                sourcePlayerCreated(owner.id.value)
            } else {
                queries.updateKind(PlayerKind.SELF.name, owner.id.value)
                if (owner.displayName.isNotBlank()) queries.rename(owner.displayName, owner.id.value)
            }
            current
        }

    fun get(id: PlayerId): LocalPlayer? = queries.selectReadableById(id.value).executeAsOneOrNull()?.toLocal()

    private fun rawGet(id: PlayerId): LocalPlayer? = queries.selectById(id.value).executeAsOneOrNull()?.toLocal()

    /** Kennzeichnung alter Uhr-Gäste ohne Namensverlust; echte Namen können sie zurückkorrigieren. */
    fun markPlaceholder(
        id: PlayerId,
        nowMs: Long,
    ) {
        if (!writable(id.value) || Placeholders.isPlaceholder(id) || rawGet(id)?.kind in setOf(PlayerKind.SELF, PlayerKind.ARCHIVED)) return
        queries.transaction {
            val exists = rawGet(id) != null
            queries.insertOrIgnore(id.value, "", PlayerKind.PLACEHOLDER.name, nowMs, null)
            if (!exists) sourcePlayerCreated(id.value)
            queries.updateKind(PlayerKind.PLACEHOLDER.name, id.value)
        }
    }

    fun canMarkPlaceholder(id: PlayerId): Boolean =
        rawGet(id).let {
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
            .selectReadableAll()
            .executeAsList()
            .filter { it.kind == PlayerKind.SELF.name || it.kind == PlayerKind.GUEST.name }
            .map { it.toLocal() }

    fun archivedIds(): Set<PlayerId> =
        queries
            .selectReadableAll()
            .executeAsList()
            .filter { it.kind == PlayerKind.ARCHIVED.name }
            .map { PlayerId(it.id) }
            .toSet()

    fun rename(
        id: PlayerId,
        displayName: String,
    ) {
        check(writable(id.value))
        queries.rename(if (redacted(id.value)) "" else displayName, id.value)
    }

    /** Merkt die Verwendung in einem Match (Sortierung „Zuletzt“ im Wizard). */
    fun markUsed(
        ids: Collection<PlayerId>,
        nowMs: Long,
    ) {
        val people = ids.filter { writable(it.value) && get(it)?.kind != PlayerKind.PLACEHOLDER && !Placeholders.isPlaceholder(it) }
        if (people.isNotEmpty()) queries.markUsed(nowMs, people.map { it.value })
    }

    /** Archiviert einen Gast ohne Namensverlust. Self, Platzhalter und bereits archivierte bleiben unverändert. */
    fun deleteGuest(id: PlayerId): Boolean =
        queries.transactionWithResult {
            if (!writable(id.value)) return@transactionWithResult false
            val existing = rawGet(id) ?: return@transactionWithResult false
            if (existing.kind != PlayerKind.GUEST) return@transactionWithResult false
            queries.deleteGuest(id.value)
            true
        }

    /** Archivmarkierung vom Handy; schützt auch vor später eintreffenden alten Metadaten. */
    fun archiveRemote(
        id: PlayerId,
        nowMs: Long,
    ) {
        if (!writable(id.value) || Placeholders.isPlaceholder(id)) return
        queries.transaction {
            val exists = rawGet(id) != null
            queries.insertOrIgnore(id.value, "", PlayerKind.ARCHIVED.name, nowMs, null)
            if (!exists) sourcePlayerCreated(id.value)
            if (rawGet(id)?.kind == PlayerKind.GUEST) queries.deleteGuest(id.value)
        }
    }

    private fun PlayerRow.toLocal(): LocalPlayer =
        LocalPlayer(PlayerId(id), if (redacted(id)) "" else display_name, PlayerKind.valueOf(kind), created_at_ms, last_used_at_ms)
}
