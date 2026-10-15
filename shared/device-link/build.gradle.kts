// Transport Uhr <-> Handy: commonMain-Interface + zuverlässige Zustellung (Ack/Timeout/Retry/Dedup),
// androidMain = Wear Data Layer (Phone + Wear), appleMain = WatchConnectivity (iOS + watchOS, Kotlin-Interop).
// Nur Bytes/JSON transportieren – Abgleich-/Scoring-Logik gehört nach core-scoring/sync-protocol.
plugins {
    alias(libs.plugins.advantly.kmp.library)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.shared.syncProtocol)
        }
        androidMain.dependencies {
            implementation(libs.play.services.wearable)
        }
    }
}
