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
    defaultConfig {
        mapOf(
            "SUPABASE_URL" to "advantly.supabase.url",
            "SUPABASE_KEY" to "advantly.supabase.key",
            "GOOGLE_WEB_CLIENT_ID" to "advantly.google.webClientId",
        ).forEach { (field, key) ->
            val value = authValue(key).replace("\\", "\\\\").replace("\"", "\\\"")
            buildConfigField("String", field, "\"$value\"")
        }
    }
}

dependencies {
    implementation(projects.shared.uiPhone)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play)
    implementation(libs.google.id)
    // WearableListenerService: Uhr-Events auch ohne laufende Activity (W1-I)
    implementation(libs.play.services.wearable)
    // Nur Debug-APK: Link-Debug-Screen (Spike S3, src/debug). device-link kommt transitiv über ui-phone → presentation.
    debugImplementation(libs.compose.foundation)
    debugImplementation(libs.compose.material3)
}
