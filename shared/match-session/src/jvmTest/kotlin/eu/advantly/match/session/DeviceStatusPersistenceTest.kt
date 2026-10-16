package eu.advantly.match.session

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import eu.advantly.core.model.DeviceId
import eu.advantly.data.LocalData
import eu.advantly.data.createInMemoryJvmDriver
import eu.advantly.device.link.testing.ManualScheduler
import eu.advantly.sync.protocol.AppBuild
import eu.advantly.sync.protocol.Hello
import eu.advantly.sync.protocol.PeerKind
import eu.advantly.sync.protocol.Summary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeviceStatusPersistenceTest {
    private class CountingSettingsDriver(
        private val delegate: SqlDriver = createInMemoryJvmDriver(),
    ) : SqlDriver by delegate {
        var reads = 0
        var writes = 0

        override fun <R> executeQuery(
            identifier: Int?,
            sql: String,
            mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<R> {
            if (sql.contains("FROM setting")) reads++
            return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
        }

        override fun execute(
            identifier: Int?,
            sql: String,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<Long> {
            if (sql.contains("INTO setting")) writes++
            return delegate.execute(identifier, sql, parameters, binders)
        }
    }

    @Test
    fun trafficSkipsSettingsUntilFiveMinutesButBuildChangesPersistImmediately() {
        val driver = CountingSettingsDriver()
        try {
            val data = LocalData(driver)
            val scheduler = ManualScheduler()
            val link = WireLink()
            val phone = MatchSession(data, link, scheduler, SessionConfig(DeviceId("phone"), PeerKind.PHONE), { scheduler.nowMs() })
            phone.start()
            val hello = Hello(DeviceId("lab"), PeerKind.WATCH, session = "watch-session")
            link.deliver(hello)
            driver.reads = 0
            driver.writes = 0
            repeat(9) {
                scheduler.advanceBy(30_000)
                link.deliver(Summary(emptyMap()))
            }
            assertEquals(0, driver.reads, "Empfangszeit-Updates lesen keine Settings")
            assertEquals(0, driver.writes)
            scheduler.advanceBy(30_000)
            link.deliver(Summary(emptyMap()))
            assertEquals(1, driver.reads, "Nach fünf Minuten einmal speichern")
            assertEquals(1, driver.writes)
            scheduler.advanceBy(1)
            link.deliver(hello.copy(appBuild = AppBuild("0.1.0", "a48fa00")))
            assertEquals(2, driver.writes, "Neuer App-Stand wird ohne Wartezeit gespeichert")
            assertTrue(data.settings.get("session.deviceStatuses")!!.contains("a48fa00"))
            phone.close()
        } finally {
            driver.close()
        }
    }
}
