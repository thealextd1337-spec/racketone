package eu.advantly.wear.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.VibrationEffect
import android.os.VibrationEffect.Composition.PRIMITIVE_CLICK
import android.os.VibrationEffect.Composition.PRIMITIVE_LOW_TICK
import android.os.VibrationEffect.Composition.PRIMITIVE_QUICK_FALL
import android.os.VibrationEffect.Composition.PRIMITIVE_SLOW_RISE
import android.os.VibrationEffect.Composition.PRIMITIVE_THUD
import android.os.VibrationEffect.Composition.PRIMITIVE_TICK
import android.os.Vibrator
import android.os.VibratorManager
import eu.advantly.wear.live.WatchHaptic

/**
 * Haptik-Muster nach 04 §5.8: Punkt Wir = 1 Klick, Punkt Gegner = 2 Klicks (120 ms), Undo = abwärts, Spiel gewonnen
 * bzw. verloren, Satz = Anstieg + Klick, Seitenwechsel = 3 Ticks, Ablehnung. Break-/Satz-/Matchball hängt einen
 * Impuls (THUD) an dasselbe Muster an – ein zweites `vibrate` würde das erste abbrechen.
 * Nutzt Composition-Primitive, wenn das Gerät sie kann, sonst kurze Waveforms als Ersatz.
 */
class Haptics(
    context: Context,
) {
    private val vibrator: Vibrator? =
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator?.takeIf { it.hasVibrator() }

    /** Spielt [pattern]; [attention] = zusätzlicher Aufmerksamkeits-Impuls danach. */
    @SuppressLint("WrongConstant") // Primitive-IDs stammen ausschließlich aus Composition.PRIMITIVE_* (recipe)
    fun play(
        pattern: WatchHaptic,
        attention: Boolean = false,
    ) {
        val v = vibrator ?: return
        if (pattern == WatchHaptic.REJECTED) {
            v.vibrate(VibrationEffect.createOneShot(REJECT_MS, VibrationEffect.DEFAULT_AMPLITUDE))
            return
        }
        val (primitives, waveform) = recipe(pattern)
        val allPrimitives = if (attention) primitives + (PRIMITIVE_THUD to ATTENTION_DELAY_MS) else primitives
        val ids = allPrimitives.map { it.first }.toIntArray()
        val effect =
            if (v.areAllPrimitivesSupported(*ids)) {
                VibrationEffect.startComposition().apply { allPrimitives.forEach { (id, delay) -> addPrimitive(id, 1f, delay) } }.compose()
            } else {
                VibrationEffect.createWaveform(if (attention) waveform + ATTENTION_WAVEFORM else waveform, -1)
            }
        v.vibrate(effect)
    }

    /** Primitive (Id, Verzögerung ms) und Waveform-Ersatz (Pause/An abwechselnd). */
    private fun recipe(pattern: WatchHaptic): Pair<List<Pair<Int, Int>>, LongArray> =
        when (pattern) {
            WatchHaptic.POINT_US -> {
                listOf(PRIMITIVE_CLICK to 0) to longArrayOf(0, 40)
            }

            WatchHaptic.POINT_THEM -> {
                listOf(PRIMITIVE_CLICK to 0, PRIMITIVE_CLICK to GAP_MS) to longArrayOf(0, 40, GAP_MS.toLong(), 40)
            }

            WatchHaptic.UNDO -> {
                listOf(PRIMITIVE_QUICK_FALL to 0) to longArrayOf(0, 150)
            }

            WatchHaptic.GAME_WON -> {
                listOf(PRIMITIVE_CLICK to 0, PRIMITIVE_TICK to 80) to longArrayOf(0, 80, 80, 80)
            }

            WatchHaptic.GAME_LOST -> {
                listOf(PRIMITIVE_LOW_TICK to 0) to longArrayOf(0, 60)
            }

            WatchHaptic.SET_WON -> {
                listOf(PRIMITIVE_SLOW_RISE to 0, PRIMITIVE_CLICK to 0) to longArrayOf(0, 300)
            }

            WatchHaptic.SET_LOST -> {
                listOf(PRIMITIVE_LOW_TICK to 0, PRIMITIVE_LOW_TICK to GAP_MS) to longArrayOf(0, 200)
            }

            WatchHaptic.CHANGE_ENDS -> {
                listOf(PRIMITIVE_TICK to 0, PRIMITIVE_TICK to 100, PRIMITIVE_TICK to 100) to
                    longArrayOf(0, 60, 80, 60, 80, 60)
            }

            WatchHaptic.REJECTED -> {
                emptyList<Pair<Int, Int>>() to longArrayOf(0, REJECT_MS)
            }
        }

    private companion object {
        const val GAP_MS = 120
        const val REJECT_MS = 200L

        /** Impuls kommt nach dem Punkt-Muster, also „zu Beginn“ des nächsten Ballwechsels. */
        const val ATTENTION_DELAY_MS = 400
        val ATTENTION_WAVEFORM = longArrayOf(400, 30, 60, 30, 60, 30)
    }
}
