// KMP-ViewModels und UI-State (Phone + Wear).
plugins {
    alias(libs.plugins.advantly.kmp.library)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.shared.data)
            api(projects.shared.deviceLink)
            api(projects.shared.entitlements)
            // KMP-ViewModel + SavedStateHandle (überlebt Rotation und Prozesstod)
            api(libs.lifecycle.viewmodel)
            api(libs.lifecycle.viewmodel.savedstate)
        }
    }
}
