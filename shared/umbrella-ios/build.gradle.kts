import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

// XCFramework "AdvantlyKit" für die iOS-App: ui-phone (+ später cloud-sync, device-link-garmin).
// Bauen (nur macOS): ./gradlew :shared:umbrella-ios:assembleAdvantlyKitDebugXCFramework
// Ausgabe: shared/umbrella-ios/build/XCFrameworks/<debug|release>/AdvantlyKit.xcframework
plugins {
    alias(libs.plugins.advantly.kmp.base)
    // Compose-Plugin nur, damit die Compose-Resources aus ui-phone ins XCFramework kommen
    // (AdvantlyKit.framework/composeResources). Die iOS-App kopiert sie beim Build ins App-Bundle,
    // weil das statische Framework nicht eingebettet wird (siehe iosApp/project.yml).
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.skie)
}

kotlin {
    // jvm() nur, damit der Platzhalter-Test auf Windows/Linux läuft
    jvm()

    val xcf = XCFramework("AdvantlyKit")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "AdvantlyKit"
            isStatic = true
            binaryOption("bundleId", "eu.advantly.kit")
            xcf.add(this)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(projects.shared.uiPhone)
            // Compose-Compiler (für die Resource-Einbettung angewendet) verlangt die Runtime im Klassenpfad
            implementation(libs.compose.runtime)
        }
    }
}
