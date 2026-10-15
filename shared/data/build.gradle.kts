// Lokale Datenhaltung: SQLDelight (System-SQLite auf Apple), EventStore; später Repositories, Outbox.
// Treiber: android-driver (Android), sqlite-driver/JDBC (JVM-Tests, Desktop-Preview), native-driver (iOS + watchOS).
plugins {
    alias(libs.plugins.advantly.kmp.library)
    alias(libs.plugins.sqldelight)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.shared.coreScoring)
            api(libs.sqldelight.runtime)
        }
        jvmMain.dependencies {
            implementation(libs.sqldelight.sqlite.driver)
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.android.driver)
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
        }
    }
}
