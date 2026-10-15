import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

// XCFramework "AdvantlyWatchKit" für die watchOS-App: nur Engine, Protokoll, lokaler Store, Transport.
// KEIN Ktor, supabase-kt, Compose oder Koin (Größenbudget < 8 MB pro Slice, siehe 01 §2.3).
// Abhängigkeiten an der Grenze: SQLDelight-native-driver (System-SQLite → App linkt -lsqlite3) und
// WatchConnectivity (→ App linkt -framework WatchConnectivity), siehe iosApp/project.yml.
// Größen je Slice misst die CI (.github/scripts/watch_metrics.py → Job-Summary).
// Bauen (nur macOS): ./gradlew :shared:umbrella-watch:assembleAdvantlyWatchKitDebugXCFramework
//
// Bewusst OHNE SKIE (Abweichung von 01 §0 E3): SKIE 0.10.15 bricht beim Fat-Framework aus
// watchosArm64 + watchosDeviceArm64 ab (fehlendes AdvantlyWatchKit-Swift.h, CI-Lauf 38644146968).
// Die Swift-Grenze bleibt deshalb schlicht: nur String/Int/Long/Boolean, einfache Klassen/Objekte und
// Listen – keine Flows, suspend-Funktionen, sealed-Hierarchien oder Generics in der öffentlichen API.
plugins {
    alias(libs.plugins.advantly.kmp.base)
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
