package eu.advantly.buildlogic

import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Android-Target über das AGP-Plugin `com.android.kotlin.multiplatform.library`.
 * [enableAndroidResources] = true ist für Compose-Multiplatform-Resources nötig (Default im AGP-KMP-Plugin: aus).
 */
internal fun Project.configureKmpAndroidTarget(
    kotlin: KotlinMultiplatformExtension,
    enableAndroidResources: Boolean = false,
) {
    pluginManager.apply(libs.pluginId("android-kmp-library"))
    (kotlin as ExtensionAware).extensions.configure<KotlinMultiplatformAndroidLibraryTarget>("android") {
        namespace = advantlyNamespace
        compileSdk = libs.version("android-compileSdk").toInt()
        minSdk = libs.version("android-minSdk").toInt()
        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget(libs.version("jvmTarget")))
        }
        // commonTest läuft zusätzlich als Android-Host-Test (JVM, ohne Gerät)
        withHostTest {}
        androidResources.enable = enableAndroidResources
    }
}

internal fun KotlinMultiplatformExtension.iosTargets() {
    iosArm64()
    iosSimulatorArm64()
}

/**
 * watchOS: `watchosArm64` (arm64_32, ältere Uhren) + `watchosDeviceArm64` (arm64, Series 9+/Ultra 2+, Tier 3)
 * + Simulator. `watchosX64`/`watchosArm32` sind deprecated und werden bewusst nicht genutzt.
 */
internal fun KotlinMultiplatformExtension.watchosTargets() {
    watchosArm64()
    watchosDeviceArm64()
    watchosSimulatorArm64()
}
