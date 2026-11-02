package eu.advantly.presentation.friends

import eu.advantly.cloud.sync.CloudException
import eu.advantly.cloud.sync.PulledMatch
import eu.advantly.cloud.sync.ScorerRolesPort
import eu.advantly.cloud.sync.roleOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Zähl-Einladung eines eigenen Matches an [scorerId]; [sent]: am Server (sonst wartet sie auf den Upload des Matches). */
data class ScorerInvite(
    val scorerId: String,
    val sent: Boolean = false,
)

/** Persistente Zähl-Einladungen je Konto (Host: z. B. SharedPreferences): Match-ID → Einladung. */
interface PendingScorerStore {
    fun scorerInvites(profileId: String): Map<String, ScorerInvite>

    fun setScorerInvites(
        profileId: String,
        invites: Map<String, ScorerInvite>,
    )
}

/** Geräteseite der Übergabe (Android: `MatchSession`); alle Aufrufe auf dem Host-Kontext. IDs = Cloud-Geräte-IDs. */
interface ScorerDevices {
    /** Dieses Handy. */
    val phoneId: String

    /** Eigene Uhr (verbunden bzw. die einzige bekannte), sonst `null`. */
    fun watchId(): String?

    /** Eines meiner Geräte zählt das laufende Match [matchId]. */
    fun scoring(matchId: String): Boolean

    /** Abgabe an [deviceId] (`ScorerChanged`); `false`, wenn keines meiner Geräte zählt. */
    fun handOver(
        matchId: String,
        deviceId: String,
    ): Boolean

    /** Notfall-Übernahme möglich: Fremd-Match mit vollständigem Stand, keines meiner Geräte zählt. */
    fun canTakeOver(matchId: String): Boolean

    fun takeOver(matchId: String): Boolean
}

/** Stand einer Abgabe des zählenden Handys für die Anzeige. */
enum class HandoverPhase {
    /** Match noch nicht hochgeladen, Einladung vorgemerkt. */
    QUEUED,

    /** Einladung am Server; dieses Handy zählt weiter, bis angenommen wurde. */
    INVITED,

    /** Angenommen; die Abgabe folgt beim nächsten Abgleich. */
    ACCEPTED,
}

data class HandoverStatus(
    val scorerId: String,
    val phase: HandoverPhase,
)

/**
 * Zählen an ein anderes Konto weitergeben (plan-live-mitspieler §3.4, E6 „Abgabe durch den aktuellen Zähler“):
 * - **Abgebendes Handy:** [invite] lädt ein (vor dem Upload des Matches vorgemerkt, [flush] sendet nach dem Abgleich).
 *   [reconcile] nach jedem Abgleich: Sobald die gezogene Rolle angenommen ist, erzeugt dieses Handy `ScorerChanged` auf das
 *   angenommene Gerät; Ablehnung oder Widerruf beenden die Abgabe.
 * - **Empfänger:** [accept] registriert bei Bedarf die eigene Uhr, nimmt an und wartet, bis der Wechsel gezogen ist.
 * [requestSync] stößt einen Abgleich an. Aufrufe auf dem seriellen Host-Kontext.
 */
class ScorerHandovers(
    private val store: PendingScorerStore,
    val port: ScorerRolesPort,
    private val onServer: (profileId: String, matchId: String) -> Boolean,
    private val pulled: (profileId: String, matchId: String) -> PulledMatch?,
    val devices: ScorerDevices,
    private val requestSync: () -> Unit,
) {
    private val flushing = Mutex()

    /** Angenommene Einladungen, deren Zählerwechsel noch nicht gezogen ist (nur im Speicher). */
    private val awaiting = mutableSetOf<String>()

    fun status(
        profileId: String,
        matchId: String,
    ): HandoverStatus? {
        val invite = store.scorerInvites(profileId)[matchId] ?: return null
        val accepted = invite.sent && pulled(profileId, matchId)?.roleOf(invite.scorerId)?.status == "accepted"
        val phase =
            when {
                !invite.sent -> HandoverPhase.QUEUED
                accepted -> HandoverPhase.ACCEPTED
                else -> HandoverPhase.INVITED
            }
        return HandoverStatus(invite.scorerId, phase)
    }

    /** Laufende Abgaben dieses Kontos. */
    fun statuses(profileId: String): Map<String, HandoverStatus> =
        store
            .scorerInvites(profileId)
            .keys
            .mapNotNull { id -> status(profileId, id)?.let { id to it } }
            .toMap()

    /** [scorerId] zum Zählen von [matchId] einladen; vor dem Upload vorgemerkt. Fehler am Server werfen. */
    suspend fun invite(
        profileId: String,
        matchId: String,
        scorerId: String,
    ) {
        if (!onServer(profileId, matchId)) {
            put(profileId, matchId, ScorerInvite(scorerId))
            requestSync()
            return
        }
        port.inviteScorer(profileId, matchId, scorerId)
        put(profileId, matchId, ScorerInvite(scorerId, sent = true))
        requestSync()
    }

    /** Einladung zurücknehmen (vorgemerkt nur lokal, sonst Rolle am Server entziehen). */
    suspend fun cancel(
        profileId: String,
        matchId: String,
    ) {
        val invite = store.scorerInvites(profileId)[matchId] ?: return
        if (invite.sent) port.revokeScorer(profileId, matchId, invite.scorerId)
        remove(profileId, matchId)
    }

    /**
     * Vorgemerkte Einladungen hochgeladener Matches senden. Endgültige Ablehnungen verwerfen den Eintrag; Netz-, Bindungs-
     * und Limitfehler bleiben für den nächsten Lauf.
     */
    suspend fun flush(profileId: String) =
        flushing.withLock {
            for ((matchId, invite) in store.scorerInvites(profileId)) {
                if (invite.sent || !onServer(profileId, matchId)) continue
                try {
                    port.inviteScorer(profileId, matchId, invite.scorerId)
                    put(profileId, matchId, invite.copy(sent = true))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: CloudException) {
                    if (e.reason !in FINAL_REASONS) return@withLock
                    remove(profileId, matchId)
                }
            }
        }

    /**
     * Nach jedem Abgleich (Pull): angenommene Rollen abgeben, beendete Abgaben vergessen, gesehene Wechsel abhaken.
     * `true`: Es gibt noch offene Übergaben – der Host fragt dann alle 5 s ab, bis der Wechsel gesehen ist.
     */
    fun reconcile(profileId: String): Boolean {
        for ((matchId, invite) in store.scorerInvites(profileId)) {
            if (!invite.sent) continue
            val role = pulled(profileId, matchId)?.roleOf(invite.scorerId)
            val device = role?.deviceId
            when {
                // Match beendet oder ein anderes Gerät zählt inzwischen (Notfall-Übernahme): nichts mehr abzugeben
                !devices.scoring(matchId) -> {
                    remove(profileId, matchId)
                }

                role?.status in setOf("declined", "revoked") -> {
                    remove(profileId, matchId)
                }

                role?.status == "accepted" && device != null && devices.handOver(matchId, device) -> {
                    remove(profileId, matchId)
                    requestSync() // ScorerChanged sofort hochladen
                }
            }
        }
        awaiting.removeAll { devices.scoring(it) }
        return awaiting.isNotEmpty() || store.scorerInvites(profileId).isNotEmpty()
    }

    /**
     * Einladung annehmen: auf diesem Handy oder auf der eigenen Uhr ([onWatch]; sie wird vorher am Server registriert).
     * Danach wartet das Handy, bis der Abgebende `ScorerChanged` erzeugt hat und es gezogen ist.
     */
    suspend fun accept(
        profileId: String,
        matchId: String,
        onWatch: Boolean,
    ) {
        val device =
            if (onWatch) {
                val watch = devices.watchId() ?: throw CloudException("watch_unavailable", false)
                port.registerWatch(profileId, watch, devices.phoneId)
                watch
            } else {
                devices.phoneId
            }
        port.respondScorerInvitation(profileId, matchId, true, device)
        awaiting += matchId
        requestSync()
    }

    suspend fun decline(
        profileId: String,
        matchId: String,
    ) {
        port.respondScorerInvitation(profileId, matchId, false, null)
        awaiting -= matchId
        requestSync()
    }

    /** Ich habe angenommen und warte noch auf den Wechsel. */
    fun awaiting(matchId: String): Boolean = matchId in awaiting

    private fun put(
        profileId: String,
        matchId: String,
        invite: ScorerInvite,
    ) = store.setScorerInvites(profileId, store.scorerInvites(profileId) + (matchId to invite))

    private fun remove(
        profileId: String,
        matchId: String,
    ) = store.setScorerInvites(profileId, store.scorerInvites(profileId) - matchId)

    companion object {
        /** Endgültig: Person darf nicht (mehr) zählen; `forbidden` bleibt wie bei Zuschauern kein Abbruch (auch abgelaufenes Token). */
        val FINAL_REASONS = setOf("scorer_not_allowed", "is_visitor", "cannot_invite_self")
    }
}
