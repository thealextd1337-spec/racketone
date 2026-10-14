package eu.advantly.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * Formfaktor einer Android-App, gesetzt in `<app>/gradle.properties` über `advantly.app.formFactor=phone|wear`.
 *
 * Google Play verlangt eindeutige versionCodes über ALLE Formfaktoren einer App
 * (https://developer.android.com/training/wearables/packaging#unique-version-code). Deshalb:
 * `versionCode = advantly.buildNumber * 10 + versionCodeDigit` (Phone …1, Wear …2).
 */
internal enum class FormFactor(
    val versionCodeDigit: Int,
    val minSdkAlias: String,
) {
    PHONE(versionCodeDigit = 1, minSdkAlias = "android-minSdk"),
    WEAR(versionCodeDigit = 2, minSdkAlias = "android-minSdk-wear"),
}

/**
 * Android-App (Phone oder Wear). AGP 9 bringt Kotlin-Unterstützung mit (built-in Kotlin),
 * `org.jetbrains.kotlin.android` wird deshalb nicht angewendet.
 * `applicationId` ist für Phone UND Wear `eu.advantly`, der Signierschlüssel ebenfalls identisch
 * (Pflicht für die Wear Data Layer API). Version kommt aus `advantly.versionName` / `advantly.buildNumber`
 * im Root-`gradle.properties` (einzige Versionsquelle, auch für Apple).
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply(libs.pluginId("android-application"))
            configureKtlint()

            val formFactor = formFactor()
            val buildNumber = providers.gradleProperty("advantly.buildNumber").get().toInt()
            require(buildNumber in 1..200_000_000) { "advantly.buildNumber muss zwischen 1 und 200000000 liegen" }

            val javaVersion = JavaVersion.toVersion(libs.version("jvmTarget"))
            extensions.configure<ApplicationExtension> {
                compileSdk = libs.version("android-compileSdk").toInt()
                defaultConfig {
                    applicationId = "eu.advantly"
                    minSdk = libs.version(formFactor.minSdkAlias).toInt()
                    targetSdk = libs.version("android-targetSdk").toInt()
                    versionName = providers.gradleProperty("advantly.versionName").get()
                    versionCode = buildNumber * 10 + formFactor.versionCodeDigit
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

    private fun Project.formFactor(): FormFactor {
        val value =
            findProperty("advantly.app.formFactor") as String?
                ?: throw GradleException("$path: advantly.app.formFactor=phone|wear in gradle.properties des Moduls setzen")
        return FormFactor.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
            ?: throw GradleException("$path: unbekannter Formfaktor '$value' (erlaubt: phone, wear)")
    }
}
