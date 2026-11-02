package eu.advantly.wear.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwipeToDismissBox
import androidx.wear.compose.material3.Text
import eu.advantly.core.model.Side
import eu.advantly.sync.protocol.LiveRelation
import eu.advantly.sync.protocol.LiveView
import eu.advantly.sync.protocol.LiveViewSubscription
import eu.advantly.wear.R
import eu.advantly.wear.sync.ReceivedLiveViews
import kotlinx.coroutines.delay

/**
 * Zuschauen auf der Uhr (plan-live-mitspieler §3.2, PR 4): Liste der fremden Live-Matches (≤ 5) und Detail mit großem
 * Spielstand. Nur Anzeige – keine Zählflächen, kein Übernehmen, kein Beenden. Solange sichtbar und nicht im Ambient:
 * alle 10 s ein Abo an das Handy ([LiveViewSubscriptionEffect]); Verlassen beendet das Abo.
 */
@Composable
fun SpectateScreen(
    received: ReceivedLiveViews?,
    isAmbient: Boolean,
    subscribe: () -> Unit,
    onClose: () -> Unit,
    nowMs: () -> Long = SystemClock::elapsedRealtime,
) {
    LiveViewSubscriptionEffect(isAmbient, subscribe)
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    // Ist das Match aus der Liste verschwunden (beendet, Freigabe weg), zurück zur Liste.
    val match = received?.matches?.firstOrNull { it.matchId.value == selected }
    val goBack = { if (match != null) selected = null else onClose() }
    BackHandler(onBack = goBack)
    // Sekundentakt für „vor X s“, im Ambient nur minütlich
    val now by produceState(nowMs(), isAmbient) {
        while (true) {
            value = nowMs()
            delay(if (isAmbient) 60_000 else 1_000)
        }
    }
    val ageSeconds = received?.let { (now - it.receivedAtMs).coerceAtLeast(0) / 1_000 }
    key(match?.matchId) {
        SwipeToDismissBox(onDismissed = goBack) { isBackground ->
            if (isBackground) return@SwipeToDismissBox
            if (match != null) {
                SpectateDetail(match, ageSeconds?.plus((match.ageMs ?: 0) / 1_000))
            } else {
                SpectateList(received, ageSeconds) { selected = it.matchId.value }
            }
        }
    }
}

/**
 * Abo-Lebenszyklus: aktiv nur, solange die Ansicht komponiert, die Activity `RESUMED` und die Uhr nicht im Ambient ist;
 * sofort ein Abo beim Aktivwerden, dann alle [LiveViewSubscription.INTERVAL_MS].
 */
@Composable
fun LiveViewSubscriptionEffect(
    isAmbient: Boolean,
    subscribe: () -> Unit,
) {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow
        .collectAsState()
    val active = !isAmbient && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val current by rememberUpdatedState(subscribe)
    LaunchedEffect(active) {
        while (active) {
            current()
            delay(LiveViewSubscription.INTERVAL_MS)
        }
    }
}

@Composable
private fun SpectateList(
    received: ReceivedLiveViews?,
    ageSeconds: Long?,
    open: (LiveView) -> Unit,
) {
    // Titel „Zuschauen“ oben sichtbar, auch wenn der erste Eintrag lange Namen hat
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)
    ScreenScaffold(scrollState = listState) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxWidth()) {
            item { ListHeader { Text(stringResource(R.string.spectate_title), color = HintAmber) } }
            when {
                received == null -> {
                    item { Hint(stringResource(R.string.spectate_waiting)) }
                }

                received.matches.isEmpty() -> {
                    item { Hint(stringResource(R.string.spectate_empty)) }
                    item { Hint(stringResource(R.string.spectate_empty_hint), secondary = true) }
                }

                else -> {
                    received.matches.forEach { match ->
                        item {
                            Button(
                                onClick = { open(match) },
                                modifier = Modifier.fillMaxWidth().testTag(SPECTATE_ITEM_TAG),
                                colors = ButtonDefaults.filledTonalButtonColors(),
                                label = { Text("${match.teamA} – ${match.teamB}", maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                secondaryLabel = {
                                    Text(
                                        listOf(sportName(match.sport), scoreSummary(match)).filter { it.isNotEmpty() }.joinToString(" · "),
                                        maxLines = 2,
                                    )
                                },
                            )
                        }
                    }
                }
            }
            if (received != null) item { Hint(agoText(ageSeconds), secondary = true) }
        }
    }
}

@Composable
private fun SpectateDetail(
    match: LiveView,
    ageSeconds: Long?,
) {
    val listState = rememberScalingLazyListState()
    ScreenScaffold(scrollState = listState) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxWidth()) {
            item {
                Text(
                    stringResource(R.string.spectate_badge, sportName(match.sport)),
                    modifier = Modifier.testTag(SPECTATE_BADGE_TAG),
                    style = MaterialTheme.typography.labelSmall,
                    color = HintAmber,
                    textAlign = TextAlign.Center,
                )
            }
            item { TeamLine(match.teamA, match.serving == Side.A) }
            item {
                val big = match.points ?: match.games?.map(Int::toString)
                Text(
                    big?.joinToString(" : ") ?: "–",
                    modifier = Modifier.fillMaxWidth().testTag(SPECTATE_SCORE_TAG),
                    style = MaterialTheme.typography.numeralMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
            item { TeamLine(match.teamB, match.serving == Side.B) }
            val games = match.games?.takeIf { match.points != null }
            val sets = match.sets.joinToString(" ") { "${it[0]}:${it[1]}" }
            if (games != null || sets.isNotEmpty()) {
                item {
                    val gamesText = games?.let { stringResource(R.string.spectate_games, it[0], it[1]) }
                    Hint(listOfNotNull(gamesText, sets.takeIf { it.isNotEmpty() }).joinToString(" · "))
                }
            }
            item {
                Hint(if (match.finished) stringResource(R.string.spectate_finished) else agoText(ageSeconds), secondary = true)
            }
            if (!match.finished && !match.scorerConnected) {
                item { Hint(stringResource(R.string.spectate_scorer_offline), color = HintAmber) }
            }
            item { Hint(stringResource(relationText(match.relation)), secondary = true) }
        }
    }
}

@Composable
private fun TeamLine(
    name: String,
    serving: Boolean,
) {
    val description = if (serving) stringResource(R.string.spectate_serving) else ""
    Row(
        modifier = Modifier.fillMaxWidth().semantics { if (serving) stateDescription = description },
        horizontalArrangement = Arrangement.Center,
    ) {
        if (serving) Text("● ", color = HintAmber, style = MaterialTheme.typography.titleMedium)
        Text(
            name,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Hint(
    text: String,
    secondary: Boolean = false,
    color: androidx.compose.ui.graphics.Color = if (secondary) TextSecondary else MaterialTheme.colorScheme.onSurface,
) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        style = if (secondary) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
        color = color,
        textAlign = TextAlign.Center,
    )
}

/** Kurzform für die Liste: Sätze, Spiele, Punkte („6:4 3:6 · 2:1 · 40:AD“). */
fun scoreSummary(match: LiveView): String =
    listOfNotNull(
        match.sets.joinToString(" ") { "${it[0]}:${it[1]}" }.takeIf { it.isNotEmpty() },
        match.games?.let { "${it[0]}:${it[1]}" },
        match.points?.let { "${it[0]}:${it[1]}" },
    ).joinToString(" · ")

fun relationText(relation: LiveRelation): Int =
    when (relation) {
        LiveRelation.PLAYER -> R.string.spectate_relation_player
        LiveRelation.VISITOR -> R.string.spectate_relation_visitor
        LiveRelation.SCORER -> R.string.spectate_relation_scorer
    }

const val SPECTATE_ITEM_TAG = "spectate_item"
const val SPECTATE_BADGE_TAG = "spectate_badge"
const val SPECTATE_SCORE_TAG = "spectate_score"
const val SPECTATE_ENTRY_TAG = "home_spectate"
