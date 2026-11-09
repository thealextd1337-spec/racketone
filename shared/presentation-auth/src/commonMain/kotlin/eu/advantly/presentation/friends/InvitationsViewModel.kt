package eu.advantly.presentation.friends

import androidx.lifecycle.SavedStateHandle
import eu.advantly.cloud.sync.CloudAuthPort
import eu.advantly.cloud.sync.CloudException
import eu.advantly.cloud.sync.CloudSession
import eu.advantly.cloud.sync.InviteKind
import eu.advantly.cloud.sync.InvitePreview
import eu.advantly.cloud.sync.InviteStatus
import eu.advantly.cloud.sync.InvitesPort
import eu.advantly.cloud.sync.MAX_INVITE_TTL_MINUTES
import eu.advantly.cloud.sync.OpenInvite
import eu.advantly.presentation.auth.PhonePullPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Ergebnis nach dem Einlösen (Snackbar); [alias] stammt aus der Vorschau. */
data class InviteResult(
    val kind: InviteKind,
    val alias: String?,
    val matchCount: Int = 0,
    val alreadyFriends: Boolean = false,
)

/**
 * Hinweis zum empfangenen Link: ein Status (`invalid`, `expired`, `used`, `revoked`, `own`, `already_claimed`),
 * [InvitationsViewModel.NEEDS_ACCOUNT] oder ein Cloud-Grund (offline, Rate-Limit, Zustimmung, Gerät) – dann wartet der Link.
 */
data class InviteProblem(
    val reason: String,
    val guestName: String? = null,
)

/** Eigener, frisch erzeugter Link (Teilen oder QR); der Token erscheint nicht in [toString]. */
class OwnInvite internal constructor(
    val link: String,
    val expiresAtMillis: Long,
) {
    override fun toString() = "OwnInvite(expiresAtMillis=$expiresAtMillis)"
}

/** Neuer Claim-Link für den Gast [playerId], bereit zum Teilen; der Token erscheint nicht in [toString]. */
class GuestInviteShare internal constructor(
    val playerId: String,
    val link: String,
    val expiresAtMillis: Long,
) {
    override fun toString() = "GuestInviteShare(playerId=$playerId, expiresAtMillis=$expiresAtMillis)"
}

/** Offener Claim-Link eines Gasts (Server-IDs zum Zurückziehen, späteste Ablaufzeit). */
data class GuestInvite(
    val inviteIds: List<String>,
    val expiresAtMillis: Long,
)

data class InvitationsState(
    val access: FriendsAccess = FriendsAccess.SIGNED_OUT,
    /** Ein empfangener Link wartet (der Token selbst bleibt im ViewModel/SavedState). */
    val pending: Boolean = false,
    /** Vorschau mit Status `ok` oder `already_friends`: Dialog offen. */
    val preview: InvitePreview? = null,
    val busy: Boolean = false,
    val result: InviteResult? = null,
    val problem: InviteProblem? = null,
    val qrOpen: Boolean = false,
    val qr: OwnInvite? = null,
    /** Freundes-Link zum Teilen; die UI öffnet das Teilen-Menü und meldet [InvitationsViewModel.shareHandled]. */
    val share: OwnInvite? = null,
    val inviteBusy: Boolean = false,
    /** Fehler beim Erzeugen eines Freundes-Links bzw. QR-Codes. */
    val inviteError: String? = null,
    /** Offene Claim-Links je Gast; `null` = noch nicht geladen. */
    val guestInvites: Map<String, GuestInvite>? = null,
    val guestShare: GuestInviteShare? = null,
    val guestBusy: Boolean = false,
    val guestError: String? = null,
)

/**
 * Einladungen per Link und QR (plan-einladungen §1, §3.4, §5). Der empfangene Token liegt nur hier und im
 * [SavedStateHandle] (überlebt Anmeldung, Onboarding, Zustimmung und Prozessende), nie in Prefs, SQL, Backup oder Logs.
 * Verworfen wird er bei Erfolg, Ablehnen, endgültigem Status, Abmeldung ([signedOut]), Kontowechsel und ohne mögliches
 * Konto ([needsAccount]); offline, Rate-Limit, fehlende Zustimmung und gesperrtes Gerät lassen ihn warten. Antworten
 * gelten nur für Konto und Auth-Generation, unter denen sie angefordert wurden. Alle Aufrufe auf dem seriellen Host-Kontext.
 */
class InvitationsViewModel(
    private val auth: CloudAuthPort,
    private val port: InvitesPort,
    private val savedState: SavedStateHandle,
    parentScope: CoroutineScope,
    /** Wanduhr in Millisekunden (Restzeit des QR, abgelaufene Gast-Links). */
    private val now: () -> Long,
    private val pull: PhonePullPort? = null,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val mutable = MutableStateFlow(InvitationsState(pending = token() != null))
    val state = mutable.asStateFlow()
    private var shown: CloudSession? = null
    private var epoch = 0L

    /** Wechselt mit jedem empfangenen bzw. verworfenen Link; ältere Antworten fallen weg. */
    private var linkRevision = 0L
    private var redeemJob: Job? = null
    private var inviteJob: Job? = null
    private var guestJob: Job? = null
    private var knownFriends: Set<String>? = null

    private class Stale : Exception()

    init {
        scope.launch { auth.accountSession.collect(::onSession) }
    }

    fun close() = scope.cancel()

    /** Link bzw. Token aus App Link oder Feld „Freund hinzufügen“; `false`, wenn es kein Advantly-Link ist. */
    fun receive(input: String): Boolean {
        val token = InviteLinks.parse(input) ?: return false
        nextLink()
        savedState[KEY_TOKEN] = token
        savedState[KEY_OWNER] = shown?.profileId
        show { copy(pending = true, preview = null, busy = false, result = null, problem = null) }
        load()
        return true
    }

    /** Vorschau (erneut) laden, z. B. „Erneut versuchen“ nach offline. */
    fun retry() {
        show { copy(problem = null) }
        load()
    }

    /** „Annehmen“ bzw. „Ja, das bin ich“. */
    fun accept() {
        val token = token() ?: return
        val preview = mutable.value.preview?.takeIf { it.status == InviteStatus.OK } ?: return
        redeem { session, revision ->
            val done =
                try {
                    port.redeemInvite(session.profileId, token)
                } catch (e: CloudException) {
                    val final = REDEEM_FINAL[e.reason] ?: throw e
                    current(session, revision)
                    discard()
                    show { copy(problem = InviteProblem(final, preview.guestName)) }
                    return@redeem
                }
            current(session, revision)
            discard()
            show { copy(result = InviteResult(done.kind, preview.inviterAlias, done.matchCount, done.alreadyFriends)) }
            pull?.pullNow()
        }
    }

    /** „Nicht jetzt“, „Nein“ und „OK“ nach „Ihr seid schon befreundet“: verwerfen, ohne einzulösen. */
    fun decline() = discard()

    fun dismissResult() = show { copy(result = null) }

    fun dismissProblem() = show { copy(problem = null) }

    /** Abmeldung: der Link gehört nicht ins nächste Konto. */
    fun signedOut() {
        discard()
        show { copy(problem = null, result = null) }
    }

    /** Altersgrenze nicht erreicht oder Land gesperrt: es entsteht kein Konto, der Link ist wertlos. */
    fun needsAccount() {
        if (token() == null) return
        discard()
        show { copy(problem = InviteProblem(NEEDS_ACCOUNT)) }
    }

    /** QR-Sheet öffnen: ein Code je Öffnen, danach nur auf Knopfdruck ([newQr]) oder nach einem Einlösen ([onFriends]). */
    fun openQr() {
        show { copy(qrOpen = true, inviteError = null) }
        if (mutable.value.qr == null) newQr()
    }

    fun newQr() = createFriendInvite(qr = true)

    fun closeQr() {
        show { copy(qrOpen = false, qr = null) }
    }

    /** „Link teilen“: Freundes-Link mit 14 Tagen Gültigkeit. */
    fun shareLink() = createFriendInvite(qr = false)

    fun shareHandled() = show { copy(share = null) }

    fun dismissInviteError() = show { copy(inviteError = null) }

    /**
     * Freundesliste nach jedem Abgleich: kommt bei offenem QR ein Freund hinzu, hat jemand den Code eingelöst (einmal
     * nutzbar, E5) – dann gleich einen neuen zeigen. Kein Erneuern im Takt.
     */
    fun onFriends(profileIds: Set<String>) {
        val before = knownFriends
        knownFriends = profileIds
        if (before != null && mutable.value.qrOpen && (profileIds - before).isNotEmpty()) newQr()
    }

    /** Offene Claim-Links für die Gästeliste. */
    fun loadGuestInvites() {
        guestJob =
            work({ it.guestBusy }, { copy(guestBusy = it) }, { copy(guestError = it) }) { session ->
                val open = port.openInvites(session.profileId)
                current(session)
                show { copy(guestInvites = open.claims()) }
            } ?: guestJob
    }

    /** „Einladungslink senden“ (Bestätigung liegt in der UI); der Server zieht ältere Links dieses Gasts zurück. */
    fun sendGuestInvite(playerId: String) {
        if (playerId.isBlank()) return
        guestJob =
            work({ it.guestBusy }, { copy(guestBusy = it) }, { copy(guestError = it) }) { session ->
                val token = port.createInvite(session.profileId, InviteKind.CLAIM_PLAYER, playerId, MAX_INVITE_TTL_MINUTES)
                current(session)
                val expires = now() + MAX_INVITE_TTL_MINUTES * MINUTE
                show { copy(guestShare = GuestInviteShare(playerId, InviteLinks.link(token), expires)) }
                val open = port.openInvites(session.profileId)
                current(session)
                show { copy(guestInvites = open.claims()) }
            } ?: guestJob
    }

    /** „Einladung zurückziehen“; ein inzwischen benutzter oder abgelaufener Link gilt ebenfalls als erledigt. */
    fun revokeGuestInvite(playerId: String) {
        val invite = mutable.value.guestInvites?.get(playerId) ?: return
        guestJob =
            work({ it.guestBusy }, { copy(guestBusy = it) }, { copy(guestError = it) }) { session ->
                invite.inviteIds.forEach { id ->
                    try {
                        port.revokeInvite(session.profileId, id)
                    } catch (e: CloudException) {
                        if (e.reason != "invite_not_found") throw e
                    }
                }
                current(session)
                show { copy(guestInvites = guestInvites?.minus(playerId)) }
            } ?: guestJob
    }

    fun guestShareHandled() = show { copy(guestShare = null) }

    fun dismissGuestError() = show { copy(guestError = null) }

    private fun createFriendInvite(qr: Boolean) {
        val minutes = if (qr) QR_TTL_MINUTES else MAX_INVITE_TTL_MINUTES
        inviteJob =
            work({ it.inviteBusy }, { copy(inviteBusy = it) }, { copy(inviteError = it) }) { session ->
                val token = port.createInvite(session.profileId, InviteKind.FRIEND, null, minutes)
                current(session)
                val invite = OwnInvite(InviteLinks.link(token), now() + minutes * MINUTE)
                show {
                    if (!qr) {
                        copy(share = invite)
                    } else if (qrOpen) {
                        copy(qr = invite)
                    } else {
                        this
                    }
                }
            } ?: inviteJob
    }

    private fun token(): String? = savedState.get<String>(KEY_TOKEN)?.let(InviteLinks::parse)

    private fun load() {
        val token = token() ?: return
        if (mutable.value.preview != null) return
        redeem { session, revision ->
            val preview = port.previewInvite(session.profileId, token)
            current(session, revision)
            if (preview.status == InviteStatus.OK || preview.status == InviteStatus.ALREADY_FRIENDS) {
                show { copy(preview = preview) }
            } else {
                discard()
                show { copy(problem = InviteProblem(preview.status.wire)) }
            }
        }
    }

    private fun nextLink() {
        check(linkRevision < Long.MAX_VALUE)
        linkRevision++
        redeemJob?.cancel()
        redeemJob = null
    }

    private fun discard() {
        nextLink()
        savedState.remove<String>(KEY_TOKEN)
        savedState.remove<String>(KEY_OWNER)
        show { copy(pending = false, preview = null, busy = false) }
    }

    private fun onSession(session: CloudSession?) {
        // Kontowechsel verwirft den Link, auch über ein Prozessende hinweg (Besitzer im SavedState).
        if (session != null && token() != null) {
            val owner = savedState.get<String>(KEY_OWNER)
            if (owner == null) {
                savedState[KEY_OWNER] = session.profileId
            } else if (owner != session.profileId) {
                discard()
            }
        }
        val same = shown?.profileId == session?.profileId && shown?.generation == session?.generation
        val access = friendsAccess(session)
        if (!same || access != FriendsAccess.READY) {
            // Neues Konto, neue Sitzung oder Rechteverlust: laufende Arbeit und alle Kontodaten verwerfen.
            check(epoch < Long.MAX_VALUE)
            epoch++
            nextLink()
            inviteJob?.cancel()
            guestJob?.cancel()
            knownFriends = null
            mutable.value = InvitationsState(access = access, pending = token() != null)
        } else {
            mutable.value = mutable.value.copy(access = access)
        }
        shown = session
        load()
    }

    /** Erst nach [current] den Zustand ändern: eine verspätete Antwort darf kein anderes Konto verändern. */
    private fun current(
        session: CloudSession,
        revision: Long? = null,
    ) {
        val now = auth.accountSession.value
        val displayed = shown
        if (displayed?.profileId != session.profileId || displayed.generation != session.generation ||
            now?.profileId != session.profileId || now.generation != session.generation ||
            (revision != null && revision != linkRevision)
        ) {
            throw Stale()
        }
        auth.requireAccountSession(session)
    }

    /** Vorschau und Einlösen des empfangenen Links; Fehler lassen ihn warten. */
    private fun redeem(block: suspend (CloudSession, Long) -> Unit) {
        val session = shown ?: return
        if (mutable.value.access != FriendsAccess.READY || mutable.value.busy) return
        val started = epoch
        val revision = linkRevision
        show { copy(busy = true, problem = null) }
        redeemJob =
            scope.launch {
                try {
                    auth.requireAccountSession(session)
                    block(session, revision)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Stale) {
                    // Antwort für ein früheres Konto oder einen früheren Link: nichts anzeigen.
                } catch (e: Exception) {
                    if (started == epoch && revision == linkRevision) {
                        val reason = (e as? CloudException)?.reason ?: "connection_failed"
                        show { copy(preview = null, problem = InviteProblem(reason)) }
                        if (reason == "device_not_authorized") pull?.pullNow()
                    }
                } finally {
                    if (started == epoch && revision == linkRevision) show { copy(busy = false) }
                }
            }
    }

    private fun work(
        busy: (InvitationsState) -> Boolean,
        setBusy: InvitationsState.(Boolean) -> InvitationsState,
        onError: InvitationsState.(String?) -> InvitationsState,
        block: suspend (CloudSession) -> Unit,
    ): Job? {
        val session = shown ?: return null
        if (mutable.value.access != FriendsAccess.READY || busy(mutable.value)) return null
        val started = epoch
        show { setBusy(true).onError(null) }
        return scope.launch {
            try {
                auth.requireAccountSession(session)
                block(session)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Stale) {
                // Antwort für ein früheres Konto: nichts anzeigen.
            } catch (e: Exception) {
                if (started == epoch) {
                    val reason = (e as? CloudException)?.reason ?: "connection_failed"
                    show { onError(reason) }
                    if (reason == "device_not_authorized") pull?.pullNow()
                }
            } finally {
                if (started == epoch) show { setBusy(false) }
            }
        }
    }

    private fun List<OpenInvite>.claims(): Map<String, GuestInvite> {
        val time = now()
        return mapNotNull { invite ->
            invite.playerId?.takeIf { invite.kind == InviteKind.CLAIM_PLAYER && invite.expiresAtMillis > time }?.to(invite)
        }.groupBy({ it.first }, { it.second })
            .mapValues { (_, invites) -> GuestInvite(invites.map { it.id }, invites.maxOf { it.expiresAtMillis }) }
    }

    private inline fun show(update: InvitationsState.() -> InvitationsState) {
        mutable.value = mutable.value.update()
    }

    companion object {
        const val NEEDS_ACCOUNT = "invite_needs_account"
        const val QR_TTL_MINUTES = 10
        private const val MINUTE = 60_000L
        internal const val KEY_TOKEN = "invite.token"
        internal const val KEY_OWNER = "invite.owner"

        /** Endgültige Antworten von `redeem_invite` → Status wie in der Vorschau. */
        private val REDEEM_FINAL =
            mapOf(
                "invalid_invite" to InviteStatus.INVALID.wire,
                "player_redacted" to InviteStatus.INVALID.wire,
                "player_not_found" to InviteStatus.INVALID.wire,
                "own_invite" to InviteStatus.OWN.wire,
                "already_assigned" to InviteStatus.ALREADY_CLAIMED.wire,
            )
    }
}
