package eu.advantly.data

import co.touchlab.sqliter.DatabaseFileContext
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import kotlin.test.Test
import kotlin.test.assertTrue

/** Review M4: Das Datenbankverzeichnis (Datenbank, -wal, -shm, -journal) ist vom iCloud-/Geräte-Backup ausgeschlossen. */
class NativeBackupExclusionTest {
    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun theDatabaseDirectoryIsExcludedFromBackup() {
        val name = "backup-exclusion-test.db"
        val driver = createNativeDriver(name)
        try {
            LocalData(driver).settings.put("probe", "1")
            val directory = NSURL.fileURLWithPath(DatabaseFileContext.databasePath(name, null)).URLByDeletingLastPathComponent!!
            val values = directory.resourceValuesForKeys(listOf(NSURLIsExcludedFromBackupKey), null)
            assertTrue((values?.get(NSURLIsExcludedFromBackupKey) as? NSNumber)?.boolValue == true)
        } finally {
            driver.close()
            DatabaseFileContext.deleteDatabase(name, null)
        }
    }
}
