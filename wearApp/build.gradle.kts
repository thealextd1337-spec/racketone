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
}
