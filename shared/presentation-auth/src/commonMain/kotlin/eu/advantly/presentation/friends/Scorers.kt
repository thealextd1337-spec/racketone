package eu.advantly.presentation.friends

import eu.advantly.cloud.sync.CloudException
import eu.advantly.cloud.sync.PulledMatch
import eu.advantly.cloud.sync.ScorerRolesPort
import eu.advantly.cloud.sync.roleOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Zähl-Einladung eines eigenen Matches an [scorerId]; [sent]: am Server (sonst wartet sie auf den Upload des Matches);
 * [handover]: bereits abgegeben, die Antwort des Servers auf das `ScorerChanged` steht noch aus.
 */
data class ScorerInvite(
    val scorerId: String,
    val sent: Boolean = false,
    val handover: Handover? = null,
)

/** Erzeugtes `ScorerChanged` einer Abgabe: Event [eventId] von [from] (eigenes Gerät, zählte vorher) an [to]. */
data class Handover(
    val eventId: String,
    val from: String,
    val to: String,
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

    /** Abgabe an [deviceId] (`ScorerChanged`); `null`, wenn keines meiner Geräte zählt. */
    fun handOver(
        matchId: String,
        deviceId: String,
    ): Handover?

    /** Antwort des Servers auf das Event: `true` angenommen (oder lokal unbekannt), `false` abgelehnt, `null` ausstehend. */
    fun uploaded(eventId: String): Boolean?

    /** Abgelehnte Abgabe: [Handover.from] zählt wieder, solange laut Log noch [Handover.to] zählt. */
    fun handBack(
        matchId: String,
        handover: Handover,
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

    /** Der Server lehnte die Abgabe ab; dieses Handy bzw. die eigene Uhr zählt weiter (Hinweis bis zum Wegklicken). */
    FAILED,
}

data class HandoverStatus(
    val scorerId: String,
    val phase: HandoverPhase,
)

/**
 * Zählen an ein anderes Konto weitergeben (plan-live-mitspieler §3.4, E6 „Abgabe durch den aktuellen Zähler“):
 * - **Abgebendes Handy:** [invite] lädt ein (vor dem Upload des Matches vorgemerkt, [flush] sendet nach dem Abgleich).
 *   [reconcile] nach jedem Abgleich: Sobald die gezogene Rolle angenommen ist, erzeugt dieses Handy `ScorerChanged` auf das
 *   angenommene Gerät; erledigt ist die Abgabe erst, wenn der Server es angenommen hat. Lehnt er es ab, zählt das vorige
 *   eigene Gerät wieder ([HandoverPhase.FAILED]). Ablehnung oder Widerruf der Einladung beenden die Abgabe.
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

    /** Vom Server abgelehnte Abgaben (Konto, Match) → eingeladene Person, bis der Hinweis weggeklickt ist (nur im Speicher). */
    private val failed = mutableMapOf<Pair<String, String>, String>()

    fun status(
        profileId: String,
        matchId: String,
    ): HandoverStatus? {
        failed[profileId to matchId]?.let { return HandoverStatus(it, HandoverPhase.FAILED) }
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
        (store.scorerInvites(profileId).keys + failed.keys.filter { it.first == profileId }.map { it.second })
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

    /** Einladung zurücknehmen (vorgemerkt nur lokal, sonst Rolle am Server entziehen); nach [HandoverPhase.FAILED] nur den Hinweis. */
    suspend fun cancel(
        profileId: String,
        matchId: String,
    ) {
        if (failed.remove(profileId to matchId) != null) return
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
            val handover = invite.handover
            if (handover != null) {
                // Lehnt der Server das ScorerChanged ab (z. B. Rolle oder Gerät inzwischen geändert), zählte sonst niemand.
                when (devices.uploaded(handover.eventId)) {
                    true -> {
                        remove(profileId, matchId)
                    }

                    false -> {
                        devices.handBack(matchId, handover)
                        remove(profileId, matchId)
                        failed[profileId to matchId] = invite.scorerId
                        requestSync() // Rückgabe sofort hochladen
                    }

                    null -> {}
                }
                continue
            }
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

                role?.status == "accepted" && device != null -> {
                    devices.handOver(matchId, device)?.let {
                        put(profileId, matchId, invite.copy(handover = it))
                        requestSync() // ScorerChanged sofort hochladen
                    }
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
