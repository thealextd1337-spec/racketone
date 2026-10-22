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
    staged: Boolean = false,
) : SessionListener {
    private var active = !staged
    private var prepared: GlanceState? = null
    private val handler = Handler(Looper.getMainLooper())
    private val restoredState =
        GlanceStateStorage.decode(holder.prefs.glanceSentState).also {
            // Alter Wert mit Namen (vor E5) oder defekt: sofort verwerfen, nicht erst beim nächsten Senden
            if (it == null && holder.prefs.glanceSentState != null) holder.prefs.glanceSentState = null
        }
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

    private val removeDirectoryObserver = holder.data.observeDirectoryChanges(directoryRefresh::invalidate)

    fun prepare(state: SessionState) {
        prepared = derive(state)
    }

    fun activate() {
        active = true
        publish(checkNotNull(prepared))
        prepared = null
    }

    fun dispose() {
        active = false
        handler.removeCallbacks(update)
        removeDirectoryObserver()
        snapshot = GlanceState()
    }

    override fun onStateChanged(state: SessionState) {
        if (active) publish(derive(state)) else prepare(state)
    }

    private fun derive(state: SessionState): GlanceState {
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
        return GlanceModel.derive(state, setup, names::get, selfIds::contains)
    }

    private fun publish(value: GlanceState) {
        if (!active) return
        snapshot = value
        if (!initialized) {
            initialized = true
            if (restoredState == null) {
                // Neue Cache-Generation: auch externe Tile-/Complication-Caches mit der frischen Sicht ersetzen.
                requestUpdate()
                return
            }
        }
        handler.removeCallbacks(update)
        val delay = throttle.delayFor(snapshot, SystemClock.elapsedRealtime())
        // Der namenlose Sendestand verdeckt reine Namensänderungen: endet der Prozess vorher, erzwingt der Neustart das Update
        if (delay != null && delay > 0) holder.prefs.glanceSentState = null
        when (delay) {
            null -> Unit
            0L -> requestUpdate()
            else -> handler.postDelayed(update, delay)
        }
    }

    private fun requestUpdate() {
        if (!active) return
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
