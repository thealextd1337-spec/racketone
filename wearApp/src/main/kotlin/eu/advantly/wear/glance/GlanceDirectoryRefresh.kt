package eu.advantly.wear.glance

import eu.advantly.core.model.MatchId
import eu.advantly.match.session.SessionState

/**
 * Globale Session-Revisionen allein sind kein Grund, das Verzeichnis erneut aus der DB zu lesen. Eine neue
 * `lifecycleRevision` (Access, Redaktion, Directory aus Data) lädt dagegen sofort neu, auch ohne `dataRevision`-Sprung.
 */
class GlanceDirectoryRefresh {
    private var dirty = true
    private var revision: Long? = null
    private var lifecycleRevision: Long? = null
    private var matchId: MatchId? = null

    fun invalidate() {
        dirty = true
    }

    fun shouldRefresh(state: SessionState): Boolean {
        val refresh =
            revision == null ||
                matchId != state.match?.id ||
                lifecycleRevision != state.lifecycleRevision ||
                (dirty && revision != state.dataRevision)
        revision = state.dataRevision
        lifecycleRevision = state.lifecycleRevision
        matchId = state.match?.id
        if (refresh) dirty = false
        return refresh
    }
}
