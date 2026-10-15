package eu.advantly.ui.phone

import eu.advantly.core.model.Discipline
import eu.advantly.core.model.Lineup
import eu.advantly.core.model.MatchFormat
import eu.advantly.core.model.MatchMeta
import eu.advantly.core.model.Side
import eu.advantly.core.model.Uuid7
import eu.advantly.presentation.match.IdFactory
import eu.advantly.presentation.match.InMemoryMatchStore
import eu.advantly.presentation.match.LiveMatchPort
import eu.advantly.presentation.match.MatchSetup
import eu.advantly.presentation.match.WallClock
import eu.advantly.ui.phone.app.AdvantlyAppModel

/** Deterministische Uhr (1 s je Abfrage) und IDs für UI-Tests. */
class StepClock : WallClock {
    var now = 1_760_000_000_000
        private set

    override fun nowMs(): Long = now.also { now += 1_000 }
}

private class CountingIds : IdFactory {
    private var n = 0L

    override fun newId(): Uuid7 = Uuid7.fromParts(1_760_000_000_000, 0, ++n)
}

/** App-Modell über einem frischen In-Memory-Store (Self „Alex“); die Anzeige-Uhr liest die Store-Uhr, ohne sie vorzurücken. */
fun testModel(wrap: (InMemoryMatchStore) -> LiveMatchPort = { it }): AdvantlyAppModel {
    val clock = StepClock()
    val store = InMemoryMatchStore(clock, CountingIds(), selfName = "Alex")
    return AdvantlyAppModel(wrap(store), store, store, clock = WallClock { clock.now })
}

/** App-Modell, dessen Live-Port ein [FakeSyncPort] über der In-Memory-Engine ist. */
fun fakeSyncModel(): Pair<AdvantlyAppModel, FakeSyncPort> {
    var fake: FakeSyncPort? = null
    val model = testModel { store -> FakeSyncPort(store).also { fake = it } }
    return model to fake!!
}

/** Setup Self (+ Partner) gegen Gäste; [firstServerSide] schlägt zuerst auf. */
fun AdvantlyAppModel.setupFor(
    format: MatchFormat,
    firstServerSide: Side = Side.A,
    meta: MatchMeta = MatchMeta(),
): MatchSetup {
    val me = players.self.value
    val partner = if (format.discipline == Discipline.DOUBLES) players.addGuest("Mia") else null
    val opponents = List(format.discipline.playersPerSide) { players.addGuest(if (it == 0) "Jonas" else "Tom") }
    val a = listOfNotNull(me, partner)
    val first = if (firstServerSide == Side.A) me.id else opponents.first().id
    return MatchSetup(format, Lineup(a.map { it.id }, opponents.map { it.id }, first), meta, a + opponents)
}

/** Startet [format] direkt (ohne Assistent) und spielt [points] („A“/„B“). */
fun AdvantlyAppModel.startWith(
    format: MatchFormat,
    points: String = "",
    firstServerSide: Side = Side.A,
    meta: MatchMeta = MatchMeta(),
) {
    live.start(setupFor(format, firstServerSide, meta))
    live.play(points)
    controller.sync(live.active.value)
}

fun LiveMatchPort.play(points: String) {
    points.forEach { check(recordPoint(if (it == 'A') Side.A else Side.B)) { "Punkt $it abgelehnt" } }
}
