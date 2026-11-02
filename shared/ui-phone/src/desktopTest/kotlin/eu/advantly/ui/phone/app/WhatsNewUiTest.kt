package eu.advantly.ui.phone.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import eu.advantly.cloud.sync.CloudSession
import eu.advantly.cloud.sync.ConsentDocument
import eu.advantly.cloud.sync.ConsentDocumentType
import eu.advantly.cloud.sync.ConsentSnapshot
import eu.advantly.cloud.sync.ConsentStatus
import eu.advantly.core.scoring.Presets
import eu.advantly.match.session.DeviceStatus
import eu.advantly.presentation.auth.AuthViewModel
import eu.advantly.presentation.match.InMemoryMatchStore
import eu.advantly.presentation.match.InMemoryTelemetrySettings
import eu.advantly.sync.protocol.AppBuild
import eu.advantly.sync.protocol.PeerKind
import eu.advantly.ui.phone.AdvantlyApp
import eu.advantly.ui.phone.auth.FakePhoneAuth
import eu.advantly.ui.phone.setupFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Plan 0.5.1 §6: „Was ist neu?“ nach einem Update. */
@OptIn(ExperimentalTestApi::class)
class WhatsNewUiTest {
    private val build = 11

    private fun store(lastSeen: Int?) = InMemoryMatchStore().also { s -> lastSeen?.let { s.putLocalNote(WHATS_NEW_KEY, "$it") } }

    private fun app(
        store: InMemoryMatchStore,
        port: InMemoryTelemetrySettings = InMemoryTelemetrySettings(),
    ) = AdvantlyAppModel(store, store, store, telemetry = port)

    private fun signedIn(consent: ConsentStatus = ConsentStatus.CURRENT) =
        AuthViewModel(
            FakePhoneAuth().also {
                it.session.value = CloudSession("alice", true)
                if (consent != ConsentStatus.CURRENT) {
                    it.consentSnapshot =
                        ConsentSnapshot(
                            "alice",
                            consent,
                            ConsentDocumentType.entries.map { type ->
                                ConsentDocument(type, "v8", "en", "https://advantly.eu/document", approved = true)
                            },
                        )
                }
            },
            CoroutineScope(Dispatchers.Unconfined),
        )

    /** Release-Checkliste: Für den `advantly.buildNumber` aus gradle.properties gibt es 2–4 Punkte in DE und EN. */
    @Test
    fun theCurrentBuildHasTextsInBothLanguages() {
        val current =
            Properties()
                .apply { File("../../gradle.properties").reader(Charsets.UTF_8).use(::load) }
                .getProperty("advantly.buildNumber")
                .toInt()
        for (qualifier in listOf("values", "values-de")) {
            val nodes =
                DocumentBuilderFactory
                    .newInstance()
                    .newDocumentBuilder()
                    .parse(File("src/commonMain/composeResources/$qualifier/strings.xml"))
                    .getElementsByTagName("string")
            val points =
                (0 until nodes.length)
                    .map {
                        nodes
                            .item(it)
                            .attributes
                            .getNamedItem("name")
                            .nodeValue
                    }.count { it.startsWith("whats_new_${current}_") }
            assertTrue(points in 2..4, "$qualifier: whats_new_${current}_* fehlt oder hat $points Punkte")
        }
        assertTrue(whatsNewPoints(current).size in 2..4)
    }

    @Test
    fun afterAnUpdateTheSheetShowsTheCurrentPointsAndOpenActions() =
        runDesktopComposeUiTest(width = 412, height = 892) {
            // Übersprungene Version (9 → 11): nur die Punkte von 11
            val store = store(lastSeen = 9).apply { localAppBuild = AppBuild("0.5.1", "") }
            store.setDeviceStatuses(listOf(DeviceStatus("w", PeerKind.WATCH, appBuild = AppBuild("0.5.0", ""), reachable = true)))
            val port = InMemoryTelemetrySettings()
            setContent { AdvantlyApp(model = app(store, port), authModel = signedIn(), versionName = "0.5.1", buildNumber = build) }
            onNodeWithText("Advantly 0.5.1", substring = true).assertIsDisplayed()
            onNodeWithText("Spielverlauf", substring = true).assertExists()
            onNodeWithTag("watch_update_w").performScrollTo().assertIsDisplayed()
            onNodeWithTag("health_choice_record").performScrollTo().performClick()
            onNodeWithTag("whats_new_done").performScrollTo().performClick()
            onNodeWithTag("whats_new").assertDoesNotExist()
            runOnIdle {
                assertEquals("11", store.localNote(WHATS_NEW_KEY))
                assertTrue(port.healthConsent.value)
            }
        }

    @Test
    fun notAfterAFreshInstallOrWhenAlreadySeen() =
        runDesktopComposeUiTest(width = 412, height = 892) {
            // Ohne Konto-Host: fehlender Stand = Neuinstallation, wird gemerkt
            val fresh = store(lastSeen = null)
            setContent { AdvantlyApp(model = app(fresh), buildNumber = build) }
            onNodeWithTag("whats_new").assertDoesNotExist()
            runOnIdle { assertEquals("11", fresh.localNote(WHATS_NEW_KEY)) }
        }

    @Test
    fun theSignInFlowCountsAsSeen() =
        runDesktopComposeUiTest(width = 412, height = 892) {
            val fresh = store(lastSeen = null)
            val auth = AuthViewModel(FakePhoneAuth(), CoroutineScope(Dispatchers.Unconfined))
            setContent { AdvantlyApp(model = app(fresh), authModel = auth, buildNumber = build) }
            runOnIdle { assertEquals("11", fresh.localNote(WHATS_NEW_KEY)) }
            onNodeWithTag("whats_new").assertDoesNotExist()
        }

    @Test
    fun aNewPrivacyVersionComesFirst() =
        runDesktopComposeUiTest(width = 412, height = 892) {
            val store = store(lastSeen = 10)
            val auth = signedIn(ConsentStatus.REQUIRED)
            setContent { AdvantlyApp(model = app(store), authModel = auth, buildNumber = build) }
            onNodeWithTag("whats_new").assertDoesNotExist()
            onNodeWithTag("consent_confirm_TERMS").performScrollTo().performClick()
            onNodeWithTag("consent_confirm_PRIVACY").performScrollTo().performClick()
            onNodeWithTag("consent_accept").performScrollTo().performClick()
            onNodeWithTag("whats_new").assertIsDisplayed()
        }

    @Test
    fun notDuringARunningMatch() =
        runDesktopComposeUiTest(width = 412, height = 892) {
            val store = store(lastSeen = 10)
            val model = app(store)
            model.live.start(model.setupFor(Presets.TENNIS_SINGLES_BO3.format()))
            setContent { AdvantlyApp(model = model, buildNumber = build) }
            onNodeWithTag("whats_new").assertDoesNotExist()
            runOnIdle { assertTrue(store.abandonMatch()) }
            onNodeWithTag("whats_new").assertIsDisplayed()
            onNodeWithTag("whats_new_done").performScrollTo().performClick()
            runOnIdle { assertEquals("11", store.localNote(WHATS_NEW_KEY)) }
        }

    @Test
    fun withoutBuildNumberNothingHappens() =
        runDesktopComposeUiTest(width = 412, height = 892) {
            val store = store(lastSeen = null)
            setContent { AdvantlyApp(model = app(store)) }
            onNodeWithTag("whats_new").assertDoesNotExist()
            runOnIdle { assertNull(store.localNote(WHATS_NEW_KEY)) }
        }
}
