package eu.advantly.ui.phone.designsystem

import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class AdvantlyThemeTest {
    @Test
    fun textWithoutColorUsesThemeTextColor() {
        for (dark in listOf(true, false)) {
            var content = Color.Unspecified
            runComposeUiTest {
                setContent { AdvantlyTheme(darkTheme = dark) { content = LocalContentColor.current } }
            }
            assertEquals(if (dark) DarkAdvantlyColors.text else LightAdvantlyColors.text, content)
        }
    }
}
