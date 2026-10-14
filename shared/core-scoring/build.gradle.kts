// Scoring-Engine: Events, RuleSets, Reducer, Undo, Merge – pure Kotlin (kein Clock/IO).
plugins {
    alias(libs.plugins.advantly.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.core)
            api(projects.shared.coreModel)
        }
    }
}
