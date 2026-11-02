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

/** Warum eine angenommene Übergabe beim Empfänger ohne Wechsel endete (Hinweis in der Live-Ansicht). */
enum class AcceptIssue {
    /** Ein späterer Zählerwechsel ging an ein anderes Gerät: Der bisherige Zähler zählt weiter. */
    FELL_BACK,

    /** Rolle widerrufen oder abgelehnt, Match beendet oder gelöscht. */
    ENDED,

    /** Frist abgelaufen: normaler Abgleich, der Wechsel kann noch kommen. */
    TIMED_OUT,
}

/**
 * Angenommene Zähl-Einladung des Empfängers, deren Wechsel noch aussteht: angenommen mit [device] um [acceptedAtMs]
 * (Wanduhr, übersteht Neustarts); [since]: jüngster Zählerwechsel im lokalen Log bei Annahme (`null`: Log war noch leer).
 */
data class ScorerAccept(
    val device: String,
    val acceptedAtMs: Long,
    val since: EventOrder? = null,
    val issue: AcceptIssue? = null,
)

/** Engine-Ordnung eines Events: [lamport], bei Gleichstand [device]. */
data class EventOrder(
    val lamport: Long,
    val device: String,
) : Comparable<EventOrder> {
    override fun compareTo(other: EventOrder) = compareValuesBy(this, other, EventOrder::lamport, EventOrder::device)

    companion object {
        /** Vor jedem Event: das Log hat noch keinen Zählerwechsel. */
        val START = EventOrder(-1, "")
    }
}

/** Jüngster Zählerwechsel im lokalen Log eines Matches: seine Ordnung ([EventOrder.START] ohne Wechsel) und sein Ziel. */
data class ScorerChanges(
    val last: EventOrder,
    val lastTarget: String?,
)

/** Persistente Zähl-Einladungen je Konto (Host: z. B. SharedPreferences): Match-ID → Einladung bzw. Annahme. */
interface PendingScorerStore {
    fun scorerInvites(profileId: String): Map<String, ScorerInvite>

    fun setScorerInvites(
        profileId: String,
        invites: Map<String, ScorerInvite>,
    )

    fun scorerAccepts(profileId: String): Map<String, ScorerAccept>

    fun setScorerAccepts(
        profileId: String,
        accepts: Map<String, ScorerAccept>,
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

    /** Zählerwechsel im lokalen Log von [matchId]; `null`, solange es leer ist. */
    fun scorerChanges(matchId: String): ScorerChanges?

    /**
     * Abgabe an [deviceId] (`ScorerChanged`): zählt dieses Handy, sofort; zählt die eigene Uhr, erzeugt sie es nach ihrer
     * letzten Eingabe ([watchPending] bis dahin). Liefert die Abgabe, sobald es das Event gibt (wiederholbar, solange
     * [deviceId] zählt); `null`, solange die Uhr noch aussteht oder keines meiner Geräte zählt.
     */
    fun handOver(
        matchId: String,
        deviceId: String,
    ): Handover?

    /** Die eigene Uhr zählt [matchId] und hat den Abgabe-Auftrag noch nicht ausgeführt (z. B. nicht verbunden). */
    fun watchPending(matchId: String): Boolean = false

    /** Offenen Abgabe-Auftrag an die eigene Uhr zurücknehmen (Einladung beendet), damit sie ihn nicht später ausführt. */
    fun cancelHandOver(matchId: String) {}

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

    /** Angenommen; die eigene Uhr zählt und gibt ab, sobald sie verbunden ist (nach ihrer letzten Eingabe). */
    AWAITING_WATCH,

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
 *   eigene Gerät wieder ([HandoverPhase.FAILED]) – außer die Rolle wurde inzwischen mit einem anderen Gerät angenommen,
 *   dann geht die Abgabe dorthin. Ablehnung oder Widerruf der Einladung beenden die Abgabe.
 * - **Empfänger:** [accept] registriert bei Bedarf die eigene Uhr, nimmt an und wartet (gespeichert), bis der Wechsel
 *   gezogen ist – höchstens [ACCEPT_LIMIT_MS]. Ein späterer Wechsel auf ein anderes Gerät, Widerruf oder Matchende beenden
 *   das Warten vorher; der Grund bleibt als [AcceptIssue] sichtbar, bis er weggeklickt ist.
 * [requestSync] stößt einen Abgleich an; [nowMs] ist Wanduhrzeit. Aufrufe auf dem seriellen Host-Kontext.
 */
class ScorerHandovers(
    private val store: PendingScorerStore,
    val port: ScorerRolesPort,
    private val onServer: (profileId: String, matchId: String) -> Boolean,
    private val pulled: (profileId: String, matchId: String) -> PulledMatch?,
    val devices: ScorerDevices,
    private val nowMs: () -> Long,
    private val requestSync: () -> Unit,
) {
    private val flushing = Mutex()

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
                accepted && devices.watchPending(matchId) -> HandoverPhase.AWAITING_WATCH
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
        if (invite.handover == null) devices.cancelHandOver(matchId)
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
        val waiting = reconcileAccepts(profileId)
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
                        // Annahme inzwischen mit einem anderen Gerät: an dieses abgeben statt aufzugeben (beim nächsten
                        // Abgleich, zählt die Uhr wieder, über sie). Wird auch das abgelehnt, ist es das angenommene Gerät –
                        // dann FAILED; erneut nur nach echtem Gerätewechsel.
                        val role = pulled(profileId, matchId)?.roleOf(invite.scorerId)
                        if (role?.status == "accepted" && role.deviceId != null && role.deviceId != handover.to) {
                            put(profileId, matchId, invite.copy(handover = null))
                        } else {
                            remove(profileId, matchId)
                            failed[profileId to matchId] = invite.scorerId
                        }
                        requestSync() // Rückgabe sofort hochladen
                    }

                    null -> {}
                }
                continue
            }
            val role = pulled(profileId, matchId)?.roleOf(invite.scorerId)
            val device = role?.deviceId
            // zählt die eigene Uhr, gibt sie selbst ab: erst dann gibt es das Event (zuvor zählt sie noch, siehe unten)
            val handed = device?.takeIf { role.status == "accepted" }?.let { devices.handOver(matchId, it) }
            when {
                handed != null -> {
                    put(profileId, matchId, invite.copy(handover = handed))
                    requestSync() // ScorerChanged sofort hochladen
                }

                // Match beendet oder ein anderes Gerät zählt inzwischen (Notfall-Übernahme): nichts mehr abzugeben.
                // „Meine Uhr“ vor ihrer Meldung zählt als meines: Die Abgabe wartet, bis sie sich gemeldet hat.
                !devices.scoring(matchId) -> {
                    remove(profileId, matchId)
                }

                // Angenommen ohne Gerät gibt es kein Ziel (der Server lässt das nicht mehr zu): wie ein Widerruf
                role?.status in setOf("declined", "revoked") || (role?.status == "accepted" && device == null) -> {
                    devices.cancelHandOver(matchId)
                    remove(profileId, matchId)
                }
            }
        }
        return waiting || store.scorerInvites(profileId).isNotEmpty()
    }

    /** Empfänger: Annahmen abhaken oder mit Grund beenden; `true`, solange eine noch auf den Wechsel wartet. */
    private fun reconcileAccepts(profileId: String): Boolean {
        val now = nowMs()
        val accepts =
            store
                .scorerAccepts(profileId)
                .mapNotNull { (matchId, accept) ->
                    val match = pulled(profileId, matchId)
                    val role = match?.roleOf(profileId)?.status
                    val changes = devices.scorerChanges(matchId)
                    val next =
                        when {
                            // (a) Wechsel gesehen
                            devices.scoring(matchId) -> {
                                null
                            }

                            // nicht (mehr) gezogen: nur bis zur Frist warten; einen Hinweis zeigte kein Eintrag mehr
                            match == null -> {
                                accept.takeIf { it.issue == null }?.timedOut(now)
                            }

                            // erneut eingeladen: Die Einladung zeigt sich wieder statt des Hinweises
                            accept.issue != null && role == "invited" -> {
                                null
                            }

                            // (c) Rolle oder Match beendet
                            match.deleted || match.status == "finished" || role == "declined" || role == "revoked" -> {
                                accept.copy(issue = AcceptIssue.ENDED)
                            }

                            // (b) ein späterer Wechsel (Engine-Ordnung nach dem bei Annahme jüngsten) auf ein anderes Gerät,
                            // z. B. die Rückgabe nach einer abgelehnten Abgabe; erst jetzt gezogene ältere Wechsel zählen nicht
                            accept.since != null && changes != null && changes.last > accept.since &&
                                changes.lastTarget != accept.device -> {
                                accept.copy(issue = AcceptIssue.FELL_BACK)
                            }

                            // (d) Frist; war das Log bei Annahme leer, gilt der erste bekannte Stand als Ausgangspunkt
                            else -> {
                                (if (accept.since == null) accept.copy(since = changes?.last) else accept).timedOut(now)
                            }
                        }
                    next?.let { matchId to it }
                }.toMap()
        store.setScorerAccepts(profileId, accepts)
        return accepts.values.any { it.issue == null }
    }

    private fun ScorerAccept.timedOut(now: Long) =
        if (issue == null && now - acceptedAtMs >= ACCEPT_LIMIT_MS) copy(issue = AcceptIssue.TIMED_OUT) else this

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
        val accept = ScorerAccept(device, nowMs(), devices.scorerChanges(matchId)?.last)
        store.setScorerAccepts(profileId, store.scorerAccepts(profileId) + (matchId to accept))
        requestSync()
    }

    suspend fun decline(
        profileId: String,
        matchId: String,
    ) {
        port.respondScorerInvitation(profileId, matchId, false, null)
        store.setScorerAccepts(profileId, store.scorerAccepts(profileId) - matchId)
        requestSync()
    }

    /** Ich habe angenommen und warte noch auf den Wechsel (innerhalb der Frist). */
    fun awaiting(
        profileId: String,
        matchId: String,
    ): Boolean = !devices.scoring(matchId) && current(profileId, matchId)?.let { it.issue == null } == true

    /** Grund, warum meine Annahme ohne Wechsel endete; `null` beim Warten, nach dem Wechsel oder ohne Annahme. */
    fun acceptIssue(
        profileId: String,
        matchId: String,
    ): AcceptIssue? = if (devices.scoring(matchId)) null else current(profileId, matchId)?.issue

    /** Nach Ablauf der Frist erneut warten (wieder [ACCEPT_LIMIT_MS] lang mit schneller Abfrage). */
    fun retryAccept(
        profileId: String,
        matchId: String,
    ) {
        val accepts = store.scorerAccepts(profileId)
        val accept = current(profileId, matchId)?.takeIf { it.issue == AcceptIssue.TIMED_OUT } ?: return
        store.setScorerAccepts(profileId, accepts + (matchId to accept.copy(acceptedAtMs = nowMs(), issue = null)))
        requestSync()
    }

    /** Hinweis wegklicken; eine noch wartende Annahme bleibt. */
    fun dismissAcceptIssue(
        profileId: String,
        matchId: String,
    ) {
        if (acceptIssue(profileId, matchId) != null) store.setScorerAccepts(profileId, store.scorerAccepts(profileId) - matchId)
    }

    /** Gespeicherte Annahme; die Frist gilt auch ohne Abgleich (etwa offline). */
    private fun current(
        profileId: String,
        matchId: String,
    ) = store.scorerAccepts(profileId)[matchId]?.timedOut(nowMs())

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

        /** Höchstens so lange nach der Annahme schnell abfragen; danach gilt der normale Abgleich. */
        const val ACCEPT_LIMIT_MS = 2 * 60_000L
    }
}
