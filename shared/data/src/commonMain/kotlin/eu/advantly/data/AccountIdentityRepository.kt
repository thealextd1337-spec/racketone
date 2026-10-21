package eu.advantly.data

import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId
import eu.advantly.data.db.AdvantlyDatabase

/** Eigene Identitäten und direkte Herkunft getrennt von beliebigen empfangenen Spielern/Events. */
class AccountIdentityRepository internal constructor(
    private val database: AdvantlyDatabase,
    private val data: LocalData,
) {
    private val queries = database.localIdentityQueries
    var managed: Boolean = data.settings.get("identity.scopedActivated") == "true"
        private set
    val scope: String? get() = data.settings.get("identity.scope")
    val profile: String? get() = data.settings.get("identity.profile")

    fun initialize(
        deviceId: String,
        selfId: String,
        kind: String,
    ) {
        if (scope != null) return
        data.transaction {
            val legacy = "legacy:$deviceId"
            queries.putIdentity(legacy, deviceId, selfId)
            recordSource(deviceId, selfId, kind, legacy, local = true)
            data.settings.put("identity.scope", legacy)
        }
    }

    fun recordSource(
        deviceId: String,
        selfId: String,
        kind: String,
        scope: String,
        local: Boolean,
        eventCount: Long = 0,
    ) {
        require(
            deviceId.isNotBlank() && deviceId.length <= 128 && selfId.isNotBlank() && selfId.length <= 128 &&
                scope.isNotBlank() && scope.length <= 128 && eventCount >= 0 && kind in setOf("PHONE", "WATCH"),
        )
        val previous = queries.source(deviceId).executeAsOneOrNull()
        check(
            previous == null ||
                (
                    previous.self_id == selfId && previous.scope_id == scope && previous.kind == kind &&
                        previous.local == if (local) 1L else 0L
                ),
        )
        database.transaction {
            queries.putSource(deviceId, selfId, kind, scope, if (local) 1L else 0L)
            queries.reportSourceCount(eventCount, deviceId)
            data.cloud.resolvePlayerMetadataSources(selfId)
            // Auch die neue Self-Aliasquelle kann Matches einer anderen eigenen Genesisquelle belegen.
            database.cloudUploadQueries.deviceOwner(deviceId).executeAsOneOrNull()?.let {
                data.cloud.resolveOwnedMatchMetadataSources(it)
            }
        }
    }

    fun localSources(): List<LocalSourceIdentity> =
        queries.localSources().executeAsList().map {
            LocalSourceIdentity(it.device_id, it.self_id, it.scope_id, it.kind, it.stored_event_count)
        }

    fun sourceScope(deviceId: String): String? = queries.source(deviceId).executeAsOneOrNull()?.scope_id

    /** Nur belegte Herkunft mit unveränderlichem Eigentümer; weder Kontobereich noch Events beweisen Eigentum. */
    fun ownedSources(expectedProfile: String): List<OwnedSourceIdentity> {
        require(expectedProfile.isNotBlank())
        return queries.ownedSourceCatalog(expectedProfile).executeAsList().map { OwnedSourceIdentity(it.device_id, it.kind) }
    }

    /** Generierung außen; Transaktion verändert weder historische Spieler-IDs noch Event-Payloads. */
    fun select(
        scope: String,
        profileId: String?,
        nowMs: Long,
        kind: String,
        newId: () -> String,
    ): String =
        database.transactionWithResult {
            if (this@AccountIdentityRepository.scope != scope || profile != profileId) data.replicas.selectionChanged()
            val identity =
                queries.identityForScope(scope).executeAsOneOrNull() ?: run {
                    queries.putIdentity(scope, newId(), newId())
                    queries.identityForScope(scope).executeAsOne()
                }
            recordSource(identity.device_id, identity.self_id, kind, scope, local = true)
            data.players.adoptSelf(
                Player(
                    PlayerId(identity.self_id),
                    data.players
                        .get(PlayerId(identity.self_id))
                        ?.displayName
                        .orEmpty(),
                ),
                nowMs,
            )
            data.settings.put("identity.scope", scope)
            data.settings.put("identity.scopedActivated", "true")
            afterCommit { managed = true }
            if (profileId == null) data.settings.remove("identity.profile") else data.settings.put("identity.profile", profileId)
            data.settings.put("session.deviceId", identity.device_id)
            identity.device_id
        }

    fun accountScope(
        profileId: String,
        newId: () -> String,
    ): String = data.settings.getOrPut("identity.account.$profileId", newId)

    fun aliases(): Set<PlayerId> =
        if (profile != null) {
            queries
                .aliasesForProfile(profile!!)
                .executeAsList()
                .map(::PlayerId)
                .toSet()
        } else {
            currentSources()
                .mapNotNull {
                    queries
                        .source(it)
                        .executeAsOneOrNull()
                        ?.self_id
                        ?.let(::PlayerId)
                }.toSet()
        }

    fun currentSources(): Set<String> =
        (
            scope?.let { queries.sourcesForScope(it).executeAsList().map { source -> source.device_id } }.orEmpty() +
                profile?.let { queries.ownedSource(it).executeAsList() }.orEmpty() +
                scope?.let { queries.acceptedSources(it).executeAsList() }.orEmpty()
        ).toSet()

    fun acceptOwnSources(
        scope: String,
        deviceIds: Set<String>,
    ) = data.transaction {
        for (deviceId in deviceIds) {
            if (queries.source(deviceId).executeAsOneOrNull() == null) continue
            val previous = queries.acceptedScopeForSource(deviceId).executeAsOneOrNull()
            check(previous == null || previous == scope)
            queries.acceptSourceScope(deviceId, scope)
        }
    }

    fun rememberMatchSources(
        matchId: String,
        deviceIds: Set<String>,
    ) = data.transaction {
        deviceIds.forEach { queries.rememberMatchSource(matchId, it) }
    }

    fun matchSources(matchId: String): Set<String> = queries.matchSources(matchId).executeAsList().toSet()

    fun scopeHasOwnedSource(scope: String): Boolean = queries.ownedSourcesInScope(scope).executeAsOne() > 0

    fun adoptionSources(): List<AdoptionSource> =
        queries.unboundSources().executeAsList().map {
            AdoptionSource(it.device_id, it.kind, it.event_count)
        }

    fun bindCurrent(session: CloudUploadSession) {
        check(profile == session.profileId)
        val source = queries.identityForScope(checkNotNull(scope)).executeAsOne()
        data.cloudUploads.bindOwnedDevices(session, setOf(source.device_id))
    }

    /** Auswahl neu prüfen: Herkunft und unveränderliche Eigentümer gemeinsam atomar übernehmen. */
    fun adopt(
        session: CloudUploadSession,
        deviceIds: Set<String>,
    ) = data.transaction {
        check(profile == session.profileId)
        val eligible = adoptionSources().map { it.deviceId }.toSet()
        check(deviceIds.all { it in eligible }) { "Quelle nicht zur Übernahme verfügbar" }
        data.cloudUploads.bindOwnedDevices(session, deviceIds)
    }
}

data class LocalSourceIdentity(
    val deviceId: String,
    val selfId: String,
    val scope: String,
    val kind: String,
    val eventCount: Long,
)

data class OwnedSourceIdentity(
    val deviceId: String,
    val kind: String,
)
