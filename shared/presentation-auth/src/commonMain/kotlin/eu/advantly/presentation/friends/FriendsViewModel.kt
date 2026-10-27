package eu.advantly.presentation.friends

import eu.advantly.cloud.sync.BlockedProfile
import eu.advantly.cloud.sync.CloudAuthPort
import eu.advantly.cloud.sync.CloudException
import eu.advantly.cloud.sync.CloudSession
import eu.advantly.cloud.sync.ConsentStatus
import eu.advantly.cloud.sync.FoundProfile
import eu.advantly.cloud.sync.FriendRequestResult
import eu.advantly.cloud.sync.FriendsPort
import eu.advantly.cloud.sync.PulledFriendship
import eu.advantly.presentation.auth.PhonePullPort
import eu.advantly.presentation.auth.PhonePullState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Warum der Freunde-Tab (noch) nicht nutzbar ist; Offline-Zählen bleibt in jedem Fall unberührt. */
enum class FriendsAccess { SIGNED_OUT, SETUP_REQUIRED, CONSENT_REQUIRED, PAUSED, READY }

data class FriendEntry(
    val profileId: String,
    /** `null`/leer: Profil inaktiv oder redigiert. */
    val alias: String?,
)

data class FriendsState(
    val access: FriendsAccess = FriendsAccess.SIGNED_OUT,
    val friendCode: String? = null,
    val friends: List<FriendEntry> = emptyList(),
    val incoming: List<FriendEntry> = emptyList(),
    val outgoing: List<FriendEntry> = emptyList(),
    /** `null` = noch nicht geladen. */
    val blocked: List<BlockedProfile>? = null,
    val found: FoundProfile? = null,
    val notFound: Boolean = false,
    val requestResult: FriendRequestResult? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

/**
 * Freunde-Tab (P2-D). Listen kommen aus dem lokalen Pull-Bestand ([localFriendships]); nach jeder Änderung startet ein
 * Pull, bis dahin überlagert das bestätigte RPC-Ergebnis die Liste. Jede Antwort gilt nur für das Konto und die
 * Auth-Generation, unter der sie angefordert wurde: Kontowechsel/Logout bricht laufende Arbeit ab und verwirft alles.
 * Alle Aufrufe auf dem seriellen Host-Kontext.
 */
class FriendsViewModel(
    private val auth: CloudAuthPort,
    private val port: FriendsPort,
    private val localFriendships: (profileId: String) -> List<PulledFriendship>,
    private val pull: PhonePullPort? = null,
    parentScope: CoroutineScope,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val mutable = MutableStateFlow(FriendsState())
    val state = mutable.asStateFlow()
    private var shown: CloudSession? = null
    private var epoch = 0L
    private var action: Job? = null

    /** Bestätigte, noch nicht gezogene Änderungen je Gegenüber; `null` = entfernt. */
    private val overlay = mutableMapOf<String, PulledFriendship?>()
    private var pullAgain = false

    private class Stale : Exception()

    init {
        scope.launch { auth.accountSession.collect(::onSession) }
        pull?.let { port -> scope.launch { port.state.collect(::onPull) } }
    }

    fun close() = scope.cancel()

    /** Tab sichtbar: lokale Liste neu lesen, Abgleich anstoßen (neue Anfragen) und den eigenen Code laden. */
    fun open() {
        reloadLocal()
        if (mutable.value.access == FriendsAccess.READY) pull?.pullNow()
        loadCode()
    }

    /** Auch „Erneut versuchen“ nach einem gescheiterten Laden des Codes. */
    fun loadCode() {
        if (mutable.value.friendCode != null) return
        run { session ->
            val code = port.friendCode()
            current(session)
            show { copy(friendCode = code) }
        }
    }

    fun search(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || trimmed.length > 64 || mutable.value.busy) return
        mutable.value = mutable.value.copy(found = null, notFound = false, requestResult = null)
        run { session ->
            val found = port.findProfile(trimmed)
            current(session)
            show { copy(found = found, notFound = found == null) }
        }
    }

    fun clearSearch() {
        mutable.value = mutable.value.copy(found = null, notFound = false, requestResult = null, error = null)
    }

    /** Freundescode, wenn der Treffer nicht über den Benutzernamen kam (Server unterscheidet nur zur Statistik). */
    fun sendRequest(query: String) {
        val target = mutable.value.found ?: return
        val viaCode = !target.username.equals(query.trim(), ignoreCase = true)
        run { session ->
            val result = port.sendFriendRequest(target.id, viaCode)
            current(session)
            val status = if (result == FriendRequestResult.REQUESTED) "pending" else "accepted"
            changed(target.id, PulledFriendship(target.id, target.alias, status, outgoing = true, serverVersion = 0))
            show { copy(found = null, requestResult = result) }
        }
    }

    fun respond(
        requesterId: String,
        accept: Boolean,
    ) = run { session ->
        port.respondFriendRequest(requesterId, accept)
        current(session)
        val old = entry(requesterId)
        changed(requesterId, if (accept) old?.copy(status = "accepted") else null)
    }

    /** Entfreunden bzw. eigene offene Anfrage zurückziehen (Rückfrage liegt in der UI). */
    fun remove(profileId: String) =
        run { session ->
            port.removeFriend(profileId)
            current(session)
            changed(profileId, null)
        }

    fun block(profileId: String) =
        run { session ->
            port.blockUser(profileId)
            current(session)
            changed(profileId, null)
            if (mutable.value.blocked != null) loadBlocked(session)
        }

    fun unblock(profileId: String) =
        run { session ->
            port.unblockUser(profileId)
            current(session)
            loadBlocked(session)
        }

    fun showBlocked() = run(::loadBlocked)

    fun dismissError() {
        mutable.value = mutable.value.copy(error = null)
    }

    private suspend fun loadBlocked(session: CloudSession) {
        val blocked = port.blockedProfiles()
        current(session)
        show { copy(blocked = blocked) }
    }

    private fun onSession(session: CloudSession?) {
        val same = shown?.profileId == session?.profileId && shown?.generation == session?.generation
        val access = access(session)
        if (same && access == mutable.value.access) return
        if (!same || access != FriendsAccess.READY) {
            // Neues Konto, neue Sitzung oder Rechteverlust: laufende Arbeit und alle Kontodaten verwerfen.
            check(epoch < Long.MAX_VALUE)
            epoch++
            action?.cancel()
            action = null
            overlay.clear()
            pullAgain = false
            mutable.value = FriendsState(access = access)
        } else {
            mutable.value = mutable.value.copy(access = access)
        }
        shown = session
        reloadLocal()
    }

    private fun onPull(state: PhonePullState) {
        if (state.busy) return
        if (pullAgain) {
            // Der laufende Pull begann vor der Änderung; erst ein neuer Lauf darf die Überlagerung ablösen.
            pullAgain = false
            pull?.pullNow()
            return
        }
        // Vollständige Metadaten enthalten alle Freundschaften: auch bei BLOCKED/FAILED durch andere Rückstände.
        if (state.metadataComplete) overlay.clear()
        reloadLocal()
        // Gerätebindung entsteht im Pull: einen fehlenden (z. B. daran gescheiterten) Code jetzt nachladen.
        if (state.metadataComplete && mutable.value.friendCode == null && action?.isActive != true) {
            if (mutable.value.error == "device_not_authorized") dismissError()
            loadCode()
        }
    }

    private fun access(session: CloudSession?): FriendsAccess =
        when {
            session == null -> FriendsAccess.SIGNED_OUT
            session.deletionRequested || session.suspended -> FriendsAccess.PAUSED
            !session.onboarded -> FriendsAccess.SETUP_REQUIRED
            session.consentStatus != ConsentStatus.CURRENT -> FriendsAccess.CONSENT_REQUIRED
            else -> FriendsAccess.READY
        }

    private fun entry(profileId: String): PulledFriendship? =
        if (profileId in overlay) overlay[profileId] else currentList().firstOrNull { it.profileId == profileId }

    private fun currentList(): List<PulledFriendship> {
        val session = shown ?: return emptyList()
        if (mutable.value.access != FriendsAccess.READY) return emptyList()
        return localFriendships(session.profileId)
    }

    private fun reloadLocal() {
        val merged =
            (currentList().filter { it.profileId !in overlay } + overlay.values.filterNotNull())
                .sortedWith(compareBy({ it.alias.isNullOrBlank() }, { it.alias.orEmpty().lowercase() }, { it.profileId }))

        fun of(predicate: (PulledFriendship) -> Boolean) = merged.filter(predicate).map { FriendEntry(it.profileId, it.alias) }
        mutable.value =
            mutable.value.copy(
                friends = of { it.status == "accepted" },
                incoming = of { it.status == "pending" && !it.outgoing },
                outgoing = of { it.status == "pending" && it.outgoing },
            )
    }

    private fun changed(
        profileId: String,
        value: PulledFriendship?,
    ) {
        overlay[profileId] = value
        reloadLocal()
        val port = pull ?: return
        if (port.state.value.busy) pullAgain = true else port.pullNow()
    }

    /** Wirft [Stale], wenn inzwischen ein anderes Konto/eine andere Sitzung angezeigt wird; die Antwort wird verworfen. */
    private fun current(session: CloudSession) {
        val now = auth.accountSession.value
        val displayed = shown
        if (displayed?.profileId != session.profileId || displayed.generation != session.generation ||
            now?.profileId != session.profileId || now.generation != session.generation
        ) {
            throw Stale()
        }
        auth.requireAccountSession(session)
    }

    private inline fun show(update: FriendsState.() -> FriendsState) {
        mutable.value = mutable.value.update()
    }

    private fun run(block: suspend (CloudSession) -> Unit) {
        val session = shown ?: return
        if (mutable.value.busy || mutable.value.access != FriendsAccess.READY) return
        val started = epoch
        mutable.value = mutable.value.copy(busy = true, error = null)
        action =
            scope.launch {
                try {
                    // Vor dem RPC: Das SDK kann schon ein anderes Konto tragen, während [shown] noch nachhinkt.
                    // Sonst liefe z. B. block_user mit dem Gegenüber von A unter der Sitzung von B.
                    auth.requireAccountSession(session)
                    block(session)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Stale) {
                    // Antwort für ein früheres Konto: nichts anzeigen.
                } catch (e: Exception) {
                    if (started == epoch) {
                        val reason = (e as? CloudException)?.reason ?: "connection_failed"
                        mutable.value = mutable.value.copy(error = reason)
                        // Fehlende Bindung holt der Pull-Host nach; danach lädt [onPull] den Code erneut.
                        if (reason == "device_not_authorized") pull?.pullNow()
                    }
                } finally {
                    if (started == epoch) mutable.value = mutable.value.copy(busy = false)
                }
            }
    }
}
