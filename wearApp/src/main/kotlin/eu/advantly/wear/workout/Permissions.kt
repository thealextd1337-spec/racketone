package eu.advantly.wear.workout

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.health.connect.HealthPermissions
import android.os.Build
import androidx.core.content.ContextCompat

/** Laufzeitrechte der Wear-App (05 §2.2, §6.2). */
object Permissions {
    /** Wear OS 6 (API 36): `health.READ_HEART_RATE` statt `BODY_SENSORS`. */
    val heartRate: String
        get() = if (usesHealthPermission) HealthPermissions.READ_HEART_RATE else Manifest.permission.BODY_SENSORS

    private val usesHealthPermission: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA

    fun required(): List<String> = listOf(heartRate, Manifest.permission.ACTIVITY_RECOGNITION, Manifest.permission.POST_NOTIFICATIONS)

    fun missing(context: Context): List<String> = required().filterNot { granted(context, it) }

    fun current(context: Context): WorkoutPermissions =
        WorkoutPermissions(
            heartRate = granted(context, heartRate),
            activityRecognition = granted(context, Manifest.permission.ACTIVITY_RECOGNITION),
            notifications = granted(context, Manifest.permission.POST_NOTIFICATIONS),
        )

    private fun granted(
        context: Context,
        permission: String,
    ): Boolean = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
