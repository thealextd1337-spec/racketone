package eu.advantly.match.session

import eu.advantly.core.model.Lineup
import eu.advantly.core.model.PlayerId
import eu.advantly.core.model.Side
import eu.advantly.core.scoring.Presets
import eu.advantly.sync.protocol.PeerKind
import kotlin.random.Random
import kotlin.test.Test

/**
 * Zufällige Sync-Simulation (01 §8) mit festen Seeds: drei Geräte übernehmen abwechselnd das Zählen (auch offline
 * und gleichzeitig), zählen, machen rückgängig, verlieren und verdoppeln Frames und gehen zeitweise offline.
 * Nach dem Wiederverbinden müssen alle denselben Zustand haben.
 */
class RandomizedConvergenceTest {
    @Test
    fun threeDevicesConvergeUnderChaos() {
        for (seed in 1..12) {
            val cluster =
                Cluster(
                    "phone" to PeerKind.PHONE,
                    "watch1" to PeerKind.WATCH,
                    "watch2" to PeerKind.WATCH,
                    seed = seed,
                )
            val random = Random(seed)
            val names = listOf("phone", "watch1", "watch2")
            cluster["phone"].session.create(Presets.PADEL_PREMIER_2026.format(), LINEUP4)
            cluster.settle()
            cluster.network.lossRate = random.nextDouble(0.0, 0.4)
            cluster.network.duplicateRate = random.nextDouble(0.0, 0.3)
            val offline = mutableSetOf<String>()
            repeat(80) {
                val name = names[random.nextInt(names.size)]
                val session = cluster[name].session
                when (random.nextInt(10)) {
                    0 -> {
                        session.undo()
                    }

                    1 -> {
                        session.redo()
                    }

                    2 -> {
                        val goOnline = !offline.add(name)
                        if (goOnline) offline -= name
                        cluster.network.setConnected(name, goOnline)
                    }

                    3 -> {
                        session.continueDecision(anotherSet = true)
                    }

                    4 -> {
                        session.takeOverScoring()
                    }

                    else -> {
                        session.recordPoint(if (random.nextBoolean()) Side.A else Side.B)
                    }
                }
                cluster.settle(random.nextLong(0, 3_000))
            }
            offline.forEach { cluster.online(it) }
            cluster.network.lossRate = 0.0
            cluster.network.duplicateRate = 0.0
            cluster.settle(65_000)
            try {
                cluster.assertConverged()
            } catch (e: AssertionError) {
                throw AssertionError("Seed $seed: ${e.message}", e)
            }
        }
    }

    private companion object {
        val LINEUP4 = Lineup(listOf(PlayerId("me"), PlayerId("max")), listOf(PlayerId("bea"), PlayerId("cem")), PlayerId("me"))
    }
}
