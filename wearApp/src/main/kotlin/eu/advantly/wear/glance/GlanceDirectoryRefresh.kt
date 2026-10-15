package eu.advantly.wear.glance

import eu.advantly.core.model.MatchId
import eu.advantly.match.session.SessionState

/** Globale Session-Revisionen allein sind kein Grund, das Verzeichnis erneut aus der DB zu lesen. */
class GlanceDirectoryRefresh {
    private var dirty = true
    private var revision: Long? = null
    private var matchId: MatchId? = null

    fun invalidate() {
        dirty = true
    }

    fun shouldRefresh(state: SessionState): Boolean {
        val refresh = revision == null || matchId != state.match?.id || (dirty && revision != state.dataRevision)
        revision = state.dataRevision
        matchId = state.match?.id
        if (refresh) dirty = false
        return refresh
    }
}
