package eu.advantly.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Compose-Multiplatform-UI-Modul (Phone): `android`, `iosArm64`, `iosSimulatorArm64` und
 * `jvm("desktop")` – Letzteres nur für Previews und JVM-Tests auf Windows, kein Desktop-Produkt.
 * UI-Strings über Compose-Multiplatform-Resources: `src/commonMain/composeResources/values/` (EN, Default)
 * und `values-de/` (DE).
 */
class KmpComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("advantly.kmp.base")
            pluginManager.apply(libs.pluginId("compose-multiplatform"))
            pluginManager.apply(libs.pluginId("kotlin-compose"))
            extensions.configure<KotlinMultiplatformExtension> {
                jvm("desktop")
                configureKmpAndroidTarget(this, enableAndroidResources = true)
                iosTargets()
                sourceSets.getByName("commonMain").dependencies {
                    implementation(libs.library("compose-runtime"))
                    implementation(libs.library("compose-foundation"))
                    implementation(libs.library("compose-ui"))
                    implementation(libs.library("compose-material3"))
                    implementation(libs.library("compose-components-resources"))
                }
            }
        }
}
