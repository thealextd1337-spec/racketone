// Compose-Multiplatform-Screens für Android + iOS; jvm("desktop") nur für Previews auf Windows.
plugins {
    alias(libs.plugins.advantly.kmp.compose)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.shared.presentation)
        }
        named("desktopMain") {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
            }
        }
    }
}

// Desktop-Preview: ./gradlew :shared:ui-phone:run
compose.desktop {
    application {
        mainClass = "eu.advantly.ui.phone.DesktopPreviewKt"
    }
}
