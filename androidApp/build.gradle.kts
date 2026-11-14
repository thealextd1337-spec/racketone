import java.util.Properties

// Android-Phone-App. UI kommt aus shared/ui-phone (Compose Multiplatform).
plugins {
    alias(libs.plugins.advantly.android.compose)
}

// Nur lokale/benutzerspezifische Properties; keine OAuth-Secrets im APK.
val authProperties =
    Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }

fun authValue(key: String): String = providers.gradleProperty(key).orNull ?: authProperties.getProperty(key, "")

android {
    namespace = "eu.advantly.android"
    testOptions.unitTests.isIncludeAndroidResources = true
    defaultConfig {
        mapOf(
            "SUPABASE_URL" to "advantly.supabase.url",
            "SUPABASE_KEY" to "advantly.supabase.key",
            "GOOGLE_WEB_CLIENT_ID" to "advantly.google.webClientId",
            // Push (Firebase-Projekt, Werte aus google-services.json); leer: Push bleibt aus.
            "FIREBASE_APP_ID" to "advantly.firebase.appId",
            "FIREBASE_API_KEY" to "advantly.firebase.apiKey",
            "FIREBASE_PROJECT_ID" to "advantly.firebase.projectId",
            "FIREBASE_SENDER_ID" to "advantly.firebase.senderId",
        ).forEach { (field, key) ->
            val value = authValue(key).replace("\\", "\\\\").replace("\"", "\\\"")
            buildConfigField("String", field, "\"$value\"")
        }
    }
}

dependencies {
    testImplementation(libs.robolectric)
    testImplementation(libs.ktor.mock)
    testImplementation(libs.sqldelight.sqlite.driver)
    implementation(projects.shared.uiPhone)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play)
    implementation(libs.google.id)
    // Live-Signale über privaten Realtime-Kanal (PhoneLiveSignals); nur hier, nicht in den Shared-Modulen
    implementation(libs.supabase.realtime)
    // Push ohne google-services-Plugin: Firebase wird manuell aus BuildConfig initialisiert (AndroidPush).
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    // firebase-messaging zieht fragment 1.2.x; ab 1.3.0 ist registerForActivityResult sicher (Lint, wie wearApp)
    implementation(libs.androidx.fragment)
    // WearableListenerService: Uhr-Events auch ohne laufende Activity (W1-I)
    implementation(libs.play.services.wearable)
    // Play-Store-Seite der Uhr-App auf einer Uhr ohne Advantly öffnen
    implementation(libs.wear.remote.interactions)
    // Nur Debug-APK: Link-Debug-Screen (Spike S3, src/debug). device-link kommt transitiv über ui-phone → presentation.
    debugImplementation(libs.compose.foundation)
    debugImplementation(libs.compose.material3)
}
