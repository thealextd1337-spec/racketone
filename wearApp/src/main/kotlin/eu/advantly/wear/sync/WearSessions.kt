package eu.advantly.wear.sync

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import eu.advantly.data.AndroidInstallationGuard
import eu.advantly.data.LocalData
import eu.advantly.match.session.AndroidMatchSessions
import eu.advantly.match.session.AndroidSessionBootstrap
import eu.advantly.match.session.MatchSession
import eu.advantly.match.session.SessionListener
import eu.advantly.match.session.SyncStatus
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
        AndroidInstallationGuard.requireReady(context)
        return holder ?: prepare(context) { _, _ -> }
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
     * mehr ausstehend – weder Events noch Lifecycle-Zustellungen ([SyncStatus.settled], E4). Nur Main-Thread.
     */
    fun isSyncSettled(): Boolean =
        holder
            ?.session
            ?.state
            ?.value
            ?.sync
            ?.let(::isSettled) == true

    internal fun isSettled(sync: SyncStatus): Boolean =
        sync.peers.any { it.compatibility == CompatibilityStatus.COMPATIBLE } && sync.lastSyncAtMs != null && sync.settled

    /** Wear-Resume und Hostlesepfade vor der Veröffentlichung derselben Sessiongeneration vorbereiten. */
    fun prepare(
        context: Context,
        configure: (Holder, AndroidSessionBootstrap) -> Unit,
    ): Holder {
        val app = context.applicationContext
        var prepared: Holder? = null
        holder = null
        AndroidMatchSessions.prepare(app, PeerKind.WATCH) { session, stage ->
            val local = Holder(session, WearPrefs(app)).also { it.prefs.openMatchId?.let(session::resume) }
            prepared = local
            stage.onDiscard { if (holder === local) holder = null }
            configure(local, stage)
            val rememberOpen = SessionListener { local.prefs.openMatchId = it.match?.id }
            stage.onDiscard { session.removeListener(rememberOpen) }
            stage.afterCommit {
                session.addListener(rememberOpen)
                if (DiagnosticLog.ENABLED) attachDiagnostics(session)
                startedAtElapsed = SystemClock.elapsedRealtime()
                holder = local
                DiagnosticLog.log("SESSION_STARTED", "device" to session.deviceId.value)
            }
        }
        return checkNotNull(prepared)
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
