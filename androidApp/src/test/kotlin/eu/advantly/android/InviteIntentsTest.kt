package eu.advantly.android

import android.app.Application
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private val TOKEN = "c0ffee".repeat(10) + "c0ff"

/** plan-einladungen §6 „Android“: VIEW-Intent → Einladungsziel (auch onNewIntent), fremde URLs ignoriert. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class InviteIntentsTest {
    private fun view(url: String) = Intent(Intent.ACTION_VIEW, Uri.parse(url))

    @Test fun onlyStrictInvitationLinksBecomeTargets() {
        assertEquals(TOKEN, view("https://advantly.eu/i/#$TOKEN").inviteToken())
        assertEquals(TOKEN, view("https://advantly.eu/i/index.html#${TOKEN.uppercase()}").inviteToken())
        for (url in listOf(
            "https://evil.example/i/#$TOKEN",
            "http://advantly.eu/i/#$TOKEN",
            "https://www.advantly.eu/i/#$TOKEN",
            "https://advantly.eu/i/?t=$TOKEN",
            "https://advantly.eu/i/#",
            "https://advantly.eu/x/#$TOKEN",
            "advantly://i/$TOKEN",
        )) {
            assertNull(url, view(url).inviteToken())
        }
        assertNull(Intent(Intent.ACTION_SEND).setData(Uri.parse("https://advantly.eu/i/#$TOKEN")).inviteToken())
        assertNull(Intent().inviteToken())
    }

    @Test fun startIntentWaitsForTheAccountAndIsConsumedOnce() {
        val inbox = InviteInbox()
        val delivered = mutableListOf<String>()
        val start = view("https://advantly.eu/i/#$TOKEN")
        inbox.take(start)
        assertNull("URL entfernt: Neuerzeugung löst nicht erneut aus", start.data)
        assertTrue(delivered.isEmpty())
        inbox.deliver = { delivered += it }
        assertEquals(listOf(TOKEN), delivered)
        inbox.take(start)
        inbox.deliver = { delivered += it }
        assertEquals(listOf(TOKEN), delivered)
    }

    @Test fun onNewIntentDeliversImmediatelyRestoredAndHistoryLaunchesAreIgnored() {
        val inbox = InviteInbox()
        val delivered = mutableListOf<String>()
        inbox.deliver = { delivered += it }
        inbox.take(view("https://advantly.eu/i/#$TOKEN"))
        assertEquals(listOf(TOKEN), delivered)
        // Drehen/Prozessende: der Link liegt schon im SavedStateHandle.
        val restored = view("https://advantly.eu/i/#$TOKEN")
        inbox.take(restored, restored = true)
        assertNotNull(restored.data)
        inbox.take(view("https://advantly.eu/i/#$TOKEN").addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY))
        val foreign = view("https://evil.example/i/#$TOKEN")
        inbox.take(foreign)
        assertNotNull("fremde URL bleibt unangetastet", foreign.data)
        assertEquals(1, delivered.size)
    }
}
