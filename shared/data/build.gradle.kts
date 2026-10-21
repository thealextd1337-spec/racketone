// Lokale Datenhaltung: SQLDelight (System-SQLite auf Apple), EventStore, Repositories (Match-Kopf, Spieler,
// Favoriten-Lineups, Settings); später Outbox (P2).
// Treiber: android-driver (Android), sqlite-driver/JDBC (JVM-Tests, Desktop-Preview), native-driver (iOS + watchOS).
plugins {
    alias(libs.plugins.advantly.kmp.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.shared.coreScoring)
            api(libs.sqldelight.runtime)
            // JSON-Spalten (MatchFormat, Lineup, Event-Payloads) – gleiche Form wie auf dem Draht (sync-protocol)
            implementation(libs.kotlinx.serialization.json)
        }
        jvmMain.dependencies {
            implementation(libs.sqldelight.sqlite.driver)
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.android.driver)
            implementation(libs.androidx.sqlite.framework)
        }
        appleMain.dependencies {
            implementation(libs.sqldelight.native.driver)
        }
        // commonTest läuft auch als Android-Host-Test (JVM ohne Gerät) → dort JDBC-Treiber statt android-driver
        getByName("androidHostTest").dependencies {
            implementation(libs.sqldelight.sqlite.driver)
        }
    }
}

sqldelight {
    databases {
        create("AdvantlyDatabase") {
            packageName.set("eu.advantly.data.db")
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
            verifyMigrations.set(true)
        }
    }
}
