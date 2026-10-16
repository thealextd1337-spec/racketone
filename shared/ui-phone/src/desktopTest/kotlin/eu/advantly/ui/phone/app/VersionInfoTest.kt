package eu.advantly.ui.phone.app

import java.util.Locale
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class VersionInfoTest {
    @Test
    fun germanDateUsesLocalTimeAndKeepsDirtyRevision() =
        localized(Locale.GERMANY) {
            assertEquals("0.1.0 · 5e3db67-dirty · 16.10. 16:54", versionDetails("0.1.0", "5e3db67-dirty", "2026-10-16T14:54:00Z"))
        }

    @Test
    fun englishDateUsesLocalTimeAcrossMidnight() =
        localized(Locale.US) {
            assertEquals("0.1.0 · 5e3db67 · Oct 17, 01:54", versionDetails("0.1.0", "5e3db67", "2026-10-16T23:54:00Z"))
        }

    @Test
    fun unknownReleaseAndInvalidDatesStayReadable() =
        localized(Locale.GERMANY) {
            assertEquals("0.1.0 · unknown", versionDetails("0.1.0", "unknown", ""))
            assertEquals("0.1.0 · unknown", versionDetails("0.1.0", "unknown", "invalid"))
            assertEquals("0.1.0", versionDetails("0.1.0", "", ""))
        }

    private fun localized(
        locale: Locale,
        test: () -> Unit,
    ) {
        val previousLocale = Locale.getDefault()
        val previousZone = TimeZone.getDefault()
        try {
            Locale.setDefault(locale)
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"))
            test()
        } finally {
            Locale.setDefault(previousLocale)
            TimeZone.setDefault(previousZone)
        }
    }
}
