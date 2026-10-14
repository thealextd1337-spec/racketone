// IDs, Sportarten, Spieler, Teams, Lineups – pure Kotlin (kein Clock/IO).
plugins {
    alias(libs.plugins.advantly.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.core)
        }
    }
}
