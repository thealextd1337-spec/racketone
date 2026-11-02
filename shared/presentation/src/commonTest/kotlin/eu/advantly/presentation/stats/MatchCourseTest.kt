package eu.advantly.presentation.stats

import eu.advantly.core.model.Side
import eu.advantly.core.model.TelemetryVisibility
import eu.advantly.core.scoring.MomentKind
import eu.advantly.core.scoring.Presets
import eu.advantly.core.scoring.SidePair
import eu.advantly.match.session.WATCH_HEALTH_RETENTION_MS
import eu.advantly.presentation.match.CountingIds
import eu.advantly.presentation.match.InMemoryMatchStore
import eu.advantly.presentation.match.PulseSetting
import eu.advantly.presentation.match.StepClock
import eu.advantly.presentation.match.setupFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MatchCourseTest {
    private val time = StepClock(now = 0, stepMs = 0)
    private val store = InMemoryMatchStore(time, CountingIds())

    /** Punkte im Abstand von [stepMs] (der erste [stepMs] nach dem letzten Zeitpunkt). */
    private fun play(
        points: String,
        stepMs: Long = 10_000,
    ) = points.forEach { c ->
        time.now += stepMs
        check(store.recordPoint(if (c == 'A') Side.A else Side.B))
    }

    private fun stats(id: eu.advantly.core.model.MatchId) = MatchStatistics.compute(store.record(id)!!)

    @Test
    fun tennisSetsAreSeparateSectionsWithGamesAsWideAsTheirDurationAndPausesCut() {
        val id = store.start(store.setupFor(Presets.TENNIS_SINGLES_BO3.format()))
        play("AAAA") // 1:0 nach 40 s
        play("AAAA", stepMs = 20_000) // 2:0, 80 s
        play("A".repeat(16)) // 6:0
        assertTrue(store.pause())
        time.now += 600_000
        play("BBBB") // Satz 2, Spiel 1: A schlägt auf, der Gegner breakt
        val sections = stats(id).courseSections(perSet = true)
        assertEquals(listOf(1, 2), sections.map { it.set })
        val set1 = sections[0]
        assertTrue(set1.timed)
        assertEquals(SidePair(6, 0), set1.score)
        assertEquals(listOf(0L to 40_000L, 40_000L to 120_000L), set1.games.take(2).map { it.startMs to it.endMs })
        assertEquals(set1.games.zipWithNext().map { it.first.endMs }, set1.games.drop(1).map { it.startMs }, "lückenlos")
        val set2 = sections[1]
        assertEquals(set1.endMs, set2.startMs)
        // Die Pause endet mit dem ersten Punkt danach (02 §9.3): bleiben die 3 Abstände à 10 s
        assertEquals(30_000, set2.durationMs)
        assertEquals(SidePair(0, 1), set2.score)
    }

    @Test
    fun momentsLandInTheSetOfTheirPoint() {
        val id = store.start(store.setupFor(Presets.TENNIS_SINGLES_BO3.format()))
        play("A".repeat(24))
        play("AABBBB")
        val stats = stats(id)
        val sections = stats.courseSections(perSet = true)
        val set2 = sections[1]
        val breakMoment = set2.moments.single { MomentKind.BREAK in stats.moments[it.index].moment.kinds }
        assertEquals(set2.endMs, breakMoment.atMs)
        assertTrue(
            sections[0].moments.all {
                stats.moments[it.index]
                    .before.sets
                    .isEmpty()
            },
        )
        assertTrue(set2.moments.all { it.atMs in set2.startMs..set2.endMs })
    }

    @Test
    fun rallySportIsOneSectionWithASetPerBar() {
        val id = store.start(store.setupFor(Presets.TABLE_TENNIS_SINGLES_BO3.format()))
        play("A".repeat(11) + "B".repeat(11))
        val section = stats(id).courseSections(perSet = false).single()
        assertNull(section.set)
        assertEquals(2, section.games.size)
        assertEquals(SidePair(1, 1), section.score)
        assertEquals(220_000, section.durationMs)
    }

    @Test
    fun withoutTimestampsEveryGameIsOneUnit() {
        val id = store.start(store.setupFor(Presets.TENNIS_SINGLES_BO3.format()))
        play("AAAABBBB", stepMs = 0)
        val section = stats(id).courseSections(perSet = true).single()
        assertFalse(section.timed)
        assertEquals(listOf(0L to 1L, 1L to 2L), section.games.map { it.startMs to it.endMs })
        assertEquals(0, section.durationMs)
    }

    @Test
    fun emptyMatchHasNoSections() {
        val id = store.start(store.setupFor(Presets.TENNIS_SINGLES_BO3.format()))
        assertEquals(emptyList(), stats(id).courseSections(perSet = true))
    }

    @Test
    fun pulseWithinASectionAndItsSummary() {
        val segments = listOf(listOf(PulsePoint(0, 100), PulsePoint(10, 120)), listOf(PulsePoint(20, 141), PulsePoint(30, 160)))
        assertEquals(listOf(listOf(PulsePoint(10, 120)), listOf(PulsePoint(20, 141))), segments.within(5, 25))
        assertEquals(PulseSummary(130, 160), segments.pulseSummary())
        assertNull(emptyList<List<PulsePoint>>().pulseSummary())
    }

    @Test
    fun axisAndPulseTicks() {
        assertEquals(listOf(300_000L, 600_000L, 900_000L, 1_200_000L, 1_500_000L, 1_800_000L), axisTicks(0, 1_900_000))
        assertEquals(listOf(2_640_000L, 2_760_000L, 2_880_000L, 3_000_000L), axisTicks(2_520_000, 3_100_000))
        assertEquals(emptyList(), axisTicks(10, 10))
        assertTrue(axisTicks(0, 5 * 3_600_000).size <= 6)
        assertEquals(listOf(100, 150), pulseTicks(85, 186))
        assertEquals(listOf(120, 140), pulseTicks(105, 150))
    }

    @Test
    fun symbolsSpreadApartAndStayInside() {
        // zwei fast gleiche Zeitpunkte rücken auseinander
        assertEquals(listOf(100f, 148f), placeSymbols(listOf(100f, 101f), 400f, 48f).map { it.center })
        // am rechten Rand nach links zusammengeschoben
        assertEquals(listOf(328f, 376f), placeSymbols(listOf(390f, 400f), 400f, 48f).map { it.center })
        // drei auf einem Fleck: das dritte rückte zwei Breiten weg, deshalb teilen sich die beiden nächsten eine Fläche
        val cluster = placeSymbols(listOf(390f, 399f, 400f), 400f, 48f)
        assertEquals(listOf(listOf(0), listOf(1, 2)), cluster.map { it.members })
        assertEquals(listOf(328f, 376f), cluster.map { it.center })
        // am linken Rand nicht abgeschnitten
        assertEquals(24f, placeSymbols(listOf(0f), 400f, 48f).single().center)
    }

    @Test
    fun tooManySymbolsShareTheClosestSlots() {
        val xs = listOf(0f, 1f, 2f, 50f, 51f, 100f)
        val slots = placeSymbols(xs, 100f, 48f)
        assertEquals(2, slots.size)
        assertEquals(xs.indices.toList(), slots.flatMap { it.members })
        assertTrue(slots.all { it.center >= 24f && it.center <= 76f })
        assertTrue(slots.zipWithNext().all { (a, b) -> b.center - a.center >= 48f })
    }

    @Test
    fun pulseTransferHintOnlyWhileAChosenSeriesIsMissing() {
        val chosen = PulseSetting(TelemetryVisibility.PRIVATE, recorded = false)
        assertEquals(PulseTransfer.PENDING, chosen.transfer(false, finishedAtMs = 0, nowMs = PULSE_OVERDUE_MS))
        assertEquals(PulseTransfer.OVERDUE, chosen.transfer(false, finishedAtMs = 0, nowMs = PULSE_OVERDUE_MS + 1))
        // nach der Aufbewahrung auf der Uhr kommt nichts mehr: neutral statt dauerhafter Aufforderung
        assertEquals(PulseTransfer.OVERDUE, chosen.transfer(false, finishedAtMs = 0, nowMs = WATCH_HEALTH_RETENTION_MS))
        assertEquals(PulseTransfer.MISSING, chosen.transfer(false, finishedAtMs = 0, nowMs = WATCH_HEALTH_RETENTION_MS + 1))
        assertNull(chosen.transfer(true, 0, 0))
        assertNull(chosen.copy(recorded = true).transfer(false, 0, 0))
        assertNull(PulseSetting().transfer(false, 0, 0), "keine Aufzeichnung gewählt")
    }
}
