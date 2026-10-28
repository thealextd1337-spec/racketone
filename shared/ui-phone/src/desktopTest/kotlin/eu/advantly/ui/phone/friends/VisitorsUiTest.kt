package eu.advantly.ui.phone.friends

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
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
import eu.advantly.cloud.sync.MatchVisitor
import eu.advantly.cloud.sync.PulledFriendship
import eu.advantly.cloud.sync.VisitorTeamMember
import eu.advantly.cloud.sync.VisitorView
import eu.advantly.cloud.sync.VisitorsPort
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.presentation.friends.FriendEntry
import eu.advantly.presentation.friends.FriendsViewModel
import eu.advantly.presentation.friends.PendingVisitorStore
import eu.advantly.presentation.friends.VisitorQueue
import eu.advantly.presentation.match.InMemoryMatchStore
import eu.advantly.presentation.wizard.MatchWizard
import eu.advantly.presentation.wizard.PlayerPlace
import eu.advantly.presentation.wizard.WizardStep
import eu.advantly.ui.phone.auth.FakePhoneAuth
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.wizard.WizardScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

private object NoRequests : FriendsPort {
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

private class UiVisitors : VisitorsPort {
    val calls = mutableListOf<String>()
    val visitors = mutableListOf(MatchVisitor("carol", "Carol", "bob", canRevoke = false))
    var watching = emptyList<VisitorView>()

    override suspend fun addVisitor(
        profileId: String,
        matchId: String,
        visitorId: String,
    ) {
        calls += "add:$matchId:$visitorId"
        visitors += MatchVisitor(visitorId, visitorId.replaceFirstChar { it.uppercase() }, profileId, canRevoke = true)
    }

    override suspend fun revokeVisitor(
        profileId: String,
        matchId: String,
        visitorId: String,
    ) {
        calls += "revoke:$matchId:$visitorId"
        visitors.removeAll { it.profileId == visitorId }
        watching = watching.filter { it.matchId != matchId }
    }

    override suspend fun matchVisitors(
        profileId: String,
        matchId: String,
    ) = visitors.toList()

    override suspend fun myVisitorMatches(profileId: String) = watching
}

private class UiStore : PendingVisitorStore {
    var pending = emptyMap<String, Set<String>>()
    var watched = emptySet<String>()

    override fun pending(profileId: String) = pending

    override fun setPending(
        profileId: String,
        pending: Map<String, Set<String>>,
    ) {
        this.pending = pending
    }

    override fun watched(profileId: String) = watched

    override fun setWatched(
        profileId: String,
        matchIds: Set<String>,
    ) {
        watched = matchIds
    }
}

@OptIn(ExperimentalTestApi::class)
class VisitorsUiTest {
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

    // Nicht jeder Knopf liegt in einer scrollbaren Fläche (Live-Knopf, Dialog).
    private fun ComposeUiTest.click(tag: String) =
        onNodeWithTag(tag)
            .apply { runCatching { performScrollTo() } }
            .performClick()

    private fun ComposeUiTest.text(
        text: String,
        tag: String,
    ) = onNode(hasText(text, substring = true) and hasAnyAncestor(hasTestTag(tag))).assertExists()

    private val friendships =
        listOf(
            PulledFriendship("vera", "Vera", "accepted", false, 1),
            PulledFriendship("carol", "Carol", "accepted", false, 1),
            PulledFriendship("dora", "Dora", "accepted", false, 1),
        )

    private fun model(
        scope: CoroutineScope,
        port: UiVisitors,
        onServer: Boolean = true,
    ): FriendsViewModel {
        val auth = FakePhoneAuth().apply { session.value = CloudSession("alice", true, consentStatus = ConsentStatus.CURRENT) }
        val queue = VisitorQueue(UiStore(), port, { _, _ -> onServer }) {}
        return FriendsViewModel(auth, NoRequests, { friendships }, null, scope, visitorQueue = queue)
    }

    @Test fun wizardLastStepOffersFriendsAsSpectatorsExceptSeatedOnes() =
        withLocale(Locale.GERMAN) {
            runDesktopComposeUiTest(width = 360, height = 1600) {
                val wizard = MatchWizard(InMemoryMatchStore(selfName = "Alex")).apply { selectSport(Sport.TENNIS) }
                while (wizard.state.value.step != WizardStep.PLAYERS) wizard.next()
                wizard.assignFriend(PlayerPlace(Side.B, 0), "bob-profile", "Bob")
                while (wizard.state.value.step != WizardStep.SCORER) check(wizard.next())
                val friends = listOf(FriendEntry("bob-profile", "Bob"), FriendEntry("vera", "Vera"), FriendEntry("ghost", null))
                var chosen by mutableStateOf(emptySet<String>())
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                        AdvantlyTheme(darkTheme = true) {
                            WizardScreen(wizard, {}, {}, friends = friends, visitors = chosen, onVisitorsChange = { chosen = it })
                        }
                    }
                }
                text("ZUSCHAUER", "wizard_visitors")
                onNodeWithTag("wizard_visitor_bob-profile").assertDoesNotExist()
                onNodeWithTag("wizard_visitor_ghost").assertDoesNotExist()
                click("wizard_visitor_vera")
                assertEquals(setOf("vera"), chosen)
                click("wizard_visitor_vera")
                assertEquals(emptySet(), chosen)
            }
        }

    @Test fun wizardStopsAtTenSpectators() =
        runDesktopComposeUiTest(width = 360, height = 1600) {
            val friends = (1..11).map { FriendEntry("f$it", "Freund $it") }
            var chosen by mutableStateOf((1..10).mapTo(mutableSetOf()) { "f$it" } as Set<String>)
            setContent {
                AdvantlyTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        WizardVisitors(friends, chosen) {
                            chosen =
                                it
                        }
                    }
                }
            }
            onNodeWithTag("wizard_visitor_limit").assertExists()
            onNodeWithTag("wizard_visitor_f11").performScrollTo().performClick()
            assertEquals(10, chosen.size, "kein elfter Zuschauer")
            onNodeWithTag("wizard_visitor_f1").performScrollTo().performClick()
            onNodeWithTag("wizard_visitor_f11").performScrollTo().performClick()
            assertEquals(setOf("f11") + (2..10).map { "f$it" }, chosen)
        }

    @Test fun wizardWithoutFriendsHasNoSpectatorSection() =
        runDesktopComposeUiTest(width = 360, height = 1200) {
            val wizard = MatchWizard(InMemoryMatchStore(selfName = "Alex")).apply { selectSport(Sport.TENNIS) }
            while (wizard.state.value.step != WizardStep.PLAYERS) wizard.next()
            wizard.addGuestAt("Gast", PlayerPlace(Side.B, 0))
            while (wizard.state.value.step != WizardStep.SCORER) check(wizard.next())
            setContent { AdvantlyTheme { WizardScreen(wizard, {}, {}) } }
            onNodeWithTag("scorer_THIS_PHONE").assertExists()
            onNodeWithTag("wizard_visitors").assertDoesNotExist()
        }

    @Test fun matchDetailListsSpectatorsAndAddsAndRemovesInEnglish() =
        withLocale(Locale.ENGLISH) {
            runDesktopComposeUiTest(width = 360, height = 1600) {
                val scope = CoroutineScope(Dispatchers.Unconfined + Job())
                val port = UiVisitors()
                val model = model(scope, port)
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                        AdvantlyTheme(darkTheme = false) {
                            Column(Modifier.verticalScroll(rememberScrollState())) { MatchVisitorsSection(model, "m1") }
                        }
                    }
                }
                try {
                    text("Carol", "visitor_carol")
                    // Von einem Mitspieler benannt: hier nicht entfernbar.
                    onNodeWithTag("visitor_remove_carol").assertDoesNotExist()
                    click("visitors_add")
                    onNodeWithTag("visitor_pick_carol").assertDoesNotExist()
                    click("visitor_pick_vera")
                    click("visitors_add_confirm")
                    waitForIdle()
                    text("Vera", "visitor_vera")
                    click("visitor_remove_vera")
                    waitForIdle()
                    onNodeWithTag("visitor_vera").assertDoesNotExist()
                    assertEquals(listOf("add:m1:vera", "revoke:m1:vera"), port.calls)
                } finally {
                    model.close()
                    scope.cancel()
                }
            }
        }

    @Test fun notYetUploadedMatchShowsQueuedSpectatorsInGerman() =
        withLocale(Locale.GERMAN) {
            runDesktopComposeUiTest(width = 360, height = 1200) {
                val scope = CoroutineScope(Dispatchers.Unconfined + Job())
                val port = UiVisitors()
                val model = model(scope, port, onServer = false)
                setContent { AdvantlyTheme { LiveVisitorsButton(model, "m2") } }
                try {
                    click("live_visitors")
                    onNodeWithTag("visitors_empty").assertTextContains("Noch keine Zuschauer.")
                    click("visitors_add")
                    click("visitor_pick_dora")
                    click("visitors_add_confirm")
                    waitForIdle()
                    text("wartet auf Upload", "visitor_dora")
                    assertEquals(emptyList(), port.calls)
                    click("live_visitors_close")
                    onNodeWithTag("live_visitors_dialog").assertDoesNotExist()
                } finally {
                    model.close()
                    scope.cancel()
                }
            }
        }

    @Test fun watchingSegmentShowsLiveScoreAndStopsWatching() =
        withLocale(Locale.GERMAN) {
            runDesktopComposeUiTest(width = 360, height = 1600) {
                val scope = CoroutineScope(Dispatchers.Unconfined + Job())
                val port =
                    UiVisitors().apply {
                        watching =
                            listOf(
                                VisitorView(
                                    "m9",
                                    "tennis",
                                    "singles",
                                    "in_progress",
                                    startedAt = "2026-10-28T10:00:00Z",
                                    channel = "match-live:m9:1",
                                    teams = listOf(VisitorTeamMember("A", 1, "Bob"), VisitorTeamMember("B", 1, "Alice")),
                                    live = Json.parseToJsonElement("""{"sets":[[6,4]],"games":[2,1],"points":["30","15"]}""") as JsonObject,
                                ),
                            )
                    }
                val model = model(scope, port)
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                        AdvantlyTheme(darkTheme = false) { FriendsScreen(model, onOpenAccount = {}, onShareText = null) }
                    }
                }
                try {
                    click("friends_segment_watching")
                    waitForIdle()
                    text("Tennis · Läuft · 2026-10-28", "watching_m9")
                    text("Bob gegen Alice", "watching_m9")
                    onNodeWithTag("watching_sets_m9").assertTextContains("6:4")
                    onNodeWithTag("watching_live_m9").assertTextContains("Spiele 2:1 · Aktuell: 30 – 15")
                    click("watching_stop_m9")
                    waitForIdle()
                    onNodeWithTag("watching_m9").assertDoesNotExist()
                    onNodeWithTag("watching_empty").assertExists()
                    assertEquals(listOf("revoke:m9:alice"), port.calls)
                } finally {
                    model.close()
                    scope.cancel()
                }
            }
        }
}
