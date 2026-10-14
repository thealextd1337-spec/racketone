package eu.advantly.wear.scoring

import android.content.Context
import eu.advantly.wear.diagnostics.DiagnosticLog
import eu.advantly.wear.workout.SessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Prozessweiter Zählerstand des Spikes; persistiert jede Änderung in [SessionStore] und protokolliert sie im Diagnose-Log. */
object ScoreRepository {
    private var store: SessionStore? = null
    private var counter = SpikeScoreCounter()
    private val mutable = MutableStateFlow(SpikeScore())
    val score: StateFlow<SpikeScore> = mutable.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (store != null) return
        val s = SessionStore(context.applicationContext)
        store = s
        counter = s.loadCounter()
        mutable.value = counter.score
    }

    @Synchronized
    fun addPoint(side: Side): SpikeScore {
        val result = counter.addPoint(side)
        publish()
        DiagnosticLog.log("POINT", "side" to side, "score" to result.format())
        return result
    }

    /** Liefert die zurückgenommene Seite oder `null`, wenn es nichts rückgängig zu machen gibt. */
    @Synchronized
    fun undo(): Side? {
        val undone = counter.undo() ?: return null
        publish()
        DiagnosticLog.log("UNDO", "side" to undone, "score" to counter.score.format())
        return undone
    }

    @Synchronized
    fun reset() {
        counter.reset()
        publish()
    }

    private fun publish() {
        store?.saveCounter(counter)
        mutable.value = counter.score
    }
}
