// Auth-Präsentation nur für das Handy; Wear nutzt weiterhin die cloudfreie presentation.
plugins {
    alias(libs.plugins.advantly.kmp.library)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.shared.cloudSync)
            api(libs.lifecycle.viewmodel)
        }
    }
}
