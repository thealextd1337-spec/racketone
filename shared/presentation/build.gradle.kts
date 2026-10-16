// KMP-ViewModels und UI-State (Phone + Wear) sowie der Session-Adapter für die Phone-UI (SessionMatchStore).
plugins {
    alias(libs.plugins.advantly.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.shared.data)
            api(projects.shared.deviceLink)
            // Match-Session (Single-Scorer, Persistenz, Sync) hinter den Ports
            api(projects.shared.matchSession)
            api(projects.shared.entitlements)
            api(projects.shared.cloudSync)
            // KMP-ViewModel + SavedStateHandle (überlebt Rotation und Prozesstod)
            api(libs.lifecycle.viewmodel)
            api(libs.lifecycle.viewmodel.savedstate)
            // StateFlow in den Port-Schnittstellen (LiveMatchPort, MatchHistoryPort, PlayersPort)
            api(libs.kotlinx.coroutines.core)
            // Favoriten (Format + Besetzung) als JSON in den Settings
            implementation(libs.kotlinx.serialization.json)
        }
        // Adapter-Tests: Datenbank als Datei (Neustart-Test) über JDBC
        jvmTest.dependencies {
            implementation(libs.sqldelight.sqlite.driver)
        }
    }
}
