import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

// XCFramework "AdvantlyKit" für die iOS-App: ui-phone (+ später cloud-sync, device-link-garmin).
// Bauen (nur macOS): ./gradlew :shared:umbrella-ios:assembleAdvantlyKitDebugXCFramework
// Ausgabe: shared/umbrella-ios/build/XCFrameworks/<debug|release>/AdvantlyKit.xcframework
plugins {
    alias(libs.plugins.advantly.kmp.base)
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
        }
    }
}
