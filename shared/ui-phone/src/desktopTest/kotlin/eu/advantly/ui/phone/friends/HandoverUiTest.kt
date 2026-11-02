package eu.advantly.ui.phone.friends

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import eu.advantly.cloud.sync.BlockedProfile
import eu.advantly.cloud.sync.CloudSession
import eu.advantly.cloud.sync.ConsentStatus
import eu.advantly.cloud.sync.FoundProfile
import eu.advantly.cloud.sync.FriendRequestResult
import eu.advantly.cloud.sync.FriendsPort
import eu.advantly.cloud.sync.LiveMatch
import eu.advantly.cloud.sync.LiveMatchesPort
import eu.advantly.cloud.sync.PulledMatch
import eu.advantly.cloud.sync.PulledRole
import eu.advantly.cloud.sync.ScorerRolesPort
import eu.advantly.cloud.sync.VisitorTeamMember
import eu.advantly.presentation.friends.FriendEntry
import eu.advantly.presentation.friends.FriendsViewModel
import eu.advantly.presentation.friends.Handover
import eu.advantly.presentation.friends.PendingScorerStore
import eu.advantly.presentation.friends.ScorerDevices
import eu.advantly.presentation.friends.ScorerHandovers
import eu.advantly.presentation.friends.ScorerInvite
import eu.advantly.presentation.friends.SharedMatchesLocal
import eu.advantly.presentation.match.WallClock
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

private object Nobody : FriendsPort {
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

private class Roles : ScorerRolesPort {
    val calls = mutableListOf<String>()

    override suspend fun inviteScorer(
        profileId: String,
        matchId: String,
        scorerId: String,
    ) {
        calls += "invite $matchId $scorerId"
    }

    override suspend fun respondScorerInvitation(
        profileId: String,
        matchId: String,
        accept: Boolean,
        deviceId: String?,
    ) {
        calls += "respond $matchId $accept $deviceId"
    }

    override suspend fun revokeScorer(
        profileId: String,
        matchId: String,
        scorerId: String,
    ) {
        calls += "revoke $matchId $scorerId"
    }

    override suspend fun registerWatch(
        profileId: String,
        watchId: String,
        phoneId: String,
    ) {
        calls += "register $watchId"
    }
}

private class Devices : ScorerDevices {
    override val phoneId = "jonas-phone"
    val scoring = mutableSetOf<String>()
    val ready = mutableSetOf<String>()
    val uploads = mutableMapOf<String, Boolean>()

    override fun watchId() = "jonas-watch"

    override fun scoring(matchId: String) = matchId in scoring

    override fun handOver(
        matchId: String,
        deviceId: String,
    ): Handover? = null

    override fun uploaded(eventId: String) = uploads[eventId]

    override fun handBack(
        matchId: String,
        handover: Handover,
    ) = scoring.add(matchId)

    override fun canTakeOver(matchId: String) = matchId in ready && matchId !in scoring

    override fun takeOver(matchId: String) = canTakeOver(matchId).also { if (it) scoring += matchId }
}

private class Store : PendingScorerStore {
    var invites = emptyMap<String, ScorerInvite>()

    override fun scorerInvites(profileId: String) = invites

    override fun setScorerInvites(
        profileId: String,
        invites: Map<String, ScorerInvite>,
    ) {
        this.invites = invites
    }
}

private class Pulled(
    var roles: Map<String, PulledRole>,
) : SharedMatchesLocal {
    override fun feed(profileId: String) = emptyList<PulledMatch>()

    override fun match(
        profileId: String,
        matchId: String,
    ) = roles[matchId]?.let {
        PulledMatch(
            matchId,
            "member",
            "alex",
            "tennis",
            "singles",
            JsonObject(emptyMap()),
            JsonObject(emptyMap()),
            status = "in_progress",
            serverVersion = 1,
            roles = listOf(it),
        )
    }

    override fun linkNotes(
        profileId: String,
        matchId: String,
    ) = emptyMap<String, String>()
}

private class Live : LiveMatchesPort {
    override suspend fun liveMatches(profileId: String) =
        listOf(
            LiveMatch(
                "m1",
                "player",
                "tennis",
                "singles",
                listOf(VisitorTeamMember("A", 1, "Alex"), VisitorTeamMember("B", 1, "Jonas")),
                Json.parseToJsonElement("""{"sets":[[6,4]],"games":[3,2],"points":["30","15"],"serving":"A"}""") as JsonObject,
                scorerConnected = true,
                updatedAt = "2026-11-02T11:00:05Z",
            ),
        )
}

@Composable
private fun Dark(content: @Composable () -> Unit) =
    AdvantlyTheme(darkTheme = true) { Box(Modifier.fillMaxSize().background(AdvantlyTheme.colors.bg)) { content() } }

/** Zählen weitergeben (plan-live-mitspieler §3.4): Abgabe am zählenden Handy, Annahme und Notfall beim Empfänger. */
@OptIn(ExperimentalTestApi::class)
class HandoverUiTest {
    private val clock = WallClock { 1_793_617_217_000L }
    private val roles = Roles()
    private val devices = Devices()
    private val store = Store()
    private val pulled = Pulled(emptyMap())
    private val onServer = mutableSetOf<String>()
    private var handovers: ScorerHandovers? = null

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

    /** Mit offenem Dialog gibt es zwei Wurzeln: dann der Dialog. */
    private fun ComposeUiTest.screenshot(name: String) {
        val directory = File(System.getenv("ADVANTLY_SCREENSHOT_DIR") ?: "build/reports/screenshots").apply { mkdirs() }
        val image = onAllNodes(isRoot()).onLast().captureToImage()
        ImageIO.write(image.toAwtImage(), "png", File(directory, "$name.png"))
    }

    private fun ComposeUiTest.text(
        text: String,
        tag: String,
    ) = onNode(hasText(text, substring = true) and hasAnyAncestor(hasTestTag(tag))).assertExists()

    private fun withModel(
        profile: String,
        test: (FriendsViewModel) -> Unit,
    ) {
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        val auth = FakePhoneAuth().apply { session.value = CloudSession(profile, true, consentStatus = ConsentStatus.CURRENT) }
        val handovers = ScorerHandovers(store, roles, { _, m -> m in onServer }, { p, m -> pulled.match(p, m) }, devices) {}
        this.handovers = handovers
        val model = FriendsViewModel(auth, Nobody, { emptyList() }, null, scope, local = pulled, liveMatches = Live(), scorers = handovers)
        try {
            test(model)
        } finally {
            model.close()
            scope.cancel()
        }
    }

    @Test fun countingPhoneHandsOverToALinkedPlayerInGerman() =
        withLocale(Locale.GERMAN) {
            withModel("alex") { model ->
                runDesktopComposeUiTest(width = 400, height = 700) {
                    setContent { Dark { Column { LiveHandoverButton(model, "m1", listOf(FriendEntry("jonas", "Jonas"))) } } }
                    onNodeWithTag("live_handover").assertTextContains("Zählen weitergeben").performClick()
                    waitForIdle()
                    onNodeWithTag("live_handover_to_jonas").assertTextContains("An Jonas")
                    screenshot("handover-abgeben-auswahl-de")
                    onNodeWithTag("live_handover_to_jonas").performClick()
                    waitForIdle()
                    // Match noch nicht hochgeladen: vorgemerkt, nichts gesendet
                    text("sobald das Match hochgeladen ist", "live_handover_dialog")
                    assertEquals(emptyList<String>(), roles.calls)
                    onNodeWithTag("live_handover_cancel").performClick()
                    waitForIdle()
                    onServer += "m1"
                    onNodeWithTag("live_handover_to_jonas").performClick()
                    waitForIdle()
                    text("Jonas ist eingeladen", "live_handover_dialog")
                    screenshot("handover-abgeben-eingeladen-de")
                    assertEquals(listOf("invite m1 jonas"), roles.calls)
                }
            }
        }

    @Test fun rejectedHandoverShowsAHintUntilDismissedInGerman() =
        withLocale(Locale.GERMAN) {
            onServer += "m1"
            store.invites = mapOf("m1" to ScorerInvite("jonas", sent = true, handover = Handover("sc-m1", "alex-phone", "jonas-phone")))
            devices.uploads["sc-m1"] = false
            withModel("alex") { model ->
                checkNotNull(handovers).reconcile("alex")
                model.open()
                runDesktopComposeUiTest(width = 400, height = 400) {
                    setContent { Dark { Column { LiveHandoverButton(model, "m1", listOf(FriendEntry("jonas", "Jonas"))) } } }
                    onNodeWithTag("live_handover_failed").assertTextContains("Übergabe an Jonas fehlgeschlagen – du zählst weiter.")
                    onNodeWithTag("live_handover").assertDoesNotExist()
                    screenshot("handover-fehlgeschlagen-de")
                    onNodeWithTag("live_handover_failed_ok").performClick()
                    waitForIdle()
                    onNodeWithTag("live_handover_failed").assertDoesNotExist()
                    onNodeWithTag("live_handover").assertExists()
                    assertEquals(emptyList<String>(), roles.calls)
                }
            }
        }

    @Test fun invitedPlayerAcceptsOnTheWatchAndOpensScoringInGerman() =
        withLocale(Locale.GERMAN) {
            pulled.roles = mapOf("m1" to PulledRole("jonas", "Jonas", "scorer", "invited"))
            withModel("jonas") { model ->
                runDesktopComposeUiTest(width = 400, height = 1000) {
                    var scored: String? = null
                    setContent { Dark { LiveFollowScreen(model, "m1", onBack = {}, clock = clock, onScore = { scored = it }) } }
                    waitForIdle()
                    text("Du bist zum Zählen eingeladen", "scorer_invitation")
                    onNodeWithTag("scorer_accept_phone").assertTextContains("Auf diesem Handy zählen")
                    onNodeWithTag("scorer_decline").assertTextContains("Ablehnen")
                    screenshot("handover-einladung-de")
                    onNodeWithTag("scorer_accept_watch").assertTextContains("Auf meiner Uhr zählen").performClick()
                    waitForIdle()
                    assertEquals(listOf("register jonas-watch", "respond m1 true jonas-watch"), roles.calls)
                    model.loadLive()
                    waitForIdle()
                    onNodeWithTag("scorer_waiting").assertTextContains("warte auf die Übergabe", substring = true)
                    screenshot("handover-warten-de")

                    // Wechsel gezogen: eines meiner Geräte zählt
                    devices.scoring += "m1"
                    model.loadLive()
                    waitForIdle()
                    onNodeWithTag("scorer_open").assertTextContains("Zählen öffnen").performClick()
                    assertEquals("m1", scored)
                }
            }
        }

    @Test fun emergencyTakeOverNeedsTheCompleteStateAndAWarningInEnglish() =
        withLocale(Locale.ENGLISH) {
            pulled.roles = mapOf("m1" to PulledRole("jonas", "Jonas", "scorer", "accepted", "jonas-phone"))
            withModel("jonas") { model ->
                runDesktopComposeUiTest(width = 400, height = 1000) {
                    setContent { Dark { LiveFollowScreen(model, "m1", onBack = {}, clock = clock) } }
                    waitForIdle()
                    onNodeWithTag("scorer_takeover").assertIsNotEnabled()
                    devices.ready += "m1"
                    model.loadLive()
                    waitForIdle()
                    onNodeWithTag("scorer_takeover").assertIsEnabled().assertTextContains("Take over scoring (emergency)").performClick()
                    waitForIdle()
                    text("won’t be counted", "scorer_takeover_dialog")
                    screenshot("handover-notfall-warnung-en")
                    onNodeWithTag("scorer_takeover_confirm").performClick()
                    waitForIdle()
                    assertEquals(setOf("m1"), devices.scoring)
                }
            }
        }

    @Test fun wizardOffersTheTeammateAsScorer() =
        withLocale(Locale.ENGLISH) {
            runDesktopComposeUiTest(width = 400, height = 400) {
                var chosen: String? = "x"
                setContent {
                    var selected by remember { mutableStateOf<String?>(null) }
                    Dark {
                        WizardHandover(listOf(FriendEntry("jonas", "Jonas")), selected) {
                            selected = it
                            chosen = it
                        }
                    }
                }
                text("They get an invitation", "wizard_handover")
                onNodeWithTag("wizard_handover_jonas").performClick()
                waitForIdle()
                assertEquals("jonas", chosen)
                screenshot("handover-assistent-en")
                onNodeWithTag("wizard_handover_jonas").performClick()
                assertEquals(null, chosen)
            }
        }
}
