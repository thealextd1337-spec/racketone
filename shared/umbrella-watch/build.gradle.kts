import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

// XCFramework "AdvantlyWatchKit" für die watchOS-App: nur Engine, Protokoll, lokaler Store, Transport.
// KEIN Ktor, supabase-kt, Compose oder Koin (Größenbudget < 8 MB pro Slice, siehe 01 §2.3).
// Bauen (nur macOS): ./gradlew :shared:umbrella-watch:assembleAdvantlyWatchKitDebugXCFramework
plugins {
    alias(libs.plugins.advantly.kmp.base)
    alias(libs.plugins.skie)
}

kotlin {
    // jvm() nur, damit der Platzhalter-Test auf Windows/Linux läuft
    jvm()

    val xcf = XCFramework("AdvantlyWatchKit")
    // arm64_32 (watchosArm64) + arm64 (watchosDeviceArm64) landen als Fat-Slice im XCFramework
    listOf(watchosArm64(), watchosDeviceArm64(), watchosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "AdvantlyWatchKit"
            isStatic = true
            binaryOption("bundleId", "eu.advantly.watchkit")
            xcf.add(this)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(projects.shared.data)
            implementation(projects.shared.deviceLink)
        }
    }
}
