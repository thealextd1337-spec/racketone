package eu.advantly.ui.phone.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import eu.advantly.presentation.match.UpdateRequired
import eu.advantly.ui.phone.designsystem.AdvantlyTheme
import eu.advantly.ui.phone.designsystem.Spacing
import eu.advantly.ui.phone.resources.Res
import eu.advantly.ui.phone.resources.update_required_this_device
import eu.advantly.ui.phone.resources.update_required_watch
import org.jetbrains.compose.resources.stringResource

/** Auch ohne aktives Match sichtbar, beispielsweise im Assistenten. */
@Composable
internal fun UpdateRequiredBanner(
    required: UpdateRequired?,
    modifier: Modifier = Modifier,
) {
    if (required == null) return
    Text(
        stringResource(
            if (required ==
                UpdateRequired.THIS_DEVICE
            ) {
                Res.string.update_required_this_device
            } else {
                Res.string.update_required_watch
            },
        ),
        style = AdvantlyTheme.typography.label,
        color = AdvantlyTheme.colors.onHint,
        modifier =
            modifier
                .fillMaxWidth()
                .testTag("update_required")
                .semantics { liveRegion = LiveRegionMode.Polite }
                .background(AdvantlyTheme.colors.hint)
                .padding(Spacing.m),
    )
}
