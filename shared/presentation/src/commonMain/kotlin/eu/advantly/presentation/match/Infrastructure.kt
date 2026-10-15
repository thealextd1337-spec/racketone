package eu.advantly.presentation.match

import eu.advantly.core.model.Uuid7
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource

/** Wanduhr (ms seit Epoche) – injizierbar, damit Tests deterministisch sind. */
fun interface WallClock {
    fun nowMs(): Long

    companion object {
        /** Systemuhr. */
        @OptIn(ExperimentalTime::class)
        val System: WallClock = WallClock { Clock.System.now().toEpochMilliseconds() }
    }
}

/**
 * Monotone Zeit in Millisekunden (nur Differenzen sind aussagekräftig) – für laufende Spannen wie „Stand vor X s“,
 * unabhängig von Sprüngen der Wanduhr.
 */
fun interface MonotonicClock {
    fun nowMs(): Long

    companion object {
        /** Monotone Systemzeit seit dem ersten Zugriff. */
        val System: MonotonicClock =
            object : MonotonicClock {
                private val origin = TimeSource.Monotonic.markNow()

                override fun nowMs(): Long = origin.elapsedNow().inWholeMilliseconds
            }
    }
}

/** Quelle für neue UUIDv7 (Match-, Event-, Spieler-IDs) – injizierbar. */
fun interface IdFactory {
    fun newId(): Uuid7

    companion object {
        /** UUIDv7 aus [clock] und [random]. */
        fun uuid7(
            clock: WallClock,
            random: Random = Random.Default,
        ): IdFactory = IdFactory { Uuid7.fromParts(clock.nowMs(), random.nextInt(), random.nextLong()) }
    }
}

/** Lokales Kalenderdatum (für datumsabhängige Defaults, z. B. Badminton 3×21 → 3×15 am 04.01.2027). */
data class LocalDay(
    val year: Int,
    val month: Int,
    val day: Int,
)

/** Heutiges Datum in der lokalen Zeitzone des Geräts. */
expect fun systemLocalDay(): LocalDay
