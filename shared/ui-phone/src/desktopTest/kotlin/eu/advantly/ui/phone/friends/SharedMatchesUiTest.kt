package eu.advantly.ui.phone.friends

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
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
import eu.advantly.cloud.sync.PulledFriendship
import eu.advantly.cloud.sync.PulledMatch
import eu.advantly.cloud.sync.PulledParticipant
import eu.advantly.cloud.sync.SharedMatchesPort
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.presentation.friends.FriendEntry
import eu.advantly.presentation.friends.FriendsViewModel
import eu.advantly.presentation.friends.SharedMatchesLocal
import eu.advantly.presentation.match.InMemoryMatchStore
import eu.advantly.presentation.wizard.MatchWizard
import eu.advantly.ui.phone.auth.FakePhoneAuth
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.wizard.WizardScreen
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
import kotlin.test.assertTrue

private object Friendly : FriendsPort {
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

private class UiShared : SharedMatchesPort {
    val calls = mutableListOf<String>()
    var shareDefault = false

    override suspend fun setMyParticipation(
        matchId: String,
        shareWithFriends: Boolean?,
        hidden: Boolean?,
    ) {
        calls += "participation:$matchId:$shareWithFriends:$hidden"
    }

    override suspend fun rejectMatchParticipation(matchId: String) {
        calls += "reject:$matchId"
    }

    override suspend fun shareMatchesDefault() = shareDefault.also { calls += "default" }

    override suspend fun setShareMatchesDefault(share: Boolean) {
        calls += "default:$share"
        shareDefault = share
    }
}

private fun participant(
    slot: String,
    team: String,
    name: String,
    profileId: String? = null,
    link: String? = null,
) = PulledParticipant(slot, team, 1, profileId = profileId, linkStatus = link, displayName = name, shareWithFriends = false)

private val feedMatch =
    PulledMatch(
        id = "feed-1",
        access = "feed",
        sport = "tennis",
        discipline = "singles",
        format = JsonObject(emptyMap()),
        meta = JsonObject(emptyMap()),
        startedAt = "2026-10-26T09:00:00Z",
        status = "finished",
        winner = "A",
        summary = Json.parseToJsonElement("""{"sets":[[6,4],[7,5]]}""") as JsonObject,
        serverVersion = 1,
        participants = listOf(participant("s1", "A", "Carla mit sehr langem Alias", "carla-id"), participant("s2", "B", "")),
    )

private object UiLocal : SharedMatchesLocal {
    override fun feed(profileId: String) = if (profileId == "alice") listOf(feedMatch) else emptyList()

    override fun match(
        profileId: String,
        matchId: String,
    ) = if (profileId != "alice") {
        null
    } else {
        feedMatch.copy(
            id = matchId,
            access = "member",
            participants =
                when (matchId) {
                    "linked" -> {
                        listOf(
                            participant("c", "A", "Carla", "carla-id", "self"),
                            participant("me", "B", "Alice", "alice", "linked"),
                        )
                    }

                    else -> {
                        listOf(participant("me", "A", "Alice", "alice", "self"), participant("bob-profile", "B", "Bob"))
                    }
                },
        )
    }

    override fun linkNotes(
        profileId: String,
        matchId: String,
    ) = if (profileId == "alice" && matchId == "own") mapOf("bob-profile" to "not_friend") else emptyMap()
}

@OptIn(ExperimentalTestApi::class)
class SharedMatchesUiTest {
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

    private fun ComposeUiTest.click(tag: String) = onNodeWithTag(tag).performScrollTo().performClick()

    private fun ComposeUiTest.screenshot(name: String) {
        val directory = File(System.getenv("ADVANTLY_SCREENSHOT_DIR") ?: "build/reports/screenshots").apply { mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
    }

    private fun model(
        scope: CoroutineScope,
        shared: UiShared,
        friends: List<PulledFriendship> = emptyList(),
    ): FriendsViewModel {
        val auth = FakePhoneAuth().apply { session.value = CloudSession("alice", true, consentStatus = ConsentStatus.CURRENT) }
        return FriendsViewModel(auth, Friendly, { friends }, null, scope, shared, UiLocal)
    }

    @Test fun feedListAndReadOnlyDetailInGermanAtDoubleFontSize() =
        withLocale(Locale.GERMAN) {
            runDesktopComposeUiTest(width = 360, height = 1600) {
                val scope = CoroutineScope(Dispatchers.Unconfined + Job())
                val model = model(scope, UiShared())
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                        AdvantlyTheme(darkTheme = false) { FriendsScreen(model, onOpenAccount = {}, onShareText = null) }
                    }
                }
                try {
                    click("friends_segment_feed")
                    onNodeWithTag("friends_feed_feed-1").assertTextContains("Tennis · Beendet · 2026-10-26", substring = true)
                    onNodeWithTag(
                        "friends_feed_feed-1",
                    ).assertTextContains("Carla mit sehr langem Alias gegen Unbekanntes Profil", substring = true)
                    screenshot("feed-de-200")
                    click("friends_feed_feed-1")
                    onNodeWithTag("friends_feed_winner").assertTextContains("Sieg: Carla mit sehr langem Alias")
                    onNodeWithTag("friends_feed_set_1").assertTextContains("Satz 2: 7:5")
                    onNodeWithTag("friends_feed_team_b").assertTextContains("Seite B: Unbekanntes Profil")
                    screenshot("feed-detail-de-200")
                    click("friends_feed_back")
                    onNodeWithTag("friends_feed_detail").assertDoesNotExist()
                    onNodeWithTag("friends_feed_feed-1").assertExists()
                } finally {
                    model.close()
                    scope.cancel()
                }
            }
        }

    @Test fun shareToggleNotesRejectAndDefaultInEnglishAtDoubleFontSize() =
        withLocale(Locale.ENGLISH) {
            runDesktopComposeUiTest(width = 360, height = 1600) {
                val scope = CoroutineScope(Dispatchers.Unconfined + Job())
                val shared = UiShared()
                val model = model(scope, shared)
                var matchId by mutableStateOf("own")
                var gone = 0
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                        AdvantlyTheme(darkTheme = false) {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                ShareDefaultSection(model)
                                MatchSharingSection(model, matchId, slotName = { if (it == "bob-profile") "Bob" else null }) { gone++ }
                            }
                        }
                    }
                }
                try {
                    onNodeWithTag("sharing_note_bob-profile").assertTextContains("Bob was not linked: you are not (or no longer) friends.")
                    onNodeWithTag("sharing_hide").assertDoesNotExist()
                    onNodeWithTag("sharing_reject").assertDoesNotExist()
                    click("sharing_share")
                    click("sharing_default")
                    screenshot("sharing-en-200")
                    // Von einem Freund eingetragen: „War ich nicht“ erst nach Rückfrage.
                    matchId = "linked"
                    onNodeWithTag("sharing_linked").assertTextContains("A friend entered you", substring = true)
                    click("sharing_reject")
                    onNodeWithTag("sharing_confirm_cancel").performClick()
                    assertEquals(0, gone)
                    click("sharing_reject")
                    onNodeWithTag("sharing_confirm").performClick()
                    waitForIdle()
                    assertEquals(1, gone)
                    assertEquals(
                        listOf("default", "participation:own:true:null", "default:true", "reject:linked"),
                        shared.calls,
                    )
                } finally {
                    model.close()
                    scope.cancel()
                }
            }
        }

    @Test fun wizardOffersFriendsNextToGuestsAndSeatsTheChosenOne() =
        withLocale(Locale.GERMAN) {
            runDesktopComposeUiTest(width = 360, height = 1200) {
                val wizard = MatchWizard(InMemoryMatchStore(selfName = "Alex")).apply { selectSport(Sport.PADEL) }
                val friends = listOf(FriendEntry("bob-profile", "Bob"), FriendEntry("ghost", null))
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                        AdvantlyTheme(darkTheme = true) { WizardScreen(wizard, {}, {}, friends = friends) }
                    }
                }
                waitForIdle()
                click("slot_B_0")
                onNodeWithTag("friend_ghost").assertDoesNotExist()
                click("friend_bob-profile")
                val seated =
                    wizard.state.value.input
                        .team(Side.B)[0]
                assertEquals("bob-profile" to "Bob", seated?.id?.value to seated?.displayName)
                assertTrue(wizard.guests.value.isEmpty())
            }
        }
}
