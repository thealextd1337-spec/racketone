package eu.advantly.buildlogic

import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jlleitschuh.gradle.ktlint.KtlintExtension
import java.io.File

/** ktlint (Linter + Formatter) für jedes Modul: `ktlintCheck` hängt an `check`, `ktlintFormat` formatiert. */
internal fun Project.configureKtlint() {
    pluginManager.apply(libs.pluginId("ktlint"))
    extensions.configure<KtlintExtension> {
        version.set(libs.version("ktlint"))
        filter {
            exclude { element -> element.file.path.contains("${File.separator}build${File.separator}") }
        }
    }
}
