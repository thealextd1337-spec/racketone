package eu.advantly.wear.ui

import android.content.Context
import android.os.VibrationEffect
import android.os.VibrationEffect.Composition.PRIMITIVE_CLICK
import android.os.VibrationEffect.Composition.PRIMITIVE_QUICK_FALL
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Haptik-Muster nach 04 §5.8: Punkt Wir = 1 Klick, Punkt Gegner = 2 Klicks (120 ms Abstand), Undo = abwärts.
 * Nutzt Composition-Primitive, wenn das Gerät sie kann, sonst kurze Waveforms als Ersatz.
 */
class Haptics(
    context: Context,
) {
    private val vibrator: Vibrator? =
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator?.takeIf { it.hasVibrator() }

    fun pointUs() {
        val v = vibrator ?: return
        v.vibrate(
            if (v.areAllPrimitivesSupported(PRIMITIVE_CLICK)) {
                VibrationEffect.startComposition().addPrimitive(PRIMITIVE_CLICK).compose()
            } else {
                VibrationEffect.createWaveform(longArrayOf(0, 40), -1)
            },
        )
    }

    fun pointThem() {
        val v = vibrator ?: return
        v.vibrate(
            if (v.areAllPrimitivesSupported(PRIMITIVE_CLICK)) {
                VibrationEffect
                    .startComposition()
                    .addPrimitive(PRIMITIVE_CLICK)
                    .addPrimitive(PRIMITIVE_CLICK, 1f, SECOND_CLICK_DELAY_MILLIS)
                    .compose()
            } else {
                VibrationEffect.createWaveform(longArrayOf(0, 40, SECOND_CLICK_DELAY_MILLIS.toLong(), 40), -1)
            },
        )
    }

    fun undo() {
        val v = vibrator ?: return
        v.vibrate(
            if (v.areAllPrimitivesSupported(PRIMITIVE_QUICK_FALL)) {
                VibrationEffect.startComposition().addPrimitive(PRIMITIVE_QUICK_FALL).compose()
            } else {
                VibrationEffect.createOneShot(150, 80)
            },
        )
    }

    private companion object {
        const val SECOND_CLICK_DELAY_MILLIS = 120
    }
}
