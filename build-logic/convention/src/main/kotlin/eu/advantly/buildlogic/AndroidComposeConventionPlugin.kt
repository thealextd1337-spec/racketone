package eu.advantly.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/** Android-App mit Jetpack Compose (Compose-Compiler-Plugin + Activity-Integration). */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("advantly.android.application")
            pluginManager.apply(libs.pluginId("kotlin-compose"))
            extensions.configure<ApplicationExtension> {
                buildFeatures {
                    compose = true
                }
            }
            dependencies {
                add("implementation", libs.library("androidx-activity-compose"))
            }
        }
}
