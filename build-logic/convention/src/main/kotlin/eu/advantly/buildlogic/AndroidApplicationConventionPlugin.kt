package eu.advantly.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * Android-App (Phone oder Wear). AGP 9 bringt Kotlin-Unterstützung mit (built-in Kotlin),
 * `org.jetbrains.kotlin.android` wird deshalb nicht angewendet.
 * `applicationId` ist für Phone UND Wear `eu.advantly` (Pflicht für die Wear Data Layer API).
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply(libs.pluginId("android-application"))
            configureKtlint()

            val javaVersion = JavaVersion.toVersion(libs.version("jvmTarget"))
            extensions.configure<ApplicationExtension> {
                compileSdk = libs.version("android-compileSdk").toInt()
                defaultConfig {
                    applicationId = "eu.advantly"
                    minSdk = libs.version("android-minSdk").toInt()
                    targetSdk = libs.version("android-targetSdk").toInt()
                    versionName = providers.gradleProperty("advantly.versionName").get()
                    versionCode = providers.gradleProperty("advantly.versionCode").get().toInt()
                }
                compileOptions {
                    sourceCompatibility = javaVersion
                    targetCompatibility = javaVersion
                }
                buildFeatures {
                    buildConfig = true
                }
            }
            dependencies {
                add("testImplementation", libs.library("junit4"))
            }
        }
}
