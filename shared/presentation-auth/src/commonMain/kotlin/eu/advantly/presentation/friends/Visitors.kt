package eu.advantly.presentation.friends

import eu.advantly.cloud.sync.CloudException
import eu.advantly.cloud.sync.VisitorView
import eu.advantly.cloud.sync.VisitorsPort
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** Persistenter Zuschauer-Stand je Konto (Host: z. B. SharedPreferences); keine Daten anderer Konten. */
interface PendingVisitorStore {
    /** Benennungen, die auf den Upload des Matches warten: Match-ID → Profil-IDs. */
    fun pending(profileId: String): Map<String, Set<String>>

    fun setPending(
        profileId: String,
        pending: Map<String, Set<String>>,
    )

    /** Matches mit mindestens einem bestätigten Visitor: nur sie laden den Live-Stand während des Spiels hoch. */
    fun watched(profileId: String): Set<String>

    fun setWatched(
        profileId: String,
        matchIds: Set<String>,
    )
}

/**
 * Zuschauer (F16/F25) über dem [port]: Ein Match ist erst nach dem Upload seines Kopfes auf dem Server ([onServer]);
 * vorher gewählte Visitor (Assistent) warten im [store] und gehen mit [flush] nach einem Abgleich hinaus. [requestSync]
 * stößt den Abgleich an, der den Kopf hochlädt. Aufrufe auf dem seriellen Host-Kontext.
 */
class VisitorQueue(
    private val store: PendingVisitorStore,
    val port: VisitorsPort,
    private val onServer: (profileId: String, matchId: String) -> Boolean,
    private val requestSync: () -> Unit,
) {
    private val flushing = Mutex()

    fun isOnServer(
        profileId: String,
        matchId: String,
    ): Boolean = onServer(profileId, matchId)

    fun pending(
        profileId: String,
        matchId: String,
    ): Set<String> = store.pending(profileId)[matchId].orEmpty()

    /** Benennungen vormerken und den Abgleich anstoßen; der folgende [flush] sendet sie. */
    fun enqueue(
        profileId: String,
        matchId: String,
        visitorIds: Set<String>,
    ) {
        if (visitorIds.isEmpty()) return
        val pending = store.pending(profileId)
        store.setPending(profileId, pending + (matchId to pending[matchId].orEmpty() + visitorIds))
        requestSync()
    }

    /** Vorgemerkte Benennung zurücknehmen (noch nichts gesendet). */
    fun forget(
        profileId: String,
        matchId: String,
        visitorId: String,
    ) = removePending(profileId, matchId, visitorId)

    fun isWatched(
        profileId: String,
        matchId: String,
    ): Boolean = matchId in store.watched(profileId) || pending(profileId, matchId).isNotEmpty()

    fun setWatched(
        profileId: String,
        matchId: String,
        watched: Boolean,
    ) {
        val current = store.watched(profileId)
        if ((matchId in current) != watched) store.setWatched(profileId, if (watched) current + matchId else current - matchId)
    }

    /**
     * Vorgemerkte Benennungen hochgeladener Matches senden. Endgültige Ablehnungen (kein Freund mehr, schon Mitspieler,
     * Match nicht erlaubt) verwerfen den Eintrag still; Netz-, Bindungs- und Limitfehler bleiben für den nächsten Lauf.
     * `true`: mindestens ein Visitor wurde bestätigt.
     */
    suspend fun flush(profileId: String): Boolean =
        flushing.withLock {
            var added = false
            for ((matchId, visitors) in store.pending(profileId)) {
                if (!onServer(profileId, matchId)) continue
                for (visitorId in visitors) {
                    try {
                        port.addVisitor(profileId, matchId, visitorId)
                        setWatched(profileId, matchId, true)
                        added = true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: CloudException) {
                        if (!isFinal(e, matchId, visitorId)) return@withLock added
                    }
                    permissionFailures.remove(matchId to visitorId)
                    removePending(profileId, matchId, visitorId)
                }
            }
            added
        }

    private fun removePending(
        profileId: String,
        matchId: String,
        visitorId: String,
    ) {
        val pending = store.pending(profileId)
        val rest = pending[matchId].orEmpty() - visitorId
        store.setPending(profileId, if (rest.isEmpty()) pending - matchId else pending + (matchId to rest))
    }

    private val permissionFailures = mutableMapOf<Pair<String, String>, Int>()

    /**
     * `forbidden` kommt als abgewiesene Berechtigung ohne eigenen Grund (CloudHttp kennt ihn nicht) – genau wie ein
     * abgelaufenes Token (401). Deshalb erst nach [PERMISSION_ATTEMPTS] Versuchen verwerfen, nicht beim ersten.
     */
    private fun isFinal(
        e: CloudException,
        matchId: String,
        visitorId: String,
    ): Boolean {
        if (e.reason in FINAL_REASONS) return true
        if (e.reason != "cloud_connection_failed" || !e.permission) return false
        val failures = (permissionFailures[matchId to visitorId] ?: 0) + 1
        permissionFailures[matchId to visitorId] = failures
        return failures >= PERMISSION_ATTEMPTS
    }

    companion object {
        val FINAL_REASONS = setOf("visitor_must_be_friend", "already_member", "visitor_limit_reached")
        const val PERMISSION_ATTEMPTS = 3
    }
}

/**
 * Live-Stand für Visitor während des Spiels: Nach einer Zähländerung eines Matches mit Visitor höchstens alle
 * [intervalMs] einen Abgleich ([syncNow]), damit Zuschauer nicht erst am Matchende etwas sehen. Ohne Visitor nichts.
 */
class LiveVisitorUpload(
    private val queue: VisitorQueue,
    private val nowMs: () -> Long,
    /** Führt den Block nach der Wartezeit aus (Host: Coroutine mit `delay`). */
    private val schedule: (delayMs: Long, run: () -> Unit) -> Unit,
    private val intervalMs: Long = INTERVAL_MS,
    private val syncNow: () -> Unit,
) {
    private val counts = mutableMapOf<String, Int>()
    private var lastSyncMs: Long? = null
    private var armed = false

    /** Stand eines laufenden Matches; nur eine geänderte [eventCount] zählt als Zähländerung. */
    fun onScore(
        profileId: String?,
        matchId: String,
        eventCount: Int,
    ) {
        val previous = counts.put(matchId, eventCount)
        if (profileId == null || previous == null || previous == eventCount) return
        if (armed || !queue.isWatched(profileId, matchId)) return
        armed = true
        val wait = lastSyncMs?.let { it + intervalMs - nowMs() }?.coerceAtLeast(0) ?: 0
        schedule(wait) {
            armed = false
            lastSyncMs = nowMs()
            syncNow()
        }
    }

    companion object {
        const val INTERVAL_MS = 30_000L
    }
}

/** Zuschauer-Eintrag eines Matches; [pending]: wartet noch auf den Upload des Matches. */
data class VisitorEntry(
    val profileId: String,
    val alias: String?,
    val canRevoke: Boolean,
    val pending: Boolean = false,
)

/** Zuschauer des geöffneten Matches ([FriendsViewModel.openVisitors]). */
data class MatchVisitors(
    val matchId: String,
    val visitors: List<VisitorEntry> = emptyList(),
    /** Profil-IDs der verknüpften Mitspieler (nicht als Zuschauer wählbar), soweit lokal bekannt. */
    val players: Set<String> = emptySet(),
    val loading: Boolean = false,
    val error: String? = null,
)

/** Stand eines zugeschauten Matches (bereinigt, ohne Telemetrie); [games]/[points] nur während des Spiels. */
data class WatchedMatch(
    val match: FeedMatch,
    val games: Pair<Int, Int>? = null,
    val points: Pair<String, String>? = null,
    val tiebreak: Boolean = false,
)

internal fun VisitorView.toWatched(): WatchedMatch {
    fun team(name: String) = teams.filter { it.team == name }.sortedBy { it.position }.map { it.name }
    val finished = status == "finished"
    val source = if (finished) result ?: live else live ?: result

    fun pair(key: String): Pair<JsonPrimitive, JsonPrimitive>? =
        (source?.get(key) as? JsonArray)?.takeIf { it.size == 2 }?.let {
            (it[0] as? JsonPrimitive ?: return null) to
                (it[1] as? JsonPrimitive ?: return null)
        }
    val sets =
        (source?.get("sets") as? JsonArray)
            ?.mapNotNull { set ->
                (set as? JsonArray)?.takeIf { it.size == 2 }?.let {
                    val a = (it[0] as? JsonPrimitive)?.intOrNull
                    val b = (it[1] as? JsonPrimitive)?.intOrNull
                    if (a != null && b != null) a to b else null
                }
            }.orEmpty()
    val running = !finished && source === live
    return WatchedMatch(
        match =
            FeedMatch(
                id = matchId,
                sport = Sport.entries.firstOrNull { it.name.lowercase() == sport },
                doubles = discipline == "doubles",
                status = status,
                winner = Side.entries.firstOrNull { it.name == winner },
                resultReason = resultReason,
                startedAt = startedAt,
                endedAt = endedAt,
                teamA = team("A"),
                teamB = team("B"),
                sets = sets,
                corrected = (result?.get("corrected") as? JsonPrimitive)?.booleanOrNull == true,
            ),
        games =
            pair("games")?.takeIf { running }?.let { (a, b) ->
                a.intOrNull?.let { x -> b.intOrNull?.let { y -> x to y } }
            },
        points =
            pair(
                "points",
            )?.takeIf { running && it.first.isString && it.second.isString }?.let { it.first.content to it.second.content },
        tiebreak = running && (live?.get("tiebreak") as? JsonPrimitive)?.booleanOrNull == true,
    )
}
