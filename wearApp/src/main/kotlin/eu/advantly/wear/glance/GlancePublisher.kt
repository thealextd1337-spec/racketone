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
    private val restoredState = GlanceStateStorage.decode(holder.prefs.glanceSentState)
    private val throttle = GlanceUpdateThrottle(initialState = restoredState)
    private var initialized = false
    private val directoryRefresh = GlanceDirectoryRefresh()
    private val directory =
        WatchDirectory(holder.data, System::currentTimeMillis) {
            PlayerId(RandomIdGenerator().newUuid7(System.currentTimeMillis()).value)
        }
    private var setup: WatchSetup? = null
    private var names: Map<PlayerId, String?> = emptyMap()
    private var selfIds: Set<PlayerId> = emptySet()
    private val update = Runnable { requestUpdate() }

    init {
        // Prozessweiter Publisher: die Registrierung lebt genauso lange wie LocalData und die Session.
        holder.data.observeDirectoryChanges(directoryRefresh::invalidate)
    }

    override fun onStateChanged(state: SessionState) {
        if (directoryRefresh.shouldRefresh(state)) {
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
        if (!initialized) {
            initialized = true
            if (restoredState == null) {
                // Neue Cache-Generation: auch externe Tile-/Complication-Caches mit der frischen Sicht ersetzen.
                requestUpdate()
                return
            }
        }
        handler.removeCallbacks(update)
        when (val delay = throttle.delayFor(snapshot, SystemClock.elapsedRealtime())) {
            null -> Unit
            0L -> requestUpdate()
            else -> handler.postDelayed(update, delay)
        }
    }

    private fun requestUpdate() {
        TileService.getUpdater(context).requestUpdate(MatchTileService::class.java)
        ComplicationDataSourceUpdateRequester
            .create(context, ComponentName(context, MatchComplicationService::class.java))
            .requestUpdateAll()
        throttle.sent(snapshot, SystemClock.elapsedRealtime())
        holder.prefs.glanceSentState = GlanceStateStorage.encode(snapshot)
    }

    companion object {
        /** Bereits beim Application-Start aus der DB/Session rekonstruiert; Provider lesen nur diesen Wert. */
        @Volatile
        var snapshot: GlanceState = GlanceState()
            private set
    }
}
