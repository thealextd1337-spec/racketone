package eu.advantly.ui.phone.friends

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import eu.advantly.cloud.sync.BlockedProfile
import eu.advantly.cloud.sync.CloudSession
import eu.advantly.cloud.sync.ConsentStatus
import eu.advantly.cloud.sync.FoundProfile
import eu.advantly.cloud.sync.FriendRequestResult
import eu.advantly.cloud.sync.FriendsPort
import eu.advantly.cloud.sync.LiveMatch
import eu.advantly.cloud.sync.LiveMatchesPort
import eu.advantly.cloud.sync.MatchVisitor
import eu.advantly.cloud.sync.VisitorTeamMember
import eu.advantly.cloud.sync.VisitorView
import eu.advantly.cloud.sync.VisitorsPort
import eu.advantly.presentation.friends.FriendsViewModel
import eu.advantly.presentation.friends.PendingVisitorStore
import eu.advantly.presentation.friends.VisitorQueue
import eu.advantly.presentation.match.WallClock
import eu.advantly.presentation.push.FriendsSection
import eu.advantly.presentation.push.PushKind
import eu.advantly.presentation.push.PushMessage
import eu.advantly.presentation.push.PushTarget
import eu.advantly.ui.phone.auth.FakePhoneAuth
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

private const val NOW = 1_793_617_217_000L // 2026-11-02T11:00:17Z

private object NoFriends : FriendsPort {
    override suspend fun friendCode() = "K7QF92MX"

    override suspend fun findProfile(query: String): FoundProfile? = null

    override suspend fun sendFriendRequest(
        profileId: String,
        viaCode: Boolean,
    ) = FriendRequestResult.REQUESTED

    override suspend fun respondFriendRequest(
        requesterId: String,
        accept: Boolean,
    ) = Unit

    override suspend fun removeFriend(profileId: String) = Unit

    override suspend fun blockUser(profileId: String) = Unit

    override suspend fun unblockUser(profileId: String) = Unit

    override suspend fun blockedProfiles() = emptyList<BlockedProfile>()
}

private class LiveVisitors : VisitorsPort {
    val calls = mutableListOf<String>()
    var watching = emptyList<VisitorView>()

    override suspend fun addVisitor(
        profileId: String,
        matchId: String,
        visitorId: String,
    ) = Unit

    override suspend fun revokeVisitor(
        profileId: String,
        matchId: String,
        visitorId: String,
    ) {
        calls += "revoke:$matchId:$visitorId"
    }

    override suspend fun matchVisitors(
        profileId: String,
        matchId: String,
    ) = emptyList<MatchVisitor>()

    override suspend fun myVisitorMatches(profileId: String) = watching
}

private object NoPending : PendingVisitorStore {
    override fun pending(profileId: String) = emptyMap<String, Set<String>>()

    override fun setPending(
        profileId: String,
        pending: Map<String, Set<String>>,
    ) = Unit
}

private class UiLive(
    var matches: List<LiveMatch>,
) : LiveMatchesPort {
    var calls = 0

    override suspend fun liveMatches(profileId: String): List<LiveMatch> {
        calls++
        return matches
    }
}

private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject

private val running =
    LiveMatch(
        "m1",
        "player",
        "tennis",
        "singles",
        listOf(VisitorTeamMember("A", 1, "Bob"), VisitorTeamMember("B", 1, "Alice")),
        json("""{"sets":[[6,4]],"games":[3,2],"points":["40","AD"],"serving":"B","tiebreak":false}"""),
        // M2: unbekannt (ältere App des Zählers) – kein „Zähler nicht verbunden“
        scorerConnected = null,
        updatedAt = "2026-11-02T11:00:05Z",
    )

private val spectated =
    LiveMatch(
        "m2",
        "visitor",
        "padel",
        "doubles",
        listOf(
            VisitorTeamMember("A", 1, "Carol"),
            VisitorTeamMember("A", 2, "Dora"),
            VisitorTeamMember("B", 1, "Eve"),
            VisitorTeamMember("B", 2, "Finn"),
        ),
        json("""{"sets":[[2,1]],"games":[2,1],"points":["15","0"],"serving":"A"}"""),
        scorerConnected = false,
        updatedAt = "2026-11-02T10:57:00Z",
    )

/** Dunkel wie in der App: der Bildschirm liegt dort auf dem Hintergrund der Tab-Ebene. */
@Composable
private fun Dark(content: @Composable () -> Unit) =
    AdvantlyTheme(darkTheme = true) { Box(Modifier.fillMaxSize().background(AdvantlyTheme.colors.bg)) { content() } }

@OptIn(ExperimentalTestApi::class)
class LiveUiTest {
    private val clock = WallClock { NOW }

    private fun withLocale(
        locale: Locale,
        block: () -> Unit,
    ) {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(locale)
            block()
        } finally {
            Locale.setDefault(previous)
        }
    }

    private fun ComposeUiTest.click(tag: String) =
        onNodeWithTag(tag)
            .apply { runCatching { performScrollTo() } }
            .performClick()

    private fun ComposeUiTest.text(
        text: String,
        tag: String,
    ) = onNode(hasText(text, substring = true) and hasAnyAncestor(hasTestTag(tag))).assertExists()

    private fun ComposeUiTest.screenshot(name: String) {
        val directory = File(System.getenv("ADVANTLY_SCREENSHOT_DIR") ?: "build/reports/screenshots").apply { mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
    }

    private fun withModel(
        live: UiLive,
        visitors: LiveVisitors = LiveVisitors(),
        test: (FriendsViewModel) -> Unit,
    ) {
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        val auth = FakePhoneAuth().apply { session.value = CloudSession("me", true, consentStatus = ConsentStatus.CURRENT) }
        val queue = VisitorQueue(NoPending, visitors, { _, _ -> true }) {}
        val model = FriendsViewModel(auth, NoFriends, { emptyList() }, null, scope, visitorQueue = queue, liveMatches = live)
        try {
            test(model)
        } finally {
            model.close()
            scope.cancel()
        }
    }

    @Test fun liveSegmentSplitsPlayingAndWatchingInGerman() =
        withLocale(Locale.GERMAN) {
            val visitors =
                LiveVisitors().apply {
                    watching =
                        listOf(
                            VisitorView(
                                "m8",
                                "badminton",
                                "singles",
                                "finished",
                                winner = "A",
                                channel = "match-live:m8:1",
                                teams = listOf(VisitorTeamMember("A", 1, "Gina"), VisitorTeamMember("B", 1, "Hugo")),
                                result = json("""{"sets":[[21,15],[21,18]],"winner":"A"}"""),
                            ),
                        )
                }
            withModel(UiLive(listOf(running, spectated)), visitors) { model ->
                runDesktopComposeUiTest(width = 400, height = 1700) {
                    var opened: String? = null
                    setContent {
                        CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                            Dark {
                                FriendsScreen(model, onOpenAccount = {}, onShareText = null, onOpenLive = { opened = it }, clock = clock)
                            }
                        }
                    }
                    click("friends_segment_live")
                    waitForIdle()
                    // Eine Karte ist ein Knopf: Screenreader lesen ihren Text als Einheit.
                    val first = onNodeWithTag("live_m1")
                    first.assertTextContains("Bob gegen Alice")
                    first.assertTextContains("6:4 · Spiele 3:2 · Aktuell: 40 – AD")
                    first.assertTextContains("Stand vor 12 s")
                    onNodeWithTag("live_offline_m1", useUnmergedTree = true).assertDoesNotExist()
                    val second = onNodeWithTag("live_m2")
                    second.assertTextContains("Carol / Dora gegen Eve / Finn")
                    second.assertTextContains("Stand vor 3 min")
                    second.assertTextContains("Zähler nicht verbunden", substring = true)
                    // Beendetes Zuschau-Match bleibt mit Ergebnis unter „Ich schaue zu“.
                    onNodeWithTag("watching_sets_m8").assertTextContains("21:15  21:18")
                    screenshot("live-liste-dunkel-de")
                    click("live_m2")
                    assertEquals("m2", opened)
                }
            }
        }

    @Test fun matchPushOpensTheLiveViewWhileRunningAndOtherwiseStaysInLive() =
        withLocale(Locale.GERMAN) {
            val live = UiLive(listOf(running))
            withModel(live) { model ->
                runDesktopComposeUiTest(width = 400, height = 1200) {
                    var opened: String? = null
                    var link by mutableStateOf<PushTarget?>(null)
                    setContent {
                        Dark {
                            FriendsScreen(
                                model,
                                onOpenAccount = {},
                                onShareText = null,
                                onOpenLive = { opened = it },
                                clock = clock,
                                link = link,
                                onLinkHandled = { link = null },
                            )
                        }
                    }
                    // Zähl-Einladung zu einem laufenden Match: Live-Ansicht (dort steht die Einladung).
                    link = PushMessage(PushKind.SCORER_INVITED, "m1").target
                    waitForIdle()
                    assertEquals("m1", opened)
                    assertEquals(null, link)
                    // Zuschauer-Einladung (früher Absturz über valueOf): Match läuft nicht mehr → Bereich „Live“.
                    opened = null
                    link = PushMessage(PushKind.VISITOR_INVITED, "m9").target
                    waitForIdle()
                    assertEquals(null, opened)
                    assertEquals(null, link)
                    onNodeWithTag("friends_live").assertIsDisplayed()
                    link = PushTarget(FriendsSection.REQUESTS)
                    waitForIdle()
                    onNodeWithTag("friends_live").assertDoesNotExist()
                }
            }
        }

    @Test fun detailShowsBigScoreServeAndStaysOnlyADisplayInEnglish() =
        withLocale(Locale.ENGLISH) {
            val live = UiLive(listOf(running))
            withModel(live) { model ->
                runDesktopComposeUiTest(width = 400, height = 900) {
                    var back = 0
                    setContent {
                        Dark { LiveFollowScreen(model, "m1", onBack = { back++ }, clock = clock) }
                    }
                    waitForIdle()
                    // Je Team eine Einheit für Screenreader: Namen, Aufschlag, Sätze, Games, Punkte.
                    onNodeWithTag("live_follow_team_a").assertTextEquals("Bob", "6", "3", "40")
                    onNodeWithTag("live_follow_team_b").assertTextEquals("Alice", "Serve", "4", "2", "AD")
                    onNodeWithTag("live_age_m1").assertTextContains("Score from 12 s ago")
                    text("View only", "live_follow")
                    // Spielt mit: weder Zuschauen beenden noch irgendeine Zähl-Aktion.
                    onNodeWithTag("live_follow_stop").assertDoesNotExist()
                    screenshot("live-detail-dunkel-en")

                    // Match endet: letzter Stand bleibt mit Hinweis stehen.
                    live.matches = emptyList()
                    model.loadLive()
                    waitForIdle()
                    onNodeWithTag("live_follow_gone").assertTextContains("no longer live", substring = true)
                    onNodeWithTag("live_follow_team_b").assertTextContains("AD")
                    click("live_follow_back")
                    assertEquals(1, back)
                }
            }
        }

    @Test fun spectatorDetailCanStopWatching() =
        withLocale(Locale.GERMAN) {
            val visitors = LiveVisitors()
            withModel(UiLive(listOf(spectated)), visitors) { model ->
                runDesktopComposeUiTest(width = 400, height = 900) {
                    var back = 0
                    setContent { Dark { LiveFollowScreen(model, "m2", onBack = { back++ }, clock = clock) } }
                    waitForIdle()
                    onNodeWithTag("live_offline_m2").assertExists()
                    screenshot("live-detail-zuschauer-dunkel-de")
                    click("live_follow_stop")
                    waitForIdle()
                    assertEquals(listOf("revoke:m2:me"), visitors.calls)
                    assertEquals(1, back)
                }
            }
        }

    @Test fun homePollsEveryMinuteWithoutLiveMatchesAndEvery15SecondsWithThem() =
        withLocale(Locale.GERMAN) {
            val live = UiLive(emptyList())
            var wall = NOW
            withModel(live) { model ->
                runDesktopComposeUiTest(width = 400, height = 700) {
                    setContent { Dark { HomeLiveSection(model, WallClock { wall }) {} } }
                    waitForIdle()
                    assertEquals(1, live.calls)

                    fun after(ms: Long) {
                        wall += ms
                        mainClock.advanceTimeBy(1_500)
                        waitForIdle()
                    }
                    after(20_000)
                    assertEquals(1, live.calls, "ohne Live-Match kein Abruf nach 20 s")
                    live.matches = listOf(running)
                    after(41_000)
                    assertEquals(2, live.calls, "nach einer Minute")
                    after(16_000)
                    assertEquals(3, live.calls, "mit Live-Match wieder alle 15 s")
                }
            }
        }

    @Test fun homeShowsForeignRunningMatchesAsLiveOnlyWhenThereAreAny() =
        withLocale(Locale.GERMAN) {
            val live = UiLive(emptyList())
            withModel(live) { model ->
                runDesktopComposeUiTest(width = 400, height = 700) {
                    var opened: String? = null
                    setContent {
                        Dark { HomeLiveSection(model, clock) { opened = it } }
                    }
                    waitForIdle()
                    onNodeWithTag("home_live").assertDoesNotExist()
                    live.matches = listOf(running)
                    model.loadLive()
                    waitForIdle()
                    onNodeWithTag("live_m1").assertTextContains("LIVE · ICH SPIELE MIT")
                    screenshot("live-startseite-dunkel-de")
                    click("live_m1")
                    assertEquals("m1", opened)
                }
            }
        }
}
