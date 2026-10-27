package eu.advantly.android

import android.app.Application
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [UnsupportedDirectorySyncLinux::class])
class StartLoginPrefsTest {
    @Test
    fun decisionSurvivesAppRestarts() {
        val context = RuntimeEnvironment.getApplication()
        val first = StartLoginPrefs(context)
        assertFalse(first.decided.value)
        first.decide()
        assertTrue(first.decided.value)
        assertTrue(StartLoginPrefs(context).decided.value)
    }
}
