package eu.advantly.presentation.push

import eu.advantly.cloud.sync.CloudSession
import eu.advantly.cloud.sync.ConsentStatus
import eu.advantly.cloud.sync.PUSH_PROVIDER_FCM
import eu.advantly.cloud.sync.PushTokenPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Push-Benachrichtigungen (docs/plan-live-mitspieler.md §3.3/§8). Die Nachricht trägt nur Art und IDs; den Text baut
// die App lokal. Jedes angemeldete Handy lädt sein Token hoch (stiller Abgleich); der Schalter regelt nur die Anzeige.

/** Android-Kanal je Kategorie. */
enum class PushChannel { FRIENDS, MATCHES, SCORING }

enum class PushKind(
    val wire: String,
    val channel: PushChannel,
) {
    FRIEND_REQUEST("friend_request", PushChannel.FRIENDS),
    FRIEND_ACCEPTED("friend_accepted", PushChannel.FRIENDS),
    PLAYER_ASSIGNED("player_assigned", PushChannel.MATCHES),
    MATCH_SHARED("match_shared", PushChannel.MATCHES),
    VISITOR_INVITED("visitor_invited", PushChannel.MATCHES),
    SCORER_INVITED("scorer_invited", PushChannel.SCORING),
    SCORER_RESPONDED("scorer_responded", PushChannel.SCORING),
    SCORER_CHANGED("scorer_changed", PushChannel.SCORING),

    /** Mein Gast wurde per Claim-Link übernommen (plan-einladungen E8); [PushMessage.playerId] ist der Gast. */
    PLAYER_CLAIMED("player_claimed", PushChannel.FRIENDS),
}

/** Abschnitt des Freunde-Tabs, den ein Antippen öffnet. */
enum class FriendsSection { FEED, FRIENDS, REQUESTS, LIVE }

/**
 * Ziel einer Benachrichtigung. [matchId] öffnet im Feed das Match-Detail, im Bereich „Live“ die Live-Ansicht des
 * Matches (mit Zähl-Einladung); läuft es nicht mehr, das Feed-Detail, sonst bleibt der Bereich „Live“.
 */
data class PushTarget(
    val section: FriendsSection,
    val matchId: String? = null,
)

/** Geprüfte FCM-data (`{"v":"1","kind":…,"matchId"?,"profileId"?,"playerId"?,"role"?}`); [profileId] ist der Auslöser. */
data class PushMessage(
    val kind: PushKind,
    val matchId: String? = null,
    val profileId: String? = null,
    /** Eigener Gast bei [PushKind.PLAYER_CLAIMED]; den Namen setzt die App lokal ein. */
    val playerId: String? = null,
) {
    /** Zuweisungen betreffen meist beendete Matches (Feed); alles rund ums laufende Match öffnet „Live“. */
    val target: PushTarget
        get() =
            when (kind) {
                PushKind.FRIEND_REQUEST -> PushTarget(FriendsSection.REQUESTS)

                PushKind.FRIEND_ACCEPTED, PushKind.PLAYER_CLAIMED -> PushTarget(FriendsSection.FRIENDS)

                PushKind.PLAYER_ASSIGNED -> PushTarget(FriendsSection.FEED, matchId)

                PushKind.MATCH_SHARED,
                PushKind.VISITOR_INVITED,
                PushKind.SCORER_INVITED,
                PushKind.SCORER_RESPONDED,
                PushKind.SCORER_CHANGED,
                -> PushTarget(FriendsSection.LIVE, matchId)
            }

    companion object {
        private val uuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        /** `null` bei fremder Version oder unbekannter Art; ungültige IDs fallen weg. */
        fun parse(data: Map<String, String>): PushMessage? {
            if (data["v"] != "1") return null
            val kind = PushKind.entries.firstOrNull { it.wire == data["kind"] } ?: return null

            fun id(key: String) = data[key]?.takeIf(uuid::matches)
            return PushMessage(kind, id("matchId"), id("profileId"), id("playerId"))
        }
    }
}

/** Profil-Abschnitt „Benachrichtigungen“ und einmaliger Hinweis; die Plattform fragt bei Bedarf die Berechtigung ab. */
interface NotificationSettingsPort {
    val enabled: StateFlow<Boolean>

    /** Einmaliger Hinweis (erste Freundschaft/Einladung), bis eingeschaltet oder weggeklickt. */
    val hint: StateFlow<Boolean>

    fun setEnabled(enabled: Boolean)

    fun dismissHint()
}

/** Gerätelokal (nicht im Backup): Anzeige gewünscht und zuletzt hochgeladenes Konto + Token. */
interface PushPreferences {
    /** Benachrichtigungen anzeigen; das Token hängt nicht daran. */
    var enabled: Boolean

    /** `"<profileId>\n<token>"` oder `null`. */
    var uploaded: String?
}

/** Android: FirebaseMessaging; [token] schaltet die automatische Erneuerung ein, [delete] wieder aus. */
interface PushTokenSource {
    suspend fun token(): String

    suspend fun delete()
}

/**
 * Token-Logik: Upload bei nutzbarer Kontositzung, unabhängig von [enabled] (Pushes stoßen auch still den Abgleich an),
 * je Prozess, Konto und Token einmal (jeder Aufruf zählt serverseitig als Aktivität, 180-Tage-Regel). Abmelden,
 * Löschantrag und Kontowechsel leeren das Server-Token und verwerfen das lokale; Ausschalten nicht.
 * Fehler bleiben still und werden beim nächsten [sync] wiederholt.
 */
class PushRegistration(
    private val prefs: PushPreferences,
    private val tokens: PushTokenSource,
    private val port: PushTokenPort,
    private val session: () -> CloudSession?,
) {
    private val mutex = Mutex()

    /** In diesem Prozess bestätigter Stand; nach jedem App-Start wird das Token erneut gesetzt. */
    private var confirmed: String? = null
    private val mutableEnabled = MutableStateFlow(prefs.enabled)
    val enabled: StateFlow<Boolean> = mutableEnabled.asStateFlow()

    /** Angemeldetes Konto; der Stand der Erklärseite gilt je Konto. */
    val profileId: String? get() = session()?.profileId

    /** Anzeige an (nach erteilter Systemberechtigung); lädt das Token hoch, falls noch nicht geschehen. */
    suspend fun enable() =
        mutex.withLock {
            setEnabled(true)
            quietly { upload() }
        }

    /**
     * Anzeige aus; das Token bleibt für den stillen Abgleich. Abmelden und Löschantrag ([signedOut]) leeren es zusätzlich.
     */
    suspend fun disable(signedOut: Boolean = false) =
        mutex.withLock {
            setEnabled(false)
            if (signedOut) quietly { clear() }
        }

    /** Neues Token, App-Start, nach jedem Abgleich: holt Ausstehendes nach (auch das Leeren nach einem Löschantrag). */
    suspend fun sync() =
        mutex.withLock {
            quietly {
                if (session()?.deletionRequested == true) {
                    if (prefs.uploaded != null) clear()
                } else {
                    upload()
                }
            }
        }

    private fun setEnabled(value: Boolean) {
        prefs.enabled = value
        mutableEnabled.value = value
    }

    private suspend fun upload() {
        val current =
            session()?.takeIf {
                it.onboarded && it.consentStatus == ConsentStatus.CURRENT && !it.suspended &&
                    !it.deletionRequested
            }
        if (current == null) return
        // Kontowechsel ohne Abmelden: das Token des alten Kontos darf hier nicht weiter zustellen.
        if (prefs.uploaded?.substringBefore('\n')?.let { it != current.profileId } == true) clear()
        val token = tokens.token()
        val key = current.profileId + "\n" + token
        if (confirmed == key) return
        port.setPushToken(current.profileId, PUSH_PROVIDER_FCM, token)
        prefs.uploaded = key
        confirmed = key
    }

    /**
     * Lokal zuerst (Auto-Init aus, Token löschen): Push ist sofort aus, auch wenn der Server gerade nicht erreichbar ist.
     * Solange [PushPreferences.uploaded] gesetzt bleibt, holt [sync] das Leeren nach (Löschantrag) bzw. ersetzt das Token
     * (erneute Anmeldung, Kontowechsel).
     */
    private suspend fun clear() {
        confirmed = null
        val local =
            try {
                tokens.delete()
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            }
        val profile = prefs.uploaded?.substringBefore('\n')
        // Ohne passende Sitzung bleibt das Server-Token liegen; nach deleteToken meldet FCM es als UNREGISTERED.
        if (profile != null && session()?.profileId == profile) port.setPushToken(profile, PUSH_PROVIDER_FCM, null)
        local?.let { throw it }
        prefs.uploaded = null
    }

    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Netz, Bindung oder Firebase nicht bereit: der nächste sync versucht es erneut.
        }
    }
}
