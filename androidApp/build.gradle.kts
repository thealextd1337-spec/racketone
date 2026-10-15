// Android-Phone-App. UI kommt aus shared/ui-phone (Compose Multiplatform).
plugins {
    alias(libs.plugins.advantly.android.compose)
}

android {
    namespace = "eu.advantly.android"
}

dependencies {
    implementation(projects.shared.uiPhone)
    // WearableListenerService: Uhr-Events auch ohne laufende Activity (W1-I)
    implementation(libs.play.services.wearable)
    // Nur Debug-APK: Link-Debug-Screen (Spike S3, src/debug). device-link kommt transitiv über ui-phone → presentation.
    debugImplementation(libs.compose.foundation)
    debugImplementation(libs.compose.material3)
}
