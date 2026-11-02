package eu.advantly.android.sync

import eu.advantly.cloud.sync.LiveMatchesPort
import eu.advantly.core.model.MatchId
import eu.advantly.device.link.LiveViewsProvider
import eu.advantly.match.session.PeerInfo
import eu.advantly.presentation.friends.LiveEntry
import eu.advantly.presentation.friends.toLiveEntry
import eu.advantly.presentation.live.LiveTelemetryGate
import eu.advantly.sync.protocol.LiveView
import eu.advantly.sync.protocol.LiveViewSubscriptionCodec
import eu.advantly.sync.protocol.LiveViews
import eu.advantly.sync.protocol.LiveViewsCodec
import eu.advantly.sync.protocol.PeerKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import eu.advantly.sync.protocol.LiveRelation as WireRelation

/**
 * Uhr-Zuschauen auf dem Handy (plan-live-mitspieler §3.2): Jede [eu.advantly.sync.protocol.LiveViewSubscription] der
 * eigenen, kompatiblen Uhr löst genau einen Abruf über [provider] aus; die Antwort geht als [LiveViews] zurück.
 * Kein Abo-Zustand, kein Cache, kein Hintergrunddienst.
 */
object PhoneLiveViews {
    /** Das Konto-Paket setzt hier seine Implementierung (`cloud_my_live_matches_v1`); ohne Konto nie etwas live. */
    @Volatile var provider: LiveViewsProvider = LiveViewsProvider.Empty

    const val TIMEOUT_MS = 5_000L

    /**
     * Blockiert den aufrufenden Worker-Thread höchstens [timeoutMs]: So bleibt der vom System gestartete Prozess für
     * den Abruf am Leben. Fehler oder Zeitüberschreitung: keine Antwort (die Uhr behält ihren Stand und fragt in 10 s neu).
     *
     * @return gesendet?
     */
    fun respond(
        sourceNodeId: String,
        data: ByteArray,
        peers: List<PeerInfo>,
        send: (nodeId: String, path: String, payload: ByteArray) -> Unit,
        provider: LiveViewsProvider = this.provider,
        timeoutMs: Long = TIMEOUT_MS,
    ): Boolean {
        LiveViewSubscriptionCodec.decode(data) ?: return false
        if (!LiveTelemetryGate.compatiblePeer(peers, sourceNodeId, PeerKind.WATCH)) return false
        val matches =
            try {
                runBlocking { withTimeout(timeoutMs) { provider.current() } }
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
 * `cloud_my_live_matches_v1` je Aufruf, nur mit angemeldetem Konto ([profile] `null` → nichts live). Fehler des Ports
 * gehen als Exception hinaus, damit die Uhr ihren letzten Stand behält. Unbekannte Beziehung oder Sportart fällt weg.
 */
internal class AccountLiveViews(
    private val port: LiveMatchesPort,
    private val profile: () -> String?,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : LiveViewsProvider {
    override suspend fun current(): List<LiveView> {
        val id = profile() ?: return emptyList()
        val now = nowMs()
        return port.liveMatches(id).mapNotNull { it.toLiveEntry()?.toLiveView(now) }
    }
}

/** Projektion → Uhr-Anzeige: nur Namen und Spielstand, wie [LiveEntry] (nie Telemetrie oder IDs von Profilen). */
internal fun LiveEntry.toLiveView(nowMs: Long): LiveView? {
    val match = score.match
    return LiveView(
        matchId = MatchId(match.id),
        relation = WireRelation.valueOf(relation.name),
        sport = match.sport ?: return null,
        teamA = match.teamA.joinToString(" / "),
        teamB = match.teamB.joinToString(" / "),
        sets = match.sets.map { (a, b) -> listOf(a, b) },
        games = score.games?.let { (a, b) -> listOf(a, b) },
        points = score.points?.let { (a, b) -> listOf(a, b) },
        serving = serving,
        scorerConnected = scorerConnected,
        ageMs = updatedAtMs?.let { (nowMs - it).coerceAtLeast(0) },
    )
}
