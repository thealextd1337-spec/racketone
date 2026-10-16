package eu.advantly.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import eu.advantly.wear.BuildConfig
import eu.advantly.wear.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Eigener Listeneintrag: die ScalingLazyColumn kann ihn auch mit Rotary in die Kreismitte scrollen. */
@Composable
internal fun BuildVersionLabel() {
    val version = stringResource(R.string.wear_version, BuildConfig.VERSION_NAME)
    val commit = stringResource(R.string.build_git_commit)
    val date = localBuildDate(stringResource(R.string.build_time_utc))
    Text(
        listOfNotNull("$version · $commit", date.takeIf { it.isNotEmpty() }).joinToString("\n"),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag(BUILD_VERSION_TAG),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

internal fun localBuildDate(
    utc: String,
    locale: Locale = Locale.getDefault(),
    zone: ZoneId = ZoneId.systemDefault(),
): String =
    runCatching {
        DateTimeFormatter
            .ofPattern(if (locale.language == "de") "dd.MM. HH:mm" else "MMM d, HH:mm", locale)
            .format(Instant.parse(utc).atZone(zone))
    }.getOrDefault("")

const val BUILD_VERSION_TAG = "home_build_version"
