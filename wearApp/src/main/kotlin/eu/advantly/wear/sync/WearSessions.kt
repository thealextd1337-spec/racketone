package eu.advantly.wear.sync

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import eu.advantly.data.LocalData
import eu.advantly.match.session.AndroidMatchSessions
import eu.advantly.match.session.MatchSession
import eu.advantly.match.session.SessionListener
import eu.advantly.sync.protocol.CompatibilityStatus
import eu.advantly.sync.protocol.PeerKind
import eu.advantly.wear.diagnostics.DiagnosticLog

/**
 * Prozessweite Match-Session der Uhr (Art `WATCH`) über die gemeinsame Fabrik
 * `AndroidMatchSessions.get(context, PeerKind.WATCH)`; gestartet in `Application.onCreate` und damit auch aktiv, wenn
 * der `SyncListenerService` den Prozess im Hintergrund weckt. Datenbank: `MatchSession.data`; Hintergrund-Empfang:
 * `WearDataLayerInbound` (beides aus `shared/`).
 *
 * Zusätzlich hier: zuletzt offenes Match nach Prozess-Neustart wieder öffnen, Kaltstart-Erkennung für den
 * Listener-Dienst und (nur Debug) die Verbindungs-Diagnose im Diag-Log.
 */
object WearSessions {
    /** Bausteine der laufenden Session. */
    class Holder(
        val session: MatchSession,
        val prefs: WearPrefs,
    ) {
        val data: LocalData get() = session.data
    }

    private var holder: Holder? = null
    private var startedAtElapsed = 0L
    private var coldStartConsumed = false
    private const val COLD_START_WINDOW_MS = 10_000L

    /** Liefert die gestartete Session; legt sie beim ersten Aufruf an. Nur Main-Thread. */
    fun get(context: Context): Holder {
        check(Looper.myLooper() == Looper.getMainLooper()) { "WearSessions nur auf dem Main-Thread" }
        return holder ?: create(context.applicationContext).also { holder = it }
    }

    /**
     * `true` genau einmal, wenn die Session gerade erst (≤ [COLD_START_WINDOW_MS]) mit dem Prozess gestartet wurde –
     * typisch: Play Services hat den Prozess für eine Handy-Nachricht geweckt. `Application.onCreate` startet die
     * Session immer, „schon gestartet“ allein sagt darüber also nichts. Nur Main-Thread.
     */
    fun consumeColdStart(): Boolean {
        if (holder == null || coldStartConsumed) return false
        coldStartConsumed = true
        return SystemClock.elapsedRealtime() - startedAtElapsed <= COLD_START_WINDOW_MS
    }

    /**
     * Abgleich nach dem Start abgeschlossen: eine kompatible Gegenstelle ist da, ein Abgleich kam an und nichts ist
     * mehr ausstehend. Nur Main-Thread.
     */
    fun isSyncSettled(): Boolean {
        val sync =
            holder
                ?.session
                ?.state
                ?.value
                ?.sync ?: return false
        val peerReady = sync.peers.any { it.compatibility == CompatibilityStatus.COMPATIBLE }
        return peerReady && sync.lastSyncAtMs != null && sync.pendingToPeers == 0
    }

    private fun create(app: Context): Holder {
        startedAtElapsed = SystemClock.elapsedRealtime()
        val session = AndroidMatchSessions.get(app, PeerKind.WATCH)
        val prefs = WearPrefs(app)
        prefs.openMatchId?.let { id ->
            val resumed = session.resume(id)
            DiagnosticLog.log("MATCH_RESUME_AFTER_RESTART", "match" to id.value, "ok" to resumed)
        }
        // Offenes Match merken (Neustart) – addListener liefert sofort den aktuellen Stand
        session.addListener(SessionListener { prefs.openMatchId = it.match?.id })
        if (DiagnosticLog.ENABLED) attachDiagnostics(session)
        DiagnosticLog.log("SESSION_STARTED", "device" to session.deviceId.value)
        return Holder(session, prefs)
    }

    /** Debug: Verbindungsereignisse (Hello, Peer-Wechsel, Sends/Empfänge, Fehler) ins Diag-Log – ohne Gesundheitsdaten. */
    private fun attachDiagnostics(session: MatchSession) {
        var last: ConnectionSnapshot? = null
        session.addListener(
            SessionListener { state ->
                val next = ConnectionSnapshot(state.sync.link, state.sync.peers, session.linkStats, state.sync.pendingToPeers)
                DiagnosticLog.logAll(ConnectionDiagnostics.diff(last, next))
                last = next
            },
        )
    }
}
