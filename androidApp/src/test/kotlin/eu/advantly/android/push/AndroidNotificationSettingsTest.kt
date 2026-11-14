package eu.advantly.android.push

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import eu.advantly.cloud.sync.PushTokenPort
import eu.advantly.presentation.push.HINT_HIDDEN_MS
import eu.advantly.presentation.push.PushPreferences
import eu.advantly.presentation.push.PushRegistration
import eu.advantly.presentation.push.PushTokenSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Erklärseite und Schalter (Plan W12, Paket 2, Entscheidungen 2–4). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AndroidNotificationSettingsTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val stored = context.getSharedPreferences(AndroidPushPreferences.FILE, Context.MODE_PRIVATE)
    private val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    private val push =
        PushRegistration(
            object : PushPreferences {
                override var enabled = false
                override var hintDone = false
                override var uploaded: String? = null
            },
            object : PushTokenSource {
                override suspend fun token() = "t"

                override suspend fun delete() {}
            },
            object : PushTokenPort {
                override suspend fun setPushToken(
                    profileId: String,
                    provider: String,
                    token: String?,
                ) {}
            },
        ) { null }
    private var requests = 0

    private fun settings() = AndroidNotificationSettings(activity, push, CoroutineScope(Dispatchers.Unconfined)) { requests++ }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @After fun clear() {
        stored.edit().clear().commit()
    }

    @Test fun firstMomentPromptsAndADenialStaysOutOfTheSettings() {
        val settings = settings()
        settings.onMoment()
        assertTrue(settings.prompt.value)
        settings.accept()
        assertFalse(settings.prompt.value)
        assertEquals(1, requests)
        // Ergebnis an einer neu erzeugten Instanz (Drehen): die Herkunft kommt aus den Einstellungen.
        val recreated = settings()
        recreated.permissionResult(false)
        assertNull(shadowOf(activity).nextStartedActivity)
        recreated.onMoment()
        assertFalse(recreated.prompt.value)
        assertFalse(push.enabled.value)
        // Über den Schalter führt eine dauerhafte Ablehnung weiterhin in die Einstellungen.
        recreated.setEnabled(true)
        recreated.permissionResult(false)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, shadowOf(activity).nextStartedActivity?.action)
    }

    @Test fun grantedFromThePromptTurnsOnAndNeverPromptsAgain() {
        val settings = settings()
        settings.onMoment()
        settings.accept()
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        settings.permissionResult(true)
        idle()
        assertTrue(push.enabled.value)
        assertTrue(settings.enabled.value)
        assertFalse(settings.hint.value)
        settings.onMoment()
        assertFalse(settings.prompt.value)
    }

    /** Entscheidung 2: Wer in 0.7.x schon ein- oder ausgeschaltet hat (`hint_done`), sieht nur noch den Hinweis. */
    @Test fun existingUsersWhoDecidedOnlySeeTheHint() {
        stored.edit().putBoolean("hint_done", true).commit()
        val settings = settings()
        settings.onMoment()
        assertFalse(settings.prompt.value)
        assertTrue(settings.hint.value)
    }

    @Test fun laterReturnsAfterFourteenDaysOnly() {
        val settings = settings()
        settings.onMoment()
        settings.later()
        settings.onMoment()
        assertFalse(settings.prompt.value)
        stored.edit().putLong("prompt_later_at", System.currentTimeMillis() - 15L * 24 * 60 * 60 * 1000).commit()
        settings.onMoment()
        assertTrue(settings.prompt.value)
    }

    @Test fun revokedSystemPermissionShowsTheSwitchOffAndTheHint() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val settings = settings()
        settings.setEnabled(true)
        idle()
        assertTrue(settings.enabled.value)
        shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        settings.refresh()
        idle()
        assertTrue(push.enabled.value)
        assertFalse(settings.enabled.value)
        assertTrue(settings.hint.value)
    }

    /** Entscheidung 4: „Nicht jetzt“ blendet den Hinweis 30 Tage aus. */
    @Test fun dismissedHintReturnsAfterThirtyDays() {
        val settings = settings()
        settings.dismissHint()
        idle()
        assertFalse(settings.hint.value)
        stored.edit().putLong("hint_hidden_at", System.currentTimeMillis() - HINT_HIDDEN_MS).commit()
        settings.refresh()
        idle()
        assertTrue(settings.hint.value)
    }

    /** Bis Android 12 keine Laufzeit-Berechtigung: „Einschalten“ schaltet direkt ein. */
    @Config(sdk = [32])
    @Test
    fun acceptTurnsOnDirectlyBeforeAndroid13() {
        val settings = settings()
        settings.onMoment()
        settings.accept()
        idle()
        assertEquals(0, requests)
        assertTrue(push.enabled.value)
        assertTrue(settings.enabled.value)
        assertNull(shadowOf(activity).nextStartedActivity)
    }
}
