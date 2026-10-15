// Compose-Multiplatform-Screens für Android + iOS; jvm("desktop") nur für Previews auf Windows.
plugins {
    alias(libs.plugins.advantly.kmp.compose)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.shared.presentation)
            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.compose.ui.backhandler)
        }
        named("desktopMain") {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
            }
        }
        // Compose-UI-Tests laufen nur auf der JVM (Desktop) – Android-Host-Tests hätten keinen Renderer.
        named("desktopTest") {
            dependencies {
                implementation(libs.compose.ui.test)
            }
        }
    }
}

// Compose-Resources (Strings DE/EN, Schriften Barlow Condensed + Inter, OFL-Lizenzen unter files/licenses/):
// src/commonMain/composeResources/
compose.resources {
    packageOfResClass = "eu.advantly.ui.phone.resources"
    publicResClass = false
}

// Desktop-Preview: ./gradlew :shared:ui-phone:run
// Desktop-Latenzmessung (AWT-Klicks in die Fenster-Pipeline): ADVANTLY_BENCH=1 ./gradlew :shared:ui-phone:run
compose.desktop {
    application {
        mainClass = "eu.advantly.ui.phone.DesktopPreviewKt"
    }
}
