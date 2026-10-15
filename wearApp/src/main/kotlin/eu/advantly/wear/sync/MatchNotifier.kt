package eu.advantly.wear.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import eu.advantly.core.model.MatchId
import eu.advantly.core.scoring.MatchStatus
import eu.advantly.match.session.SessionListener
import eu.advantly.match.session.SessionState
import eu.advantly.wear.MainActivity
import eu.advantly.wear.R

/**
 * Hinweis „Match läuft – auf der Uhr öffnen“ (05 §2.2): Startet das Handy ein Match (Director oder „Uhr zählt“),
 * während die Wear-UI nicht sichtbar ist, öffnet die Session es automatisch – Activities und den Health-Dienst darf die
 * App aus dem Hintergrund aber nicht starten. Ein Tipp auf die Benachrichtigung öffnet die App (dort startet dann auch
 * das Workout). Ohne Benachrichtigungsrecht entfällt der Hinweis; das Match erscheint beim nächsten Öffnen.
 */
class MatchNotifier(
    context: Context,
) : SessionListener {
    private val app = context.applicationContext

    /** Zuletzt gesehener Stand: Match, Rolle, beendet. */
    private data class Seen(
        val matchId: MatchId?,
        val isScorer: Boolean,
        val finished: Boolean,
    )

    private var seen: Seen? = null
    private var posted = false

    override fun onStateChanged(state: SessionState) {
        val match = state.match
        val now = Seen(match?.id, match?.isScorer == true, match?.state?.status == MatchStatus.FINISHED)
        val before = seen
        seen = now
        // Erster Aufruf beim Anmelden (Prozessstart, ggf. wiederhergestelltes Match): kein neuer Hinweis
        if (before == null || before == now) return
        when {
            match == null || now.finished -> {
                remove()
            }

            now.matchId != before.matchId -> {
                if (UiVisibility.resumed) remove() else post(now.isScorer)
            }

            // gleiches Match, Rolle gewechselt (Übernahme): Text eines sichtbaren Hinweises nachziehen
            posted && !UiVisibility.resumed && now.isScorer != before.isScorer -> {
                post(now.isScorer)
            }
        }
    }

    private fun remove() {
        posted = false
        cancel(app)
    }

    private fun post(isScorer: Boolean) {
        val granted =
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) return
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, app.getString(R.string.notification_channel_match), NotificationManager.IMPORTANCE_HIGH),
        )
        val open =
            PendingIntent.getActivity(
                app,
                REQUEST_CODE,
                Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val text = if (isScorer) R.string.notification_match_scorer else R.string.notification_match_display
        val notification =
            NotificationCompat
                .Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_advantly)
                .setContentTitle(app.getString(R.string.notification_match_title))
                .setContentText(app.getString(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
        posted = runCatching { NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, notification) }.isSuccess
    }

    companion object {
        private const val CHANNEL_ID = "match_started"
        private const val NOTIFICATION_ID = 4712
        private const val REQUEST_CODE = 1

        /** Entfernt den Hinweis (App ist sichtbar bzw. Match beendet/geschlossen). */
        fun cancel(context: Context) {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        }
    }
}
