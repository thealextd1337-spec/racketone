// Android-Phone-App. UI kommt aus shared/ui-phone (Compose Multiplatform).
plugins {
    alias(libs.plugins.advantly.android.compose)
}

android {
    namespace = "eu.advantly.android"
}

dependencies {
    implementation(projects.shared.uiPhone)
}
