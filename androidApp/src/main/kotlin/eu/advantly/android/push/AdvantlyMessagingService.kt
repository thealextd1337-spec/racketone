package eu.advantly.android.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import eu.advantly.data.AndroidInstallationGuard
import eu.advantly.presentation.push.PushMessage

/**
 * FCM (nur data): Jede Nachricht stößt den Abgleich an, angezeigt wird sie nur bei eingeschaltetem Schalter; Text, Kanal
 * und Ziel entstehen lokal. Datenbankzugriffe erst nach der Installationsprüfung auf Main.
 */
class AdvantlyMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        AndroidInstallationGuard.checkAsync(applicationContext) { requestPushSync(applicationContext) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val push = PushMessage.parse(message.data) ?: return
        AndroidInstallationGuard.checkAsync(applicationContext) { deliverPush(applicationContext, push) }
    }
}
