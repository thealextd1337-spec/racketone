package eu.advantly.data

import app.cash.sqldelight.Query
import app.cash.sqldelight.db.SqlDriver
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.Placeholders
import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId
import eu.advantly.core.scoring.MatchCreated
import eu.advantly.core.scoring.PlayerAssignments
import eu.advantly.data.db.AdvantlyDatabase

/**
 * Lokale Datenbank eines Geräts (01 §5.1, Single Source of Truth): Event-Log, Match-Köpfe, Spieler,
 * Favoriten-Lineups und Settings über **einen** [SqlDriver].
 *
 * Erzeugen: `createInMemoryJvmLocalData()` (Tests/Desktop), `createAndroidLocalData(context)`,
 * `createNativeLocalData()` (iOS/watchOS). Synchron und nicht thread-sicher – ein Thread bzw. eine serielle Queue.
 */
class LocalData(
    driver: SqlDriver,
) {
    private val database = AdvantlyDatabase(driver)
    val replicas: ReplicaLifecycleStore = ReplicaLifecycleStore(database, this)
    private val rawEvents: EventStore = SqlDelightEventStore(database.matchEventQueries) { rebuildAssignments(MatchId(it)) }
    val events: EventStore = ScopedEventStore(rawEvents, replicas) { block -> transaction(block) }
    val matches: MatchRepository =
        MatchRepository(
            database.matchHeaderQueries,
            database.matchTombstoneQueries,
            ::assignmentIds,
            requireReadable = { replicas.requireReadable(it) },
            requireInsert = { replicas.requireHeader(it) },
            created = {
                cloud.resolveMatchMetadataSource(it)
                replicas.noteUnresolvedImport(it)
            },
        )
    val players: PlayerRepository =
        PlayerRepository(
            database.playerQueries,
            locallyCreated = { cloud.recordLocalPlayer(it) },
            sourcePlayerCreated = { cloud.resolvePlayerMetadataSources(it) },
            redacted = { replicas.isRedacted(it) },
            writable = { replicas.writablePlayer(it) },
        )
    val favorites: LineupFavoriteRepository =
        LineupFavoriteRepository(
            database.lineupFavoriteQueries,
            candidates = { sport, limit -> replicas.favoriteCandidates(sport, limit) },
            readable = { replicas.readableFavorite(it) },
            readablePlayer = { replicas.readablePlayer(it) },
            redacted = { replicas.isRedacted(it) },
        )
    val settings: SettingsRepository = SettingsRepository(database.settingQueries)
    val cloud: CloudSyncRepository = CloudSyncRepository(database, rawEvents, replicas::sanitizeLegacyEvent, ::redactedAssignmentSlots)
    val cloudPulls: CloudPullStore = CloudPullStore(database, this)
    val cloudUploads: CloudUploadStore = CloudUploadStore(database, cloud, driver)
    val accountIdentities: AccountIdentityRepository = AccountIdentityRepository(database, this)
    private val identityListeners = mutableSetOf<() -> Unit>()

    /**
     * Invalidierung der Eingaben für kompakte Verzeichnisansichten, ohne Abfrage oder Event-Replay im Callback.
     * Favoriten und reine Session-/Sync-Statuswechsel lösen keine Invalidierung aus. Rückgabe entfernt die Registrierung.
     */
    fun observeDirectoryChanges(onChanged: () -> Unit): () -> Unit {
        val queries =
            listOf(
                database.matchHeaderQueries.observeHeaders(),
                database.replicaLifecycleQueries.observeAccess(),
                database.replicaLifecycleQueries.observeEventRedactions(),
                database.matchAssignmentQueries.forMatch(""),
                database.playerQueries.selectAll(),
            )
        val listener = Query.Listener(onChanged)
        queries.forEach { it.addListener(listener) }
        identityListeners.add(onChanged)
        return {
            queries.forEach { it.removeListener(listener) }
            identityListeners.remove(onChanged)
        }
    }

    init {
        if (settings.get("assignments.projectionVersion") != "2") {
            rebuildAllAssignments()
            settings.put("assignments.projectionVersion", "2")
        }
    }

    /** Wiederherstellung aus Identitäts-Events; Score-Events werden nicht gelesen. */
    fun rebuildAllAssignments() {
        transaction {
            database.matchAssignmentQueries.clearAll()
            database.matchEventQueries
                .assignmentMatches()
                .executeAsList()
                .forEach { rebuildAssignments(MatchId(it)) }
        }
    }

    fun rebuildAssignmentsForSlot(slot: PlayerId) {
        database.matchEventQueries
            .assignmentMatchesForSlot(slot.value)
            .executeAsList()
            .forEach { rebuildAssignments(MatchId(it)) }
    }

    private fun identityEvents(id: MatchId) =
        database.matchEventQueries
            .identityEvents(id.value) { eventId, match, device, seq, lamport, at, parent, type, payload ->
                StoredEvent(eventId, match, device, seq, lamport, payload, at, parent, type)
            }.executeAsList()
            .mapNotNull { it.toScoreEventOrNull() }

    fun matchCreator(id: MatchId): PlayerId? =
        (if (replicas.readable(id.value)) identityEvents(id) else emptyList())
            .firstOrNull {
                it.payload is MatchCreated
            }?.let {
                val created = it.payload as MatchCreated
                created.createdBy ?: created.lineup.a.first()
            }

    fun rebuildAssignments(id: MatchId) {
        transaction {
            cloud.resolveMatchMetadataSource(id.value)
            val identities = identityEvents(id)
            val projection = PlayerAssignments.projectIds(identities, players.placeholderIds())
            val legacy = PlayerAssignments.project(identities, players.placeholderIds())
            val sources = PlayerAssignments.assignmentEventIds(identities, players.placeholderIds())
            val previousRows = database.matchAssignmentQueries.identitiesForMatch(id.value).executeAsList()
            val previous =
                previousRows
                    .associate { row ->
                        val playerId = row.player_id ?: row.player_json?.let { DataJson.decodeFromString(Player.serializer(), it).id.value }
                        row.slot_id to (playerId to row.player_json)
                    }
            val next =
                projection.slots.associate { slot ->
                    val playerId = projection.peopleBySlot[slot]
                    // Eventnamen sind nur Matchanzeige, niemals ein Verzeichnis- oder Eigentumsbeleg.
                    val fallback =
                        legacy.people[slot]
                            ?.takeIf { player ->
                                player.id == playerId && !replicas.isRedacted(player.id.value) &&
                                    database.replicaLifecycleQueries.playerExists(player.id.value).executeAsOne() == 0L
                            }?.let { DataJson.encodeToString(Player.serializer(), it) }
                    slot.value to (playerId?.value to fallback)
                }
            if (previous != next) {
                database.matchAssignmentQueries.clearMatch(id.value)
                next.forEach { (slot, person) ->
                    database.matchAssignmentQueries.putIdentity(id.value, slot, person.first, person.second)
                }
            }
            next.keys.forEach { slot ->
                val source = sources[PlayerId(slot)]?.value
                val previousSource = if (previous == next) previousRows.firstOrNull { it.slot_id == slot }?.source_event_id else null
                if (source != previousSource) database.matchAssignmentQueries.recordSource(source, id.value, slot)
            }
        }
    }

    private fun redactedAssignmentSlots(matchId: String): Set<String> =
        database.matchAssignmentQueries
            .redactedSlots(matchId)
            .executeAsList()
            .toSet()

    private fun storedProjection(id: MatchId): Map<PlayerId, Player?> {
        if (!replicas.readable(id.value)) return emptyMap()
        val redactedSlots = redactedAssignmentSlots(id.value)
        return database.matchAssignmentQueries.identitiesForMatch(id.value).executeAsList().associate { row ->
            val legacy =
                row.player_json
                    ?.let { DataJson.decodeFromString(Player.serializer(), it) }
                    ?.takeIf { row.player_id == null || row.player_id == it.id.value }
            val playerId = row.player_id?.let(::PlayerId) ?: legacy?.id
            val player = playerId?.let { players.get(it)?.toPlayer() ?: legacy }
            PlayerId(row.slot_id) to
                player?.let {
                    if (row.slot_id in redactedSlots || replicas.isRedacted(it.id.value)) it.copy(displayName = "") else it
                }
        }
    }

    internal fun storedAssignments(id: MatchId): Map<PlayerId, Player> =
        storedProjection(id).mapNotNull { (slot, player) -> player?.let { slot to it } }.toMap()

    fun isMatchPlaceholder(
        matchId: MatchId,
        slot: PlayerId,
    ): Boolean =
        replicas.readable(matchId.value) &&
            (isPlaceholder(slot) || database.matchAssignmentQueries.hasSlot(matchId.value, slot.value).executeAsOne() > 0)

    private fun assignmentIds(id: MatchId): Map<PlayerId, PlayerId> {
        if (!replicas.readable(id.value)) return emptyMap()
        return database.matchAssignmentQueries.identitiesForMatch(id.value).executeAsList().associate { row ->
            val slot = PlayerId(row.slot_id)
            val idValue = row.player_id ?: row.player_json?.let { DataJson.decodeFromString(Player.serializer(), it).id.value }
            slot to (idValue?.let(::PlayerId) ?: if (Placeholders.isPlaceholder(slot)) slot else Placeholders.id(slot.value))
        }
    }

    /** Führt [block] in einer Transaktion aus (alles oder nichts). */
    fun <T> transaction(block: () -> T): T = database.transactionWithResult { block() }

    fun afterCommit(block: () -> Unit) = database.transaction { afterCommit(block) }

    /**
     * Löscht ein Match vollständig (Kopf + Events) und hinterlässt eine dauerhafte Löschmarkierung, damit es durch
     * keinen Abgleich wieder auftaucht – „Wischen → löschen“ in der Historie bzw. empfangene Löschung.
     */
    fun deleteMatch(
        id: MatchId,
        deletedAtMs: Long = 0,
    ) {
        database.transaction {
            matches.markDeleted(id, deletedAtMs)
            events.deleteMatch(id.value)
            matches.delete(id)
            database.matchAssignmentQueries.clearMatch(id.value)
        }
    }

    /** F20: nur die durch wear.guest.* belegten Slots mit unveränderten DE/EN-Standardnamen migrieren. */
    fun migrateWatchPlaceholders() {
        val names =
            mapOf(
                "PARTNER" to setOf("Partner"),
                "OPPONENT_1" to setOf("Gegner", "Opponent", "Gegner 1", "Opponent 1"),
                "OPPONENT_2" to setOf("Gegner 2", "Opponent 2"),
            )
        transaction {
            names.forEach { (slot, defaults) ->
                val key = "wear.guest.$slot"
                val id = settings.get(key)?.let(::PlayerId) ?: return@forEach
                val player = players.get(id)
                if (player == null || player.displayName in defaults || player.kind == PlayerKind.PLACEHOLDER) {
                    players.markPlaceholder(id, player?.createdAtMs ?: 0)
                    rebuildAssignmentsForSlot(id)
                }
                settings.remove(key)
            }
        }
    }

    fun isPlaceholder(id: PlayerId): Boolean = Placeholders.isPlaceholder(id) || players.get(id)?.kind == PlayerKind.PLACEHOLDER

    // --- Identität des Besitzers über eigene Geräte (W1-I) -----------------------------------------------

    /**
     * Andere Spieler-IDs desselben Besitzers, z. B. der Uhr-eigene Self-Player vor der Übernahme der Handy-Identität.
     * Matches mit diesen IDs zeigen den Besitzer („Du“), keinen Gast.
     */
    fun selfAliases(): Set<PlayerId> =
        if (accountIdentities.managed) {
            accountIdentities.aliases()
        } else {
            settings
                .get(SELF_ALIASES_KEY)
                ?.split(',')
                ?.filter { it.isNotBlank() }
                ?.map { PlayerId(it) }
                ?.toSet()
                .orEmpty()
        }

    /** Merkt [ids] als Aliase des Besitzers (der aktuelle Self wird ausgelassen). `true`, wenn sich etwas geändert hat. */
    fun addSelfAliases(ids: Collection<PlayerId>): Boolean {
        if (accountIdentities.managed) return false
        val self = players.self()?.id
        val current = selfAliases()
        val next = current + ids.filter { it != self && it.value.isNotBlank() && ',' !in it.value }
        if (next == current) return false
        settings.put(SELF_ALIASES_KEY, next.joinToString(",") { it.value })
        identityListeners.toList().forEach { it() }
        return true
    }

    /** [id] ist der Besitzer selbst oder einer seiner Aliase. */
    fun isSelf(id: PlayerId): Boolean = id == players.self()?.id || id in selfAliases()

    /**
     * Übernimmt [owner] (Self-Player des Handys) als eigenen Spieler; ein bisheriger, anderer Self wird Alias.
     * `true`, wenn sich die Identität geändert hat.
     */
    fun adoptSelf(
        owner: Player,
        nowMs: Long,
    ): Boolean =
        transaction {
            val previous = players.adoptSelf(owner, nowMs) ?: return@transaction false
            addSelfAliases(listOf(previous.id))
            true
        }

    companion object {
        /** Settings-Schlüssel der Self-Aliase (kommagetrennte Spieler-IDs). */
        const val SELF_ALIASES_KEY: String = "players.selfAliases"

        /** Schema 9: kontogebundener Zugriff, atomare Redaktionen und dauerhafte Eventbelege. */
        const val SCHEMA_VERSION: Long = 9
    }
}
