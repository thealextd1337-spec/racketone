package eu.advantly.data

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
    fun toPlayer(): Player = Player(id, displayName)
}

/** Lokale Spieler: Self-Player, Gäste, von anderen Geräten übernommene Namen (P1 ohne Account). */
class PlayerRepository internal constructor(
    private val queries: PlayerQueries,
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
            queries.insertOrIgnore(id.value, displayName, PlayerKind.GUEST.name, nowMs, null)
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
            val existing = get(player.id)
            when {
                existing == null -> {
                    queries.insertOrIgnore(player.id.value, player.displayName, PlayerKind.GUEST.name, nowMs, null)
                }

                existing.kind == PlayerKind.GUEST && existing.displayName != player.displayName -> {
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

    /** Self zuerst, dann zuletzt verwendete Gäste. */
    fun all(): List<LocalPlayer> = queries.selectAll().executeAsList().map { it.toLocal() }

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
        if (ids.isNotEmpty()) queries.markUsed(nowMs, ids.map { it.value })
    }

    /** Löscht einen Gast; der Self-Player bleibt. Liefert `true`, wenn gelöscht. */
    fun deleteGuest(id: PlayerId): Boolean =
        queries.transactionWithResult {
            val existing = get(id) ?: return@transactionWithResult false
            if (existing.kind == PlayerKind.SELF) return@transactionWithResult false
            queries.deleteGuest(id.value)
            true
        }

    private fun PlayerRow.toLocal(): LocalPlayer =
        LocalPlayer(PlayerId(id), display_name, PlayerKind.valueOf(kind), created_at_ms, last_used_at_ms)
}
