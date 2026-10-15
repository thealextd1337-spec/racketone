// Wire-DTOs, JSON-Codec, Anti-Entropy, Fork-Erkennung – pure Kotlin (kein Clock/IO).
// Ausnahme zur Pure-Regel: kotlinx-serialization-json für den Wire-Codec (01 §4.3 „JSON via kotlinx-serialization“);
// die Bibliothek ist rein (kein I/O, keine Coroutines) und auf allen Targets inkl. watchOS verfügbar.
plugins {
    alias(libs.plugins.advantly.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.core)
            api(libs.kotlinx.serialization.json)
            api(projects.shared.coreScoring)
        }
        // Property-Tests (Kotest Property) nur auf der JVM – in commonMain bleibt Kotest tabu
        jvmTest.dependencies {
            implementation(libs.kotest.property)
        }
    }
}
