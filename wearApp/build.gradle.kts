// Wear-OS-App (Compose for Wear OS, Material 3). Logik aus shared/presentation.
plugins {
    alias(libs.plugins.advantly.android.compose)
}

android {
    namespace = "eu.advantly.wear"
}

dependencies {
    implementation(projects.shared.presentation)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.material3)
    // Spike S5 (Workout-Laufzeit): ExerciseClient, Ambient, Ongoing Activity
    implementation(libs.health.services.client)
    implementation(libs.wear.ambient)
    implementation(libs.wear.ongoing)
    implementation(libs.androidx.fragment) // hebt das transitive fragment 1.2.4 aus androidx.wear:wear an
}
