package eu.advantly.android.push

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import eu.advantly.presentation.push.FriendsSection
import eu.advantly.presentation.push.PushKind
import eu.advantly.presentation.push.PushMessage
import eu.advantly.presentation.push.PushTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

private const val MATCH = "0190a5f2-1111-7000-8000-000000000001"
private const val ALEX = "0190a5f2-2222-7000-8000-000000000002"

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PushNotificationsTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(NotificationManager::class.java)

    private fun texts(name: String?) = PushKind.entries.map { pushText(context, PushMessage(it, MATCH, ALEX), name) }

    @Test fun textsUseTheLocalAliasOrStayNeutralInBothLanguages() {
        val english = texts("Alex")
        assertEquals("Alex wants to be friends", english[0])
        assertEquals("Alex asks you to keep score", english[PushKind.SCORER_INVITED.ordinal])
        assertTrue(english.all { it.contains("Alex") })
        val neutral = texts(" ")
        assertEquals("You have a new friend request", neutral[0])
        assertTrue(neutral.none { it.contains("%") } && neutral.toSet().size == PushKind.entries.size)
        RuntimeEnvironment.setQualifiers("de")
        assertEquals("Alex bittet dich zu zählen", texts("Alex")[PushKind.SCORER_INVITED.ordinal])
        assertEquals("Alex hat dich in ein Match eingetragen", texts("Alex")[PushKind.PLAYER_ASSIGNED.ordinal])
        assertEquals("Du hast eine neue Freundschaftsanfrage", texts(null)[0])
        assertTrue(texts(null).none { it.contains("Alex") })
    }

    @Test fun oneChannelPerCategoryScoringAsHeadsUp() {
        ensurePushChannels(context)
        val channels = manager.notificationChannels.associateBy { it.id }
        assertEquals(setOf("push_friends", "push_matches", "push_scoring"), channels.keys)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channels.getValue("push_scoring").importance)
        assertEquals("Friends", channels.getValue("push_friends").name)
    }

    @Test fun notificationNeedsPermissionAndDeepLinksToItsTarget() {
        val message = PushMessage(PushKind.SCORER_INVITED, MATCH, ALEX)
        showPush(context, message, "Alex")
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        showPush(context, message, "Alex")
        showPush(context, message, "Alex")
        val notification = shadowOf(manager).allNotifications.single()
        assertEquals("push_scoring", notification.channelId)
        assertEquals("Alex asks you to keep score", shadowOf(notification).contentTitle)
        val intent = shadowOf(notification.contentIntent).savedIntent
        assertEquals(PushTarget(FriendsSection.LIVE, MATCH), intent.pushTarget())
    }

    @Test fun deliveryIsDroppedWhenSwitchedOff() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        deliverPush(context, PushMessage(PushKind.FRIEND_REQUEST))
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        AndroidPushPreferences(context).enabled = true
        deliverPush(context, PushMessage(PushKind.FRIEND_REQUEST))
        assertEquals("You have a new friend request", shadowOf(shadowOf(manager).allNotifications.single()).contentTitle)
        context
            .getSharedPreferences(AndroidPushPreferences.FILE, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test fun foreignIntentExtrasAreValidated() {
        assertNull(Intent().pushTarget())
        assertNull(Intent().putExtra(EXTRA_PUSH_SECTION, "SETTINGS").pushTarget())
        assertEquals(
            PushTarget(FriendsSection.REQUESTS),
            Intent().putExtra(EXTRA_PUSH_SECTION, "REQUESTS").putExtra(EXTRA_PUSH_MATCH, "../x").pushTarget(),
        )
    }
}
