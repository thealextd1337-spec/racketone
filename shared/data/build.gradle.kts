// Lokale Datenhaltung (SQLDelight folgt), EventStore, Repositories, Outbox.
plugins {
    alias(libs.plugins.advantly.kmp.library)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.shared.coreScoring)
        }
    }
}
