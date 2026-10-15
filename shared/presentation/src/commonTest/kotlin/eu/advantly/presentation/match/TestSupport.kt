package eu.advantly.presentation.match

import eu.advantly.core.model.Discipline
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchFormat
import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.model.Uuid7

/** Deterministische Uhr: jede Abfrage rückt um [stepMs] vor. */
class StepClock(
    var now: Long = 1_760_000_000_000,
    private val stepMs: Long = 1_000,
) : WallClock {
    override fun nowMs(): Long = now.also { now += stepMs }
}

/** Deterministische IDs (fortlaufender Zähler in den Zufallsbits). */
class CountingIds(
    private var n: Long = 0,
) : IdFactory {
    override fun newId(): Uuid7 = Uuid7.fromParts(1_760_000_000_000, 0, ++n)
}

fun testStore(selfName: String = "Alex"): InMemoryMatchStore = InMemoryMatchStore(StepClock(), CountingIds(), selfName = selfName)

/** Setup mit dem eigenen Spieler (A) gegen Gäste (B); Doppel mit Partner. */
fun PlayersPort.setupFor(
    format: MatchFormat,
    firstServerSide: Side = Side.A,
): MatchSetup {
    val me = self.value
    val partner = if (format.discipline == Discipline.DOUBLES) addGuest("Mia") else null
    val opponents = List(format.discipline.playersPerSide) { addGuest(if (it == 0) "Jonas" else "Tom") }
    val a = listOfNotNull(me, partner)
    val players: List<Player> = a + opponents
    val first: PlayerId = if (firstServerSide == Side.A) me.id else opponents.first().id
    return MatchSetup(format, Lineup(a.map { it.id }, opponents.map { it.id }, first), players = players)
}

/** Punktfolge, z. B. "AAAB" = drei Punkte A, einer B. */
fun LiveMatchPort.play(sequence: String) {
    sequence.forEach { c ->
        check(recordPoint(if (c == 'A') Side.A else Side.B)) { "Punkt $c abgelehnt" }
    }
}
