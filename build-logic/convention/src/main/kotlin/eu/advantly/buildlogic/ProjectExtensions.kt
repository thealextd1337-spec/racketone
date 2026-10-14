package eu.advantly.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.version(alias: String): String = findVersion(alias).get().requiredVersion

internal fun VersionCatalog.library(alias: String): Provider<MinimalExternalModuleDependency> = findLibrary(alias).get()

internal fun VersionCatalog.pluginId(alias: String): String =
    findPlugin(alias)
        .get()
        .get()
        .pluginId

/**
 * Android-Namespace / Kotlin-Paket eines Moduls: `eu.advantly.` + Modulname mit `-` → `.`
 * (z. B. `:shared:core-scoring` → `eu.advantly.core.scoring`).
 */
internal val Project.advantlyNamespace: String
    get() = "eu.advantly." + name.replace('-', '.')

/** Boolesches Modul-Flag aus `gradle.properties` des Moduls (z. B. `advantly.kmp.watchos=false`). */
internal fun Project.flag(
    name: String,
    default: Boolean,
): Boolean = (findProperty(name) as String?)?.toBooleanStrictOrNull() ?: default
