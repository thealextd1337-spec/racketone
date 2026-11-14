package eu.advantly.wear.live

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.advantly.core.model.Discipline
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.Placeholders
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.ServeTracking
import eu.advantly.core.model.Side
import eu.advantly.core.model.Sport
import eu.advantly.core.scoring.Presets
import eu.advantly.data.LocalData
import eu.advantly.data.db.AdvantlyDatabase
import eu.advantly.match.session.SessionTrace
import eu.advantly.presentation.live.PointLabel
import eu.advantly.wear.WatchHarness
import eu.advantly.wear.match.FormatChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Properties

/**
 * Paket 5 (Uhr-Performance): Haptik direkt nach dem dauerhaften Commit, vor Kopf, Versand und UI-Modell; höchstens ein
 * Neubau des UI-Modells je Tap; Namen und „Du“ aus einem Snapshot, der nur bei Identitätsänderungen neu lädt.
 */
class TapFeedbackTest {
    private class RecordingTrace : SessionTrace {
        val sections = mutableListOf<String>()

        override fun begin(section: String) {
            sections += section
        }

        override fun end() = Unit
    }

    /** Zählt Lesezugriffe auf Spieler (Namen, Self, Lesbarkeit). */
    private class CountingDriver(
        private val driver: SqlDriver,
    ) : SqlDriver by driver {
        var playerReads = 0

        override fun <R> executeQuery(
            identifier: Int?,
            sql: String,
            mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<R> {
            if ("FROM player" in sql) playerReads++
            return driver.executeQuery(identifier, sql, mapper, parameters, binders)
        }
    }

    private val trace = RecordingTrace()
    private val driver = CountingDriver(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), AdvantlyDatabase.Schema))
    private val h = WatchHarness(watchData = LocalData(driver), trace = trace)
    private val c = h.controller
    private val live: LiveUi get() = c.state.value.live!!

    private fun startOnWatch(serveTracking: ServeTracking = ServeTracking.OFF) {
        c.newMatch()
        c.selectSport(Sport.TENNIS)
        c.selectDiscipline(Discipline.DOUBLES)
        c.selectFormat(FormatChoice.TENNIS_BO3)
        c.setServeTracking(serveTracking)
        assertTrue(c.selectServer(Side.A))
        if (live.overlay == LiveOverlay.SERVER_CHOICE) c.dismissOverlay()
    }

    /** Ein Tap nach der Entprellung; liefert die Messabschnitte dieses Taps. */
    private fun tap(action: () -> Boolean): List<String> {
        h.scheduler.advanceBy(1_000)
        trace.sections.clear()
        assertTrue(action())
        return trace.sections.toList()
    }

    @Test
    fun hapticFollowsTheDurableCommitAndPrecedesHeaderSendAndModel() {
        startOnWatch()
        val id = live.matchId
        val stored = h.watchData.events.count(id.value)
        val header =
            h.watchData.matches
                .get(id)!!
                .score
        var atHaptic: Triple<Long, Any, PointLabel>? = null
        c.effects = {
            if (it is WatchEffect.Haptic) {
                atHaptic =
                    Triple(
                        h.watchData.events.count(id.value),
                        h.watchData.matches
                            .get(id)!!
                            .score,
                        live.points.a,
                    )
            }
            h.effects += it
        }

        val sections = tap { c.point(Side.A) }

        assertEquals(listOf("accepted", "commit", "engine", "haptic", "header", "send", "publish", "feedbackState"), sections)
        val (events, headerAtHaptic, shownAtHaptic) = atHaptic!!
        assertEquals("Punkt ist gespeichert, bevor es vibriert", stored + 1, events)
        assertEquals("Kopf folgt erst nach der Haptik", header, headerAtHaptic)
        assertNotEquals(
            header,
            h.watchData.matches
                .get(id)!!
                .score,
        )
        assertEquals("Anzeige folgt erst nach der Haptik", PointLabel.Number("0"), shownAtHaptic)
        assertEquals(PointLabel.Number("15"), live.points.a)
        assertEquals(WatchHaptic.POINT_US, h.haptics.single().pattern)
    }

    @Test
    fun eachTapRebuildsTheModelOnceAndVibratesOnce() {
        startOnWatch(ServeTracking.FULL)
        // zweiter Punkt bei offener Aufschlag-Leiste: Session speichert erst „1. Aufschlag“, dann den Punkt
        for (action in listOf({ c.point(Side.A) }, { c.point(Side.B) }, { c.undo() }, { c.redo() }, { c.removePoint(Side.A) })) {
            val before = h.haptics.size
            val sections = tap(action)
            assertEquals(sections.toString(), 1, sections.count { it == "feedbackState" })
            assertTrue(sections.toString(), h.haptics.size - before <= 1)
            sections.indexOf("haptic").takeIf { it >= 0 }?.let { assertTrue(sections.toString(), it < sections.lastIndexOf("publish")) }
        }
        assertEquals(
            listOf(WatchHaptic.POINT_US, WatchHaptic.POINT_THEM, WatchHaptic.UNDO, WatchHaptic.UNDO),
            h.haptics.map { it.pattern },
        )
    }

    @Test
    fun takeOverConfirmsWithHapticAfterTheCommit() {
        h.phone.create(Presets.TENNIS_SINGLES_BO3.format(), Lineup(listOf(PlayerId("me")), listOf(PlayerId("bea")), PlayerId("me")))
        h.settle()
        c.requestTakeOver()
        val sections = tap { c.confirmTakeOver() }
        assertEquals(LiveRole.SCORER, live.role)
        assertEquals(WatchHaptic.CONFIRM, h.haptics.last().pattern)
        assertTrue(sections.toString(), sections.indexOf("commit") < sections.indexOf("haptic"))
        assertTrue(sections.toString(), sections.indexOf("haptic") < sections.indexOf("header"))
        assertEquals(1, sections.count { it == "feedbackState" })
    }

    @Test
    fun pointsDoNotReloadNamesOrSelf() {
        startOnWatch()
        tap { c.point(Side.A) }
        driver.playerReads = 0
        repeat(3) { tap { c.point(Side.B) } }
        h.settle()
        assertEquals(0, driver.playerReads)
    }

    @Test
    fun identitiesFollowRenameAssignmentAndAlias() {
        val bea =
            h.watchData.players
                .addGuest(PlayerId("bea"), "Bea", 1)
                .id
        val self = h.directory.self()
        val slot = Placeholders.id("opponent")
        h.watch.create(Presets.TENNIS_SINGLES_BO3.format(), Lineup(listOf(self), listOf(slot), self))
        val id =
            h.watch.state.value.match!!
                .id
        assertTrue(h.watch.assignPlayer(id, slot, bea))
        h.settle()

        fun opponent() = live.teams.b.single()
        assertEquals("Bea", opponent().name)

        // lokale Umbenennung ohne Session-Revision: das Signal aus Data reicht
        h.watchData.players.rename(bea, "Beatrix")
        c.tick()
        assertEquals("Beatrix", opponent().name)

        val caro =
            h.watchData.players
                .addGuest(PlayerId("caro"), "Caro", 1)
                .id
        assertTrue(h.watch.assignPlayer(id, slot, caro))
        h.settle()
        assertEquals("Caro", opponent().name)
    }

    @Test
    fun selfFollowsNewAlias() {
        val dan =
            h.watchData.players
                .addGuest(PlayerId("dan"), "Dan", 1)
                .id
        val self = h.directory.self()
        h.watch.create(Presets.TENNIS_SINGLES_BO3.format(), Lineup(listOf(self), listOf(dan), self))
        h.settle()
        assertFalse(
            live.teams.b
                .single()
                .isSelf,
        )
        assertTrue(h.watchData.addSelfAliases(listOf(dan)))
        c.tick()
        assertTrue(
            live.teams.b
                .single()
                .isSelf,
        )
    }
}
