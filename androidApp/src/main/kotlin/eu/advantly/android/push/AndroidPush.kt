package eu.advantly.android.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import eu.advantly.android.AdvantlyPhoneApplication
import eu.advantly.android.BuildConfig
import eu.advantly.android.MainActivity
import eu.advantly.android.R
import eu.advantly.android.sync.AndroidPhoneSyncRecord
import eu.advantly.android.sync.AndroidPhoneSyncScheduler
import eu.advantly.cloud.sync.pulledFriendships
import eu.advantly.core.model.PlayerId
import eu.advantly.data.AndroidInstallationGuard
import eu.advantly.match.session.AndroidMatchSessions
import eu.advantly.presentation.push.FriendsSection
import eu.advantly.presentation.push.PushChannel
import eu.advantly.presentation.push.PushKind
import eu.advantly.presentation.push.PushMessage
import eu.advantly.presentation.push.PushPreferences
import eu.advantly.presentation.push.PushTarget
import eu.advantly.presentation.push.PushTokenSource
import eu.advantly.sync.protocol.PeerKind
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Push (Plan Live §3.3, PR 7). Firebase ohne google-services-Plugin und ohne Analytics: Werte kommen aus
 * `local.properties` → BuildConfig. Fehlt einer, bleibt Push vollständig aus (CI, Builds ohne Firebase).
 */
internal object AndroidPush {
    val configured: Boolean
        get() =
            BuildConfig.SUPABASE_URL.isNotBlank() &&
                listOf(
                    BuildConfig.FIREBASE_APP_ID,
                    BuildConfig.FIREBASE_API_KEY,
                    BuildConfig.FIREBASE_PROJECT_ID,
                    BuildConfig.FIREBASE_SENDER_ID,
                ).all { it.isNotBlank() }

    /** Application.onCreate: ohne Netz und ohne Token (Auto-Init ist im Manifest aus). */
    fun initialize(context: Context) {
        if (!configured || FirebaseApp.getApps(context).isNotEmpty()) return
        FirebaseApp.initializeApp(
            context,
            FirebaseOptions
                .Builder()
                .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                .setApiKey(BuildConfig.FIREBASE_API_KEY)
                .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                .build(),
        )
    }
}

/** Nicht im Backup (`backup_rules`): Zustimmung und Token gehören zu diesem Gerät. */
internal class AndroidPushPreferences(
    context: Context,
) : PushPreferences {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    override var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) = prefs.edit().putBoolean("enabled", value).apply()
    override var hintDone: Boolean
        get() = prefs.getBoolean("hint_done", false)
        set(value) = prefs.edit().putBoolean("hint_done", value).apply()
    override var uploaded: String?
        get() = prefs.getString("uploaded", null)
        set(value) = prefs.edit().putString("uploaded", value).apply()

    companion object {
        const val FILE = "advantly_push"
    }
}

/** Auto-Init folgt der Zustimmung: an mit dem ersten Token (Erneuerung über onNewToken), aus beim Löschen. */
internal object FirebaseTokens : PushTokenSource {
    override suspend fun token(): String {
        val messaging = FirebaseMessaging.getInstance()
        messaging.isAutoInitEnabled = true
        return messaging.token.await()
    }

    override suspend fun delete() {
        val messaging = FirebaseMessaging.getInstance()
        messaging.isAutoInitEnabled = false
        messaging.deleteToken().await()
    }
}

private suspend fun <T> Task<T>.await(): T =
    suspendCancellableCoroutine { continuation ->
        addOnCompleteListener { task ->
            val error = task.exception
            if (error != null) continuation.resumeWithException(error) else continuation.resume(task.result)
        }
    }

internal const val EXTRA_PUSH_SECTION = "eu.advantly.push.section"
internal const val EXTRA_PUSH_MATCH = "eu.advantly.push.match"
private val UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

/** MainActivity ist exportiert: Extras fremder Absender nur als bekannte Abschnitte und UUIDs übernehmen. */
internal fun Intent.pushTarget(): PushTarget? {
    val section = FriendsSection.entries.firstOrNull { it.name == getStringExtra(EXTRA_PUSH_SECTION) } ?: return null
    return PushTarget(section, getStringExtra(EXTRA_PUSH_MATCH)?.takeIf(UUID::matches))
}

internal fun PushChannel.id() = "push_" + name.lowercase()

internal fun ensurePushChannels(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)
    for ((channel, name, description) in listOf(
        Triple(PushChannel.FRIENDS, R.string.push_channel_friends, R.string.push_channel_friends_description),
        Triple(PushChannel.MATCHES, R.string.push_channel_matches, R.string.push_channel_matches_description),
        Triple(PushChannel.SCORING, R.string.push_channel_scoring, R.string.push_channel_scoring_description),
    )) {
        // Zählen ist zeitkritisch (Übergabe am Platz): als Heads-up.
        val importance = if (channel == PushChannel.SCORING) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT
        manager.createNotificationChannel(
            NotificationChannel(channel.id(), context.getString(name), importance).apply {
                this.description = context.getString(description)
            },
        )
    }
}

/** Lokaler Text: mit Alias aus dem Freundes-Cache (bei [PushKind.PLAYER_CLAIMED] zusätzlich Gastname), sonst neutral. */
internal fun pushText(
    context: Context,
    message: PushMessage,
    name: String?,
    guest: String? = null,
): String {
    val (named, neutral) =
        when (message.kind) {
            PushKind.FRIEND_REQUEST -> R.string.push_friend_request to R.string.push_friend_request_neutral
            PushKind.FRIEND_ACCEPTED -> R.string.push_friend_accepted to R.string.push_friend_accepted_neutral
            PushKind.PLAYER_ASSIGNED -> R.string.push_player_assigned to R.string.push_player_assigned_neutral
            PushKind.MATCH_SHARED -> R.string.push_match_shared to R.string.push_match_shared_neutral
            PushKind.VISITOR_INVITED -> R.string.push_visitor_invited to R.string.push_visitor_invited_neutral
            PushKind.SCORER_INVITED -> R.string.push_scorer_invited to R.string.push_scorer_invited_neutral
            PushKind.SCORER_RESPONDED -> R.string.push_scorer_responded to R.string.push_scorer_responded_neutral
            PushKind.SCORER_CHANGED -> R.string.push_scorer_changed to R.string.push_scorer_changed_neutral
            PushKind.PLAYER_CLAIMED -> R.string.push_player_claimed to R.string.push_player_claimed_neutral
        }
    val args = (if (message.kind == PushKind.PLAYER_CLAIMED) listOf(name, guest) else listOf(name)).map { it?.trim().orEmpty() }
    return if (args.all { it.isNotEmpty() }) context.getString(named, *args.toTypedArray()) else context.getString(neutral)
}

/**
 * Zeigt die Benachrichtigung (nicht `localOnly`: die Wear-Uhr spiegelt sie). Gleiche Art, gleiches Match und gleicher
 * Auslöser ersetzen die vorige statt sie zu stapeln.
 */
internal fun showPush(
    context: Context,
    message: PushMessage,
    name: String?,
    guest: String? = null,
) {
    if (Build.VERSION.SDK_INT >= 33 &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
        return
    }
    ensurePushChannels(context)
    val id = listOf(message.kind.wire, message.matchId, message.profileId).hashCode()
    val target = message.target
    val open =
        Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_PUSH_SECTION, target.section.name)
            .putExtra(EXTRA_PUSH_MATCH, target.matchId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    val notification =
        android.app.Notification
            .Builder(context, message.kind.channel.id())
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(pushText(context, message, name, guest))
            .setContentText(context.getString(R.string.push_open))
            .setAutoCancel(true)
            .setCategory(android.app.Notification.CATEGORY_SOCIAL)
            .setContentIntent(
                PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            ).build()
    context.getSystemService(NotificationManager::class.java).notify(id, notification)
}

/**
 * Main-Thread nach der Installationsprüfung: Alias des Auslösers aus dem lokalen Freundes-Cache, Benachrichtigung,
 * danach ein Abgleich (neue Anfrage, Match, Rolle). Ausgeschaltet: verspätete Nachrichten an ein altes Token verwerfen.
 */
internal fun deliverPush(
    context: Context,
    message: PushMessage,
) {
    val app = context.applicationContext
    if (!AndroidPushPreferences(app).enabled) return
    val local = localAccount(app)
    val name =
        message.profileId?.let { actor ->
            local?.let { (_, friends) -> friends[actor] }
        }
    showPush(app, message, name, message.playerId?.let { guestName(app, it) })
    local?.first?.let { syncSoon(app, it) }
}

/** Neues Token: der nächste Abgleich lädt es hoch (PushRegistration.sync nach jedem Lauf). */
internal fun requestPushSync(context: Context) {
    val app = context.applicationContext
    localAccount(app)?.first?.let { syncSoon(app, it) }
}

/**
 * Abgleich zeitnah als Expedited Job (läuft auch im Doze; ein Push öffnet dafür ein kurzes Fenster), ein aktiver
 * Backoff bleibt bestehen (offline, gestört: kein Hämmern).
 */
internal fun syncSoon(
    app: Context,
    profile: String,
) = AndroidPhoneSyncScheduler(app).scheduleUnlessBackingOff(profile, AndroidPhoneSyncRecord(app), 0)

/** Name des eigenen Gasts [playerId] aus der lokalen Datenbank (der Push trägt nur IDs); `null` wenn unbekannt. */
private fun guestName(
    app: Context,
    playerId: String,
): String? {
    if ((app as? AdvantlyPhoneApplication)?.bootstrap() != true) return null
    return AndroidInstallationGuard
        .storage(app) {
            AndroidMatchSessions
                .get(app, PeerKind.PHONE)
                .data.players
                .get(PlayerId(playerId))
                ?.displayName
                .orEmpty()
        }?.takeIf { it.isNotBlank() }
}

/** Lokales Konto und Aliase der Freunde; `null` ohne geprüfte Installation oder ohne Konto. */
private fun localAccount(app: Context): Pair<String, Map<String, String?>>? {
    if (BuildConfig.SUPABASE_URL.isBlank() || (app as? AdvantlyPhoneApplication)?.bootstrap() != true) return null
    return AndroidInstallationGuard
        .storage(app) {
            val data = AndroidMatchSessions.get(app, PeerKind.PHONE).data
            val profile = data.accountIdentities.profile.orEmpty()
            profile to
                if (profile.isEmpty()) emptyMap() else data.cloudPulls.pulledFriendships(profile).associate { it.profileId to it.alias }
        }?.takeIf { it.first.isNotEmpty() }
}
