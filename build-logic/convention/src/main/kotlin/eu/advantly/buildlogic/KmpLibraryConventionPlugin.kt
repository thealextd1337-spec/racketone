package eu.advantly.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Standard-Bibliotheksmodul unter `shared/`. Targets laut 01 §2.3:
 * `jvm`, `android`, `iosArm64`, `iosSimulatorArm64`, `watchosArm64`, `watchosDeviceArm64`, `watchosSimulatorArm64`.
 *
 * Modul-Flag (in `<modul>/gradle.properties`): `advantly.kmp.watchos=false` lässt die watchOS-Targets weg.
 */
class KmpLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("advantly.kmp.base")
            extensions.configure<KotlinMultiplatformExtension> {
                jvm()
                configureKmpAndroidTarget(this)
                iosTargets()
                if (flag("advantly.kmp.watchos", default = true)) {
                    watchosTargets()
                }
            }
        }
}
