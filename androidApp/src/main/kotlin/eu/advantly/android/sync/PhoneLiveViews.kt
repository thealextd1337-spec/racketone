package eu.advantly.android.sync

import eu.advantly.cloud.sync.LiveMatchesPort
import eu.advantly.core.model.MatchId
import eu.advantly.device.link.LiveViewsProvider
import eu.advantly.match.session.PeerInfo
import eu.advantly.presentation.friends.CloudSlot
import eu.advantly.presentation.friends.LiveEntry
import eu.advantly.presentation.friends.toLiveEntry
import eu.advantly.presentation.live.LiveTelemetryGate
import eu.advantly.presentation.match.CloudPlayerName
import eu.advantly.sync.protocol.LiveView
import eu.advantly.sync.protocol.LiveViewSubscriptionCodec
import eu.advantly.sync.protocol.LiveViews
import eu.advantly.sync.protocol.LiveViewsCodec
import eu.advantly.sync.protocol.PeerKind
import eu.advantly.ui.phone.friends.toName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext
import eu.advantly.sync.protocol.LiveRelation as WireRelation

/**
 * Uhr-Zuschauen auf dem Handy (plan-live-mitspieler §3.2): Jede [eu.advantly.sync.protocol.LiveViewSubscription] der
 * eigenen, kompatiblen Uhr löst genau einen Abruf über den Provider aus; die Antwort geht als [LiveViews] zurück.
 * Kein Abo-Zustand, kein Cache, kein Hintergrunddienst.
 */
object PhoneLiveViews {
    const val TIMEOUT_MS = 5_000L

    /**
     * Blockiert den aufrufenden Worker-Thread höchstens [timeoutMs]: So bleibt der vom System gestartete Prozess für
     * den Abruf am Leben. Der Provider läuft auf [host] (Android: Main), denn Konto-Host und Cloud-Sitzung prüfen ihren
     * Thread; das Netz-I/O suspendiert dort nur. Fehler oder Zeitüberschreitung: keine Antwort (die Uhr behält ihren
     * Stand und fragt in 10 s neu).
     *
     * @return gesendet?
     */
    fun respond(
        sourceNodeId: String,
        data: ByteArray,
        peers: List<PeerInfo>,
        send: (nodeId: String, path: String, payload: ByteArray) -> Unit,
        provider: LiveViewsProvider,
        timeoutMs: Long = TIMEOUT_MS,
        host: CoroutineContext = Dispatchers.Main.immediate,
    ): Boolean {
        LiveViewSubscriptionCodec.decode(data) ?: return false
        if (!LiveTelemetryGate.compatiblePeer(peers, sourceNodeId, PeerKind.WATCH)) return false
        val matches =
            try {
                runBlocking { withTimeout(timeoutMs) { withContext(host) { provider.current() } } }
            } catch (e: CancellationException) {
                return false // Zeitüberschreitung
            } catch (e: Exception) {
                return false
            }
        send(sourceNodeId, LiveViews.MESSAGE_PATH, LiveViewsCodec.encode(LiveViews(LiveViews.CURRENT_VERSION, matches)))
        return true
    }
}

/**
 * Konto-Implementierung von [LiveViewsProvider] (plan-live-mitspieler §3.2/§8): genau ein Abruf von
 * `cloud_my_live_matches_v1` je Aufruf, nur mit angemeldetem Konto ([profile] `null` → nichts live). Ohne geprüfte
 * Sitzung (Kaltstart des Prozesses durch die Uhr) erst [awaitAccount] abwarten. Fehler gehen als Exception hinaus,
 * damit die Uhr ihren letzten Stand behält. Unbekannte Beziehung oder Sportart fällt weg.
 */
internal class AccountLiveViews(
    private val port: LiveMatchesPort,
    private val profile: () -> String?,
    /** Lokalisiert „Spieler N“/„Gelöschter Spieler“ (Handy-Sprache; die Uhr folgt ihr standardmäßig). */
    private val label: (CloudPlayerName) -> String,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val awaitAccount: suspend () -> Unit = {},
) : LiveViewsProvider {
    override suspend fun current(): List<LiveView> {
        if (profile() == null) awaitAccount()
        val id = profile() ?: return emptyList()
        val now = nowMs()
        return port.liveMatches(id).mapNotNull { it.toLiveEntry()?.toLiveView(now, label) }
    }
}

/** Projektion → Uhr-Anzeige: nur Namen und Spielstand, wie [LiveEntry] (nie Telemetrie oder IDs von Profilen). */
internal fun LiveEntry.toLiveView(
    nowMs: Long,
    label: (CloudPlayerName) -> String,
): LiveView? {
    val match = score.match

    fun names(team: List<CloudSlot>) = team.joinToString(" / ") { label(it.toName(match.doubles)) }
    return LiveView(
        matchId = MatchId(match.id),
        relation = WireRelation.valueOf(relation.name),
        sport = match.sport ?: return null,
        teamA = names(match.teamA),
        teamB = names(match.teamB),
        sets = match.sets.map { (a, b) -> listOf(a, b) },
        games = score.games?.let { (a, b) -> listOf(a, b) },
        points = score.points?.let { (a, b) -> listOf(a, b) },
        serving = serving,
        scorerConnected = scorerConnected,
        ageMs = updatedAtMs?.let { (nowMs - it).coerceAtLeast(0) },
    )
}
