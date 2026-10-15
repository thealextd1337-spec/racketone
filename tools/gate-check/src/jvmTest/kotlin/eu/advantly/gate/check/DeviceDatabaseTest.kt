package eu.advantly.gate.check

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.advantly.core.model.MatchId
import eu.advantly.core.model.Side.A
import eu.advantly.core.model.Side.B
import eu.advantly.data.LocalData
import eu.advantly.data.StoredEvent
import eu.advantly.data.db.AdvantlyDatabase
import java.io.File
import java.nio.file.Files
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceDatabaseTest {
    private fun writeDb(
        file: File,
        own: String,
        events: List<StoredEvent>,
        configure: (LocalData) -> Unit = {},
    ) {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}", Properties(), AdvantlyDatabase.Schema)
        val data = LocalData(driver)
        data.settings.put(DeviceDatabase.DEVICE_ID_KEY, own)
        data.settings.put(DeviceDatabase.PEER_KIND_PREFIX + PHONE, "PHONE")
        data.events.appendAll(events)
        configure(data)
        driver.close()
    }

    @Test
    fun loadsEventsConfirmationAndIdentityFromFile() {
        val dir = Files.createTempDirectory("gate-db").toFile()
        try {
            val log = LogBuilder().apply { points(WATCH, A, B, A) }
            val stored = log.stored().mapIndexed { i, e -> e.copy(confirmed = i < 3) }
            val file = File(dir, "advantly.db")
            writeDb(file, WATCH, stored)
            val before = file.readBytes()

            val snap = DeviceDatabase.load("uhr", file)

            assertEquals(WATCH, snap.ownDeviceId)
            assertEquals("PHONE", snap.knownKinds[PHONE])
            val match = snap.matches.getValue(MATCH)
            assertEquals(stored.map { it.id }, match.events.map { it.id })
            assertEquals(setOf(stored[3].id), match.unconfirmedIds)
            assertNull(match.header)
            assertTrue(before.contentEquals(file.readBytes()), "Original darf nicht verändert werden")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun endToEndCompareWritesReports() {
        val dir = Files.createTempDirectory("gate-e2e").toFile()
        try {
            val log = LogBuilder().apply { points(WATCH, A, A, B) }
            writeDb(File(dir, "phone.db"), PHONE, log.stored())
            writeDb(File(dir, "watch.db"), WATCH, log.stored()) { it.deleteMatch(MatchId("old-match"), 1) }
            val out = File(dir, "out")

            val code =
                run(
                    listOf(
                        "compare",
                        "--db",
                        "handy=${File(dir, "phone.db").path}",
                        "--db=uhr=${File(dir, "watch.db").path}",
                        "--out",
                        out.path,
                        "--strict",
                    ),
                )

            assertEquals(0, code)
            val index = File(out, "index.md").readText()
            assertTrue("**Gesamt: BESTANDEN**" in index, index)
            assertTrue(File(out, "vergleich.txt").readText().startsWith("$MATCH events=4 pointWon=3"))
            assertEquals(1, out.listFiles()!!.count { it.name.startsWith("match-") && "-tennis-" in it.name })
        } finally {
            dir.deleteRecursively()
        }
    }
}
