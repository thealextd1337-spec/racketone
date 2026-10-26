package eu.advantly.data

import android.database.sqlite.SQLiteAccessPermException
import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteFullException
import android.database.sqlite.SQLiteReadOnlyDatabaseException
import android.database.sqlite.SQLiteTableLockedException
import android.system.ErrnoException
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.DirectoryIteratorException

/** SQL-/Bindungs-/Constraintfehler sind Programmier- oder Domänenfehler und werden nicht als Speicherfehler maskiert. */
object AndroidStorageFailures {
    fun isExpected(error: Exception): Boolean =
        when (error) {
            is IOException, is ErrnoException,
            is SQLiteDiskIOException, is SQLiteDatabaseCorruptException, is SQLiteFullException,
            is SQLiteDatabaseLockedException, is SQLiteTableLockedException, is SQLiteCantOpenDatabaseException,
            is SQLiteReadOnlyDatabaseException, is SQLiteAccessPermException,
            -> true

            is DirectoryIteratorException, is UncheckedIOException -> error.cause is IOException

            else -> false
        }
}
