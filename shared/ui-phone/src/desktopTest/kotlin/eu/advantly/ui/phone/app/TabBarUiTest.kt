package eu.advantly.ui.phone.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class TabBarUiTest {
    @Test
    fun visibleLabelsAreAnnouncedOnlyThroughText() = verifyLabels(1f, showLabel = true)

    @Test
    fun hiddenLabelsAreAnnouncedThroughContentDescription() = verifyLabels(1.5f, showLabel = false)

    private fun verifyLabels(
        fontScale: Float,
        showLabel: Boolean,
    ) = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                AdvantlyTheme {
                    TabBar(selected = AppTab.Home, onSelect = {})
                }
            }
        }
        AppTab.entries.forEach { tab ->
            val semantics = onNodeWithTag("tab_${tab.name.lowercase()}").fetchSemanticsNode().config
            if (showLabel) {
                assertNull(semantics.getOrNull(SemanticsProperties.ContentDescription))
                assertEquals(1, semantics[SemanticsProperties.Text].size)
                assertTrue(semantics[SemanticsProperties.Text].single().text.isNotBlank())
            } else {
                assertNull(semantics.getOrNull(SemanticsProperties.Text))
                assertEquals(1, semantics[SemanticsProperties.ContentDescription].size)
                assertTrue(semantics[SemanticsProperties.ContentDescription].single().isNotBlank())
            }
            assertEquals(tab == AppTab.Home, semantics[SemanticsProperties.Selected])
        }
    }
}
