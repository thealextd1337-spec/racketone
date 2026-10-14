// Transport Uhr <-> Handy (Wear Data Layer / WatchConnectivity).
plugins {
    alias(libs.plugins.advantly.kmp.library)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.shared.syncProtocol)
        }
    }
}
