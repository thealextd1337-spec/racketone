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
import eu.advantly.cloud.sync.PulledMatch
import eu.advantly.cloud.sync.SharedMatchesPort
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
    /** Eigener Alias und Benutzername fürs Profil; lädt zusammen mit [friendCode]. */
    val me: FoundProfile? = null,
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
    /** Geteilte Matches von Freunden (P2-E), neueste zuerst; nur was der Server als `feed` freigibt. */
    val feed: List<FeedMatch> = emptyList(),
    /** `share_matches_default`; `null` = noch nicht geladen. */
    val shareDefault: Boolean? = null,
    /** Fehler nur beim Laden/Speichern von [shareDefault]; fremde Fehler des Freunde-Tabs stehen in [error]. */
    val shareDefaultError: String? = null,
    /** Eigene Sicht auf das gerade geöffnete Match ([FriendsViewModel.openMatch]). */
    val match: MatchSharing? = null,
    /** Zuschauer des Matches aus [FriendsViewModel.openVisitors]; `null` ohne Zuschauer-Port oder ohne offenes Match. */
    val visitors: MatchVisitors? = null,
    /** Matches, denen ich zuschaue (neueste zuerst); `null` = noch nicht geladen. */
    val watching: List<WatchedMatch>? = null,
    /** Fehler beim Laden/Beenden von [watching]. */
    val watchingError: String? = null,
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
    /** Geteilte Matches (P2-E); ohne Port bleiben Feed, Teilen und „War ich nicht“ aus. */
    private val matches: SharedMatchesPort? = null,
    private val local: SharedMatchesLocal? = null,
    /** Zuschauer (F16/F25); ohne Warteschlange bleiben Zuschauer-Abschnitte und „Ich schaue zu“ aus. */
    private val visitorQueue: VisitorQueue? = null,
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

    /** Geöffnetes Match (nur die ID, keine Kontodaten); bestätigte Freigaben und verlassene Matches bis zum Pull. */
    private var openedMatch: String? = null
    private val shareOverlay = mutableMapOf<String, Boolean>()
    private val goneMatches = mutableSetOf<String>()

    /** Match der Zuschauer-Verwaltung; Zähler verwirft „Ich schaue zu“-Antworten, die vor einem Beenden begannen. */
    private var visitorMatch: String? = null
    private var watchingRevision = 0L

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

    /**
     * Auch „Erneut versuchen“ nach einem gescheiterten Laden des Codes; lädt Alias und Benutzername mit. Scheitert nur das
     * Profil, steht der Code trotzdem da und der nächste Aufruf (z. B. Öffnen des Freunde-Tabs) versucht das Profil erneut.
     */
    fun loadCode() {
        if (mutable.value.friendCode != null && mutable.value.me != null) return
        run { session ->
            val code = mutable.value.friendCode ?: port.friendCode()
            val me =
                try {
                    port.ownProfile()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            current(session)
            show { copy(friendCode = code, me = me ?: this.me) }
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

    fun showBlocked() = run(block = ::loadBlocked)

    /** Match-Detail geöffnet ([matchId]) bzw. geschlossen (`null`): eigene Freigabe aus dem Pull-Bestand lesen. */
    fun openMatch(matchId: String?) {
        openedMatch = matchId
        reloadLocal()
    }

    /** „Mit Freunden teilen“ für das geöffnete Match; nur mit eigenem verknüpftem Platz in der Cloud. */
    fun setShared(share: Boolean) =
        matchAction({ it.shared != null }, { port, profile, id -> port.setMyParticipation(profile, id, shareWithFriends = share) }) {
            shareOverlay[it] = share
        }

    /**
     * „Aus meinem Verlauf ausblenden“: nur meine Sicht, die anderen Teilnehmer behalten das Match. Beendet zugleich das
     * Teilen – sonst sähen Freunde es weiter im Feed, ohne dass ich die Freigabe noch abschalten könnte.
     */
    fun hideMatch() =
        matchAction({ it.canHide }, { port, profile, id ->
            port.setMyParticipation(profile, id, shareWithFriends = false, hidden = true)
        }) { goneMatches += it }

    /** „War ich nicht“: löst die Verknüpfung durch einen Freund und sperrt sie für dieses Match. */
    fun rejectParticipation() =
        matchAction({ it.canReject }, { port, profile, id -> port.rejectMatchParticipation(profile, id) }) {
            goneMatches +=
                it
        }

    fun loadShareDefault() {
        val port = matches ?: return
        run(shareDefault = true) { session ->
            val share = port.shareMatchesDefault(session.profileId)
            current(session)
            show { copy(shareDefault = share) }
        }
    }

    /** Standard für eigene neue Matches; gilt beim Hochladen eines Matches, bestehende bleiben unverändert. */
    fun setShareDefault(share: Boolean) {
        val port = matches ?: return
        run(shareDefault = true) { session ->
            port.setShareMatchesDefault(session.profileId, share)
            current(session)
            show { copy(shareDefault = share) }
        }
    }

    fun dismissError() {
        mutable.value = mutable.value.copy(error = null, shareDefaultError = null)
    }

    /** Zuschauer-Verwaltung für [matchId] öffnen (lädt die Liste) bzw. schließen (`null`). */
    fun openVisitors(matchId: String?) {
        visitorMatch = matchId
        show { copy(visitors = null) }
        loadVisitors()
    }

    /**
     * Aktive Zuschauer vom Server; ein noch nicht hochgeladenes Match zeigt nur vorgemerkte. Kennt das Match keine
     * Zuschauer mehr, endet auch der Live-Upload während des Spiels.
     */
    fun loadVisitors() {
        val queue = visitorQueue ?: return
        val id = visitorMatch ?: return
        val profile = readyProfile() ?: return
        show { copy(visitors = localVisitors(profile, id, visitors.confirmed(id)).copy(loading = true)) }
        quiet(
            { error -> show { copy(visitors = visitors?.takeIf { it.matchId == id }?.copy(loading = false, error = error)) } },
        ) { session ->
            val onServer = queue.isOnServer(session.profileId, id)
            val confirmed = if (onServer) queue.port.matchVisitors(session.profileId, id) else emptyList()
            current(session)
            if (visitorMatch != id) return@quiet
            if (onServer) queue.setWatched(session.profileId, id, confirmed.isNotEmpty())
            val entries = confirmed.map { VisitorEntry(it.profileId, it.alias, it.canRevoke) }
            show { copy(visitors = localVisitors(session.profileId, id, entries)) }
        }
    }

    /**
     * Freunde als Zuschauer des offenen Matches benennen. Liegt das Match schon auf dem Server, sofort (Fehler sichtbar);
     * sonst vormerken – sie gehen nach dem Upload hinaus.
     */
    fun addVisitors(profileIds: Set<String>) {
        val queue = visitorQueue ?: return
        val id = visitorMatch ?: return
        val profile = readyProfile() ?: return
        if (profileIds.isEmpty()) return
        dismissVisitorError()
        if (!queue.isOnServer(profile, id)) {
            queue.enqueue(profile, id, profileIds)
            show { copy(visitors = localVisitors(profile, id, visitors.confirmed(id))) }
            return
        }
        quiet({ error -> show { copy(visitors = visitors?.takeIf { it.matchId == id }?.copy(error = error)) } }) { session ->
            try {
                profileIds.forEach { queue.port.addVisitor(session.profileId, id, it) }
                current(session)
                queue.setWatched(session.profileId, id, true)
            } finally {
                if (visitorMatch == id && started(session)) loadVisitors()
            }
        }
    }

    /** Zuschauer entfernen: vorgemerkt nur lokal, sonst Widerruf am Server (Benennender oder Ersteller). */
    fun removeVisitor(profileId: String) {
        val queue = visitorQueue ?: return
        val id = visitorMatch ?: return
        val profile = readyProfile() ?: return
        val entry =
            mutable.value.visitors
                ?.takeIf { it.matchId == id }
                ?.visitors
                ?.firstOrNull { it.profileId == profileId } ?: return
        if (entry.pending) {
            queue.forget(profile, id, profileId)
            show { copy(visitors = localVisitors(profile, id, visitors.confirmed(id))) }
            return
        }
        if (!entry.canRevoke) return
        dismissVisitorError()
        quiet({ error -> show { copy(visitors = visitors?.takeIf { it.matchId == id }?.copy(error = error)) } }) { session ->
            queue.port.revokeVisitor(session.profileId, id, profileId)
            current(session)
            if (visitorMatch == id) loadVisitors()
        }
    }

    /** Im Assistenten gewählte Zuschauer eines gerade gestarteten Matches vormerken (Upload folgt). */
    fun queueVisitors(
        matchId: String,
        profileIds: Set<String>,
    ) {
        val profile = readyProfile() ?: return
        visitorQueue?.enqueue(profile, matchId, profileIds)
    }

    /** „Ich schaue zu“ laden; die UI ruft das wiederholt, solange der Abschnitt sichtbar ist (Realtime ist aus). */
    fun loadWatching() {
        val queue = visitorQueue ?: return
        val revision = watchingRevision
        quiet({ error -> show { copy(watchingError = error) } }) { session ->
            val views = queue.port.myVisitorMatches(session.profileId)
            current(session)
            if (revision != watchingRevision) return@quiet
            show { copy(watching = views.map { it.toWatched() }, watchingError = null) }
        }
    }

    /** Nicht mehr zuschauen (Widerruf durch den Visitor selbst). */
    fun stopWatching(matchId: String) {
        val queue = visitorQueue ?: return
        quiet({ error -> show { copy(watchingError = error) } }) { session ->
            queue.port.revokeVisitor(session.profileId, matchId, session.profileId)
            current(session)
            watchingRevision++
            show { copy(watching = watching?.filter { it.match.id != matchId }) }
        }
    }

    fun dismissVisitorError() {
        show { copy(visitors = visitors?.copy(error = null), watchingError = null) }
    }

    private fun readyProfile(): String? = shown?.profileId?.takeIf { mutable.value.access == FriendsAccess.READY }

    /** Sitzung von [session] wird noch angezeigt (kein Kontowechsel seit dem Start). */
    private fun started(session: CloudSession): Boolean = shown?.profileId == session.profileId && shown?.generation == session.generation

    private fun MatchVisitors?.confirmed(matchId: String): List<VisitorEntry> =
        this
            ?.takeIf { it.matchId == matchId }
            ?.visitors
            .orEmpty()
            .filter { !it.pending }

    /** Bestätigte [confirmed] plus vorgemerkte Zuschauer; Mitspieler aus dem Pull-Bestand, soweit bekannt. */
    private fun localVisitors(
        profileId: String,
        matchId: String,
        confirmed: List<VisitorEntry>,
    ): MatchVisitors {
        val known = confirmed.mapTo(mutableSetOf()) { it.profileId }
        val aliases = mutable.value.friends.associate { it.profileId to it.alias }
        val pending =
            visitorQueue
                ?.pending(profileId, matchId)
                .orEmpty()
                .filter { it !in known }
                .map { VisitorEntry(it, aliases[it], canRevoke = true, pending = true) }
        val players =
            local
                ?.match(profileId, matchId)
                ?.participants
                ?.mapNotNullTo(mutableSetOf()) { it.profileId }
                .orEmpty()
        // Ein Fehler bleibt bis zum Schließen bzw. zur nächsten Aktion stehen, auch über das Neuladen hinweg.
        val error =
            mutable.value.visitors
                ?.takeIf { it.matchId == matchId }
                ?.error
        return MatchVisitors(matchId, confirmed + pending, players, error = error)
    }

    /**
     * Wie [run], aber ohne Belegt-Zustand und ohne den Fehler des Freunde-Tabs: Zuschauer-Laden läuft wiederholt im
     * Hintergrund und darf weder andere Aktionen sperren noch deren Fehler überschreiben. Fehler gehen an [onError].
     */
    private fun quiet(
        onError: (String) -> Unit,
        block: suspend (CloudSession) -> Unit,
    ) {
        val session = shown ?: return
        if (mutable.value.access != FriendsAccess.READY) return
        val started = epoch
        scope.launch {
            try {
                // Vor dem RPC: Das SDK kann schon ein anderes Konto tragen, während [shown] noch nachhinkt.
                auth.requireAccountSession(session)
                block(session)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Stale) {
                // Antwort für ein früheres Konto: nichts anzeigen.
            } catch (e: Exception) {
                if (started == epoch) {
                    val reason = (e as? CloudException)?.reason ?: "connection_failed"
                    onError(reason)
                    if (reason == "device_not_authorized") pull?.pullNow()
                }
            }
        }
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
            shareOverlay.clear()
            goneMatches.clear()
            pullAgain = false
            watchingRevision++
            mutable.value = FriendsState(access = access)
        } else {
            mutable.value = mutable.value.copy(access = access)
        }
        shown = session
        reloadLocal()
        // Neues Konto mit offener Zuschauer-Verwaltung: dessen Liste laden.
        if (!same && visitorMatch != null) loadVisitors()
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
        if (state.metadataComplete) {
            overlay.clear()
            shareOverlay.clear()
            goneMatches.clear()
        }
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
        val profile = shown?.profileId?.takeIf { mutable.value.access == FriendsAccess.READY }
        mutable.value =
            mutable.value.copy(
                friends = of { it.status == "accepted" },
                incoming = of { it.status == "pending" && !it.outgoing },
                outgoing = of { it.status == "pending" && it.outgoing },
                feed = profile?.let { local?.feed(it)?.map(PulledMatch::toFeed) }.orEmpty(),
                match = profile?.let { sharing(it) },
            )
    }

    private fun sharing(profileId: String): MatchSharing? {
        val id = openedMatch ?: return null
        val source = local ?: return null
        if (id in goneMatches) return MatchSharing(id, gone = true)
        val mine = source.match(profileId, id)?.participants?.firstOrNull { it.profileId == profileId }
        return MatchSharing(
            id,
            shared = mine?.let { shareOverlay[id] ?: (it.shareWithFriends == true) },
            canReject = mine?.linkStatus == "linked",
            canHide = mine?.linkStatus in setOf("linked", "assigned", "confirmed"),
            linkNotes = source.linkNotes(profileId, id),
        )
    }

    /** Erst nach [current] die Überlagerung setzen: eine verspätete Antwort darf kein anderes Konto verändern. */
    private fun matchAction(
        allowed: (MatchSharing) -> Boolean,
        call: suspend (SharedMatchesPort, String, String) -> Unit,
        confirmed: (String) -> Unit,
    ) {
        val port = matches ?: return
        val id =
            mutable.value.match
                ?.takeIf(allowed)
                ?.matchId ?: return
        run { session ->
            call(port, session.profileId, id)
            current(session)
            confirmed(id)
            afterChange()
        }
    }

    private fun changed(
        profileId: String,
        value: PulledFriendship?,
    ) {
        overlay[profileId] = value
        afterChange()
    }

    /** Bestätigte Änderung anzeigen und einen Pull anstoßen, der sie ablöst (ein laufender begann davor). */
    private fun afterChange() {
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

    /** [shareDefault]: Fehler gehört zum Standardwert-Schalter, nicht zum Freunde-Tab. */
    private fun run(
        shareDefault: Boolean = false,
        block: suspend (CloudSession) -> Unit,
    ) {
        val session = shown ?: return
        if (mutable.value.busy || mutable.value.access != FriendsAccess.READY) return
        val started = epoch
        mutable.value = mutable.value.copy(busy = true, error = null, shareDefaultError = null)
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
                        mutable.value =
                            if (shareDefault) mutable.value.copy(shareDefaultError = reason) else mutable.value.copy(error = reason)
                        // Fehlende Bindung holt der Pull-Host nach; danach lädt [onPull] den Code erneut.
                        if (reason == "device_not_authorized") pull?.pullNow()
                    }
                } finally {
                    if (started == epoch) mutable.value = mutable.value.copy(busy = false)
                }
            }
    }
}
