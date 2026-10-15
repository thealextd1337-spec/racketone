package eu.advantly.wear.glance

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import eu.advantly.core.model.PlayerId
import eu.advantly.data.assignments
import eu.advantly.match.session.RandomIdGenerator
import eu.advantly.match.session.SessionListener
import eu.advantly.match.session.SessionState
import eu.advantly.wear.match.WatchDirectory
import eu.advantly.wear.match.WatchSetup
import eu.advantly.wear.sync.WearSessions

/** Einziger Schreibpfad des Cache; auf dem Session-Thread registriert, unabhängig von der Activity. */
class GlancePublisher(
    private val context: Context,
    private val holder: WearSessions.Holder,
) : SessionListener {
    private val handler = Handler(Looper.getMainLooper())
    private val throttle = GlanceUpdateThrottle()
    private val directory =
        WatchDirectory(holder.data, System::currentTimeMillis) {
            PlayerId(RandomIdGenerator().newUuid7(System.currentTimeMillis()).value)
        }
    private var setup: WatchSetup? = null
    private var setupKey: Pair<Long, String?>? = null
    private var names: Map<PlayerId, String?> = emptyMap()
    private var selfIds: Set<PlayerId> = emptySet()
    private val update = Runnable { requestUpdate() }

    override fun onStateChanged(state: SessionState) {
        val key = state.dataRevision to state.match?.id?.value
        if (setupKey != key) {
            setupKey = key
            setup = directory.lastSetup()
            val assignments = state.match?.let { holder.data.assignments(it.id) }.orEmpty()
            val ids =
                (
                    setup?.lineup?.let { it.a + it.b }.orEmpty() +
                        state.match
                            ?.lineup
                            ?.let { it.a + it.b }
                            .orEmpty()
                ).distinct()
            names = ids.associateWith { assignments[it]?.displayName ?: directory.displayName(it) }
            selfIds = ids.filter(directory::isSelf).toSet()
        }
        snapshot = GlanceModel.derive(state, setup, names::get, selfIds::contains)
        handler.removeCallbacks(update)
        when (val delay = throttle.delayFor(snapshot, SystemClock.elapsedRealtime())) {
            null -> Unit
            0L -> requestUpdate()
            else -> handler.postDelayed(update, delay)
        }
    }

    private fun requestUpdate() {
        throttle.sent(snapshot, SystemClock.elapsedRealtime())
        TileService.getUpdater(context).requestUpdate(MatchTileService::class.java)
        ComplicationDataSourceUpdateRequester
            .create(context, ComponentName(context, MatchComplicationService::class.java))
            .requestUpdateAll()
    }

    companion object {
        /** Bereits beim Application-Start aus der DB/Session rekonstruiert; Provider lesen nur diesen Wert. */
        @Volatile
        var snapshot: GlanceState = GlanceState()
            private set
    }
}
