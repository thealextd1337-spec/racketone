package eu.advantly.ui.phone.app

import eu.advantly.ui.phone.platform.formatBuildDate
import kotlin.time.Instant

/** Rohwerte bleiben unverändert (auch unknown/-dirty); Release hat keine Zeit. */
internal fun versionDetails(
    versionName: String,
    commit: String,
    timeUtc: String,
): String =
    listOfNotNull(
        versionName,
        commit.takeIf { it.isNotBlank() },
        runCatching { formatBuildDate(Instant.parse(timeUtc).toEpochMilliseconds()) }.getOrNull(),
    ).joinToString(" · ")
