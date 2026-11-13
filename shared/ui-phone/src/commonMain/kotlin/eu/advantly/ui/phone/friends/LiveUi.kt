package eu.advantly.ui.phone.friends

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import eu.advantly.core.model.Side
import eu.advantly.presentation.friends.FriendsAccess
import eu.advantly.presentation.friends.FriendsState
import eu.advantly.presentation.friends.FriendsViewModel
import eu.advantly.presentation.friends.LiveEntry
import eu.advantly.presentation.friends.LiveRelation
import eu.advantly.presentation.match.WallClock
import eu.advantly.ui.phone.common.AdvantlyCard
import eu.advantly.ui.phone.common.SectionTitle
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.designsystem.Spacing
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.friends_feed_vs
import eu.advantly.ui.phone.resources.live_age_minutes
import eu.advantly.ui.phone.resources.live_age_seconds
import eu.advantly.ui.phone.resources.live_back
import eu.advantly.ui.phone.resources.live_badge
import eu.advantly.ui.phone.resources.live_gone
import eu.advantly.ui.phone.resources.live_only_display
import eu.advantly.ui.phone.resources.live_playing_empty
import eu.advantly.ui.phone.resources.live_playing_title
import eu.advantly.ui.phone.resources.live_scorer_offline
import eu.advantly.ui.phone.resources.live_watching_title
import eu.advantly.ui.phone.resources.scorer_invitation
import eu.advantly.ui.phone.resources.watching_empty
import eu.advantly.ui.phone.resources.watching_games
import eu.advantly.ui.phone.resources.watching_live
import eu.advantly.ui.phone.resources.watching_loading
import eu.advantly.ui.phone.resources.watching_stop
import eu.advantly.ui.phone.resources.watching_tiebreak
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Bereich „Live“ und Detail: Abfrage alle 5 s, solange sichtbar (Realtime ist aus, plan-live-mitspieler E1). */
internal const val LIVE_POLL_MS = 5_000L

/** Startseite: nur der Hinweis auf laufende Fremd-Matches, daher seltener – 15 s, solange zuletzt etwas live war. */
internal const val HOME_LIVE_POLL_MS = 15_000L

/** Startseite ohne laufende Fremd-Matches: selten nachsehen (Serverlast, Akku). */
internal const val HOME_IDLE_POLL_MS = 60_000L

/**
 * Fragt „Live“ alle [intervalMs] ab, solange die App im Vordergrund und die Ansicht sichtbar ist; liefert die Uhrzeit
 * im Sekundentakt für „Stand vor X s“. Ein geänderter Takt zählt ab der letzten Abfrage, ohne den Effekt neu zu starten.
 */
@Composable
private fun rememberLivePolling(
    model: FriendsViewModel,
    intervalMs: Long,
    clock: WallClock,
): Long {
    var now by remember { mutableLongStateOf(clock.nowMs()) }
    val interval by rememberUpdatedState(intervalMs)
    val scope = rememberCoroutineScope()
    LifecycleResumeEffect(model) {
        val job =
            scope.launch {
                var last: Long? = null
                while (true) {
                    now = clock.nowMs()
                    // Takt ab der letzten Abfrage, mit dem aktuellen Intervall (das Ergebnis kann es gerade geändert haben).
                    if (last == null || now - last >= interval) {
                        model.loadLive()
                        last = now
                    }
                    delay(1_000)
                }
            }
        onPauseOrDispose { job.cancel() }
    }
    return now
}

/** Freunde-Tab, Segment „Live“: Ich spiele mit · Ich schaue zu (laufend aus „Live“, beendete Zuschau-Matches). */
@Composable
internal fun LiveSection(
    model: FriendsViewModel,
    state: FriendsState,
    clock: WallClock,
    onOpen: (String) -> Unit,
) {
    val now = rememberLivePolling(model, LIVE_POLL_MS, clock)
    // Beendete Zuschau-Matches liefert „Live“ nicht mehr; ihr Ergebnis einmal je Öffnen.
    LaunchedEffect(model) { model.loadWatching() }
    val colors = AdvantlyTheme.colors
    Column(Modifier.fillMaxWidth().testTag("friends_live"), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        (state.liveError ?: state.watchingError)?.let { error ->
            Text(stringResource(errorLabel(error)), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("live_error"))
        }
        val live = state.live
        if (live == null && model.hasLive) {
            Text(stringResource(Res.string.watching_loading), color = colors.text2)
            return@Column
        }
        val (playing, watching) = live.orEmpty().partition { it.relation != LiveRelation.VISITOR }
        SectionTitle(stringResource(Res.string.live_playing_title))
        if (playing.isEmpty()) {
            Text(stringResource(Res.string.live_playing_empty), color = colors.text2, modifier = Modifier.testTag("live_playing_empty"))
        }
        playing.forEach { LiveCard(it, now) { onOpen(it.matchId) } }
        SectionTitle(stringResource(Res.string.live_watching_title))
        val liveIds = live.orEmpty().mapTo(mutableSetOf()) { it.matchId }
        val earlier = state.watching.orEmpty().filter { it.match.id !in liveIds }
        if (watching.isEmpty() && earlier.isEmpty()) {
            Text(stringResource(Res.string.watching_empty), color = colors.text2, modifier = Modifier.testTag("watching_empty"))
        }
        watching.forEach { LiveCard(it, now) { onOpen(it.matchId) } }
        earlier.forEach { watched ->
            WatchedRow(watched) { model.stopWatching(watched.match.id) }
            HorizontalDivider()
        }
    }
}

/** Startseite: laufende Fremd-Matches (nur Anzeige, PR 0) als „Live“ statt als tote Fortsetzen-Karte. */
@Composable
fun HomeLiveSection(
    model: FriendsViewModel,
    clock: WallClock,
    onOpen: (String) -> Unit,
) {
    val state by model.state.collectAsState()
    if (state.access != FriendsAccess.READY || !model.hasLive) return
    val live = state.live.orEmpty()
    val now = rememberLivePolling(model, if (live.isEmpty()) HOME_IDLE_POLL_MS else HOME_LIVE_POLL_MS, clock)
    if (live.isEmpty()) return
    Column(Modifier.fillMaxWidth().testTag("home_live")) {
        SectionTitle(stringResource(Res.string.live_badge))
        live.forEach { LiveCard(it, now, Modifier.padding(bottom = Spacing.m)) { onOpen(it.matchId) } }
    }
}

@Composable
private fun LiveCard(
    entry: LiveEntry,
    now: Long,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val match = entry.score.match
    val colors = AdvantlyTheme.colors
    AdvantlyCard(modifier.testTag("live_${match.id}"), onClick = onClick, border = colors.brand) {
        val relation = if (entry.relation == LiveRelation.VISITOR) Res.string.live_watching_title else Res.string.live_playing_title
        Text(
            "${stringResource(Res.string.live_badge)} · ${stringResource(relation)}".uppercase(),
            style = AdvantlyTheme.typography.overline,
            color = colors.brand,
        )
        Text(headline(match), style = MaterialTheme.typography.bodyLarge, color = colors.text)
        Text(stringResource(Res.string.friends_feed_vs, names(match, Side.A), names(match, Side.B)), color = colors.text2)
        val score =
            listOfNotNull(
                match.sets.takeIf { it.isNotEmpty() }?.let { match.setsText() },
                entry.score.games?.let { (a, b) -> stringResource(Res.string.watching_games, a, b) },
                entry.score.points?.let { (a, b) -> stringResource(Res.string.watching_live, a, b) },
                stringResource(Res.string.watching_tiebreak).takeIf { entry.score.tiebreak },
            )
        if (score.isNotEmpty()) Text(score.joinToString(" · "), color = colors.text, modifier = Modifier.testTag("live_score_${match.id}"))
        LiveStatus(entry, now)
        if (entry.scorerRole == "invited" && !entry.awaiting) {
            Text(
                stringResource(Res.string.scorer_invitation),
                color = colors.brand,
                modifier = Modifier.testTag("live_invited_${match.id}"),
            )
        }
    }
}

/** „Stand vor X s“ und der Hinweis, wenn der Zähler nicht verbunden ist (Stand kann veraltet sein). */
@Composable
private fun LiveStatus(
    entry: LiveEntry,
    now: Long,
) {
    val colors = AdvantlyTheme.colors
    entry.updatedAtMs?.let { at ->
        val seconds = ((now - at) / 1_000).coerceAtLeast(0)
        Text(
            if (seconds < 120) {
                stringResource(Res.string.live_age_seconds, seconds.toInt())
            } else {
                stringResource(Res.string.live_age_minutes, (seconds / 60).toInt())
            },
            style = AdvantlyTheme.typography.caption,
            color = colors.text2,
            modifier = Modifier.testTag("live_age_${entry.matchId}"),
        )
    }
    if (entry.scorerConnected == false) {
        Text(
            stringResource(Res.string.live_scorer_offline),
            style = AdvantlyTheme.typography.caption,
            color = colors.hint,
            modifier = Modifier.testTag("live_offline_${entry.matchId}"),
        )
    }
}

/**
 * Live-Ansicht eines fremden Matches (plan-live-mitspieler §3.1): großer Spielstand, Sätze, Aufschlag, Teams, Alter.
 * **Nur Anzeige** – kein Zählen, Übernehmen oder Beenden (PR 0 sperrt das in der Session). Fällt das Match aus „Live“
 * heraus (beendet, Recht entzogen), bleibt der letzte Stand mit Hinweis stehen.
 */
@Composable
fun LiveFollowScreen(
    model: FriendsViewModel,
    matchId: String,
    onBack: () -> Unit,
    clock: WallClock = WallClock.System,
    /** Eines meiner Geräte zählt nach der Übergabe: Zählansicht öffnen (plan-live-mitspieler §3.4). */
    onScore: (String) -> Unit = {},
) {
    val state by model.state.collectAsState()
    val now = rememberLivePolling(model, LIVE_POLL_MS, clock)
    val current = state.live?.firstOrNull { it.matchId == matchId }
    var last by remember(matchId) { mutableStateOf<LiveEntry?>(null) }
    SideEffect { if (current != null) last = current }
    val shown = current ?: last
    val gone = current == null && state.live != null
    val colors = AdvantlyTheme.colors
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(Spacing.xl)
                .testTag("live_follow"),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            TextButton(onBack, modifier = Modifier.testTag("live_follow_back")) { Text(stringResource(Res.string.live_back)) }
            if (shown == null) {
                Text(
                    stringResource(if (gone) Res.string.live_gone else Res.string.watching_loading),
                    color = colors.text2,
                    modifier = Modifier.testTag("live_follow_gone"),
                )
                return@Column
            }
            val match = shown.score.match
            Text(
                headline(match),
                style = AdvantlyTheme.typography.title,
                color = colors.text,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(Res.string.live_only_display), style = AdvantlyTheme.typography.caption, color = colors.text2)
            FollowScore(shown, landscape)
            if (gone) Text(stringResource(Res.string.live_gone), color = colors.hint, modifier = Modifier.testTag("live_follow_gone"))
            LiveStatus(shown, now)
            if (current != null) ScorerActions(model, current, onScore)
            if (shown.relation == LiveRelation.VISITOR) {
                TextButton({
                    model.stopWatching(matchId)
                    onBack()
                }, modifier = Modifier.testTag("live_follow_stop")) { Text(stringResource(Res.string.watching_stop)) }
            }
        }
    }
}
