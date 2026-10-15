package eu.advantly.wear.glance

import eu.advantly.core.model.MatchFormat
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.core.scoring.SidePair
import eu.advantly.match.session.SessionState
import eu.advantly.wear.match.WatchSetup

/** Kleine, unveränderliche Lesesicht. Kein DB-Zugriff und kein Event-Replay in den Provider-Callbacks. */
data class GlanceState(
    val live: GlanceMatch? = null,
    val last: GlanceSetup? = null,
)

data class GlancePlayer(
    val name: String?,
    val isSelf: Boolean,
    val side: Side,
    val index: Int,
)

data class GlanceSetup(
    val format: MatchFormat,
    val players: List<GlancePlayer>,
)

data class GlanceMatch(
    val sets: String,
    val games: String?,
    val points: String?,
    val server: GlancePlayer?,
    val scorer: Boolean,
    val status: MatchStatus,
)

/** Ableitung ausschließlich aus dem bereits reduzierten Session-Zustand und vorbereiteten Verzeichniswerten. */
object GlanceModel {
    fun derive(
        state: SessionState,
        last: WatchSetup?,
        name: (PlayerId) -> String?,
        isSelf: (PlayerId) -> Boolean,
    ): GlanceState {
        fun player(
            id: PlayerId,
            side: Side,
            index: Int,
        ) = GlancePlayer(name(id)?.takeIf { it.isNotBlank() }, isSelf(id), side, index)

        val setup =
            last?.let { s ->
                GlanceSetup(
                    s.format,
                    s.lineup.a.mapIndexed { i, id -> player(id, Side.A, i) } +
                        s.lineup.b.mapIndexed { i, id -> player(id, Side.B, i) },
                )
            }
        // Auf der Uhr ist nur das fokussierte Match ihr Match; niemals andere Director-Plätze auswählen.
        val match = state.match?.takeIf { it.state.status != MatchStatus.FINISHED }
        val live =
            match?.let { m ->
                val score = m.state
                val server =
                    score.serve?.player?.let { id ->
                        m.lineup.sideOf(id)?.let { side -> player(id, side, m.lineup.players(side).indexOf(id)) }
                    }
                GlanceMatch(
                    sets = pair(score.setsWon),
                    games = score.current?.games?.let(::pair),
                    points = score.current?.display?.let(::pair),
                    server = server,
                    scorer = m.isScorer,
                    status = score.status,
                )
            }
        return GlanceState(live, setup)
    }

    private fun pair(value: SidePair<*>): String = "${value.a}:${value.b}"
}

/** Führende Aktualisierung plus nachlaufende letzte Änderung; gleiche Werte erzeugen keine Wakeups. */
class GlanceUpdateThrottle(
    private val intervalMs: Long = 5_000,
) {
    init {
        require(intervalMs > 0)
    }

    private var lastSentAt: Long? = null
    private var sent: GlanceState? = null

    /** `null`: unverändert; sonst verbleibende Wartezeit. Zeit kommt monoton vom Aufrufer. */
    fun delayFor(
        state: GlanceState,
        nowMs: Long,
    ): Long? {
        if (state == sent) return null
        val last = lastSentAt ?: return 0
        return (intervalMs - (nowMs - last).coerceAtLeast(0)).coerceAtLeast(0)
    }

    fun sent(
        state: GlanceState,
        nowMs: Long,
    ) {
        sent = state
        lastSentAt = nowMs
    }
}

enum class GlanceAction { OPEN, REPEAT, NEW }

/** Veraltete Tile-Tipps dürfen ein inzwischen laufendes Match weder ersetzen noch schließen. */
object GlanceLaunch {
    const val EXTRA_ACTION = "eu.advantly.wear.glance.ACTION"

    fun resolve(
        value: String?,
        matchStatus: MatchStatus?,
    ): GlanceAction =
        if (matchStatus != null && matchStatus != MatchStatus.FINISHED) {
            GlanceAction.OPEN
        } else {
            GlanceAction.entries.firstOrNull { it.name == value } ?: GlanceAction.OPEN
        }
}
