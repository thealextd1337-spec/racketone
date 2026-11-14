package eu.advantly.android.auth

import eu.advantly.cloud.sync.LiveSignal
import eu.advantly.cloud.sync.LiveSignalsPort
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Live-Signale (Plan W12 Paket 3) über den privaten Kanal `live:<profileId>`; Beitritt mit dem JWT der Auth-Sitzung,
 * den das SDK bei jeder Erneuerung nachreicht. Je Konto ein geteilter Kanal für alle Sammler (Multi-Window): Das SDK
 * kennt je Thema nur ein Kanalobjekt, ein zweites Abo würde es beim Abbau des ersten mit entfernen. Ohne Sammler wird
 * der Kanal verlassen; die Verbindung trennt das SDK, sobald kein Kanal mehr besteht. Nur auf Main.
 */
internal class PhoneLiveSignals(
    private val realtime: Realtime,
    private val scope: CoroutineScope,
) : LiveSignalsPort {
    private val shared = mutableMapOf<String, Flow<LiveSignal>>()

    // Ohne Ablauf des Replays meldete ein neuer Sammler sofort das alte SUBSCRIBED, bevor der Kanal wieder steht.
    override fun signals(profileId: String): Flow<LiveSignal> =
        shared.getOrPut(profileId) {
            channelSignals(profileId).shareIn(scope, SharingStarted.WhileSubscribed(replayExpirationMillis = 0), replay = 1)
        }

    /** Ein Abo des Kanals; Abbruch des Sammelns verlässt ihn (auch wenn er noch nicht stand, etwa offline). */
    internal fun channelSignals(profileId: String): Flow<LiveSignal> =
        channelFlow {
            val channel = realtime.channel("live:$profileId") { isPrivate = true }
            try {
                // Vor dem Beitritt registrieren: Signale direkt nach dem Beitritt gehen sonst verloren.
                launch { channel.broadcastFlow(CHANGED_EVENT).collect { send(LiveSignal.CHANGED) } }
                launch {
                    channel.status
                        .mapNotNull {
                            when (it) {
                                RealtimeChannel.Status.SUBSCRIBED -> LiveSignal.SUBSCRIBED
                                RealtimeChannel.Status.UNSUBSCRIBED -> LiveSignal.LOST
                                else -> null
                            }
                        }.distinctUntilChanged()
                        .collect { send(it) }
                }
                channel.subscribe()
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { realtime.removeChannel(channel) }
            }
        }.catch { emit(LiveSignal.LOST) } // Fehler des SDK: Polling wie ohne Realtime, nie den Prozess-Scope stören

    private companion object {
        const val CHANGED_EVENT = "changed"
    }
}
