package eu.advantly.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Basis für alle KMP-Module: Kotlin Multiplatform + ktlint + `kotlin.test` in `commonTest`.
 * Targets legt das Modul (oder ein darauf aufbauendes Convention Plugin) selbst fest.
 */
class KmpBaseConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply(libs.pluginId("kotlin-multiplatform"))
            configureKtlint()

            extensions.configure<KotlinMultiplatformExtension> {
                jvmToolchain(libs.version("java").toInt())
                compilerOptions {
                    freeCompilerArgs.add("-Xexpect-actual-classes")
                }
                sourceSets.getByName("commonTest").dependencies {
                    implementation(kotlin("test"))
                }
                linkSystemSqlite()
            }
            configureAppleSimulatorTests()
        }
}
