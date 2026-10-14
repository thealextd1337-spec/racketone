// Wear-OS-App (Compose for Wear OS, Material 3). Logik aus shared/presentation.
plugins {
    alias(libs.plugins.advantly.android.compose)
}

android {
    namespace = "eu.advantly.wear"
    defaultConfig {
        // Wear OS 5+ (siehe 05-watches-health E7)
        minSdk =
            libs.versions.android.minSdk.wear
                .get()
                .toInt()
    }
}

dependencies {
    implementation(projects.shared.presentation)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.material3)
}
