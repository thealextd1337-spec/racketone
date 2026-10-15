// Match-Session: lokaler Tap → Event → EventStore → Engine → Broadcast; Empfang → Store → Engine (Batches);
// Anti-Entropie, Fork-Erkennung/-Auflösung. Ohne Compose, Ktor und Koin (läuft auch im watchOS-Framework).
plugins {
    alias(libs.plugins.advantly.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.shared.coreScoring)
            api(projects.shared.syncProtocol)
            api(projects.shared.data)
            api(projects.shared.deviceLink)
            api(libs.kotlinx.coroutines.core)
        }
        // commonTest läuft auch als Android-Host-Test (JVM ohne Gerät) → JDBC-Treiber für die Test-Datenbank
        getByName("androidHostTest").dependencies {
            implementation(libs.sqldelight.sqlite.driver)
        }
    }
}
