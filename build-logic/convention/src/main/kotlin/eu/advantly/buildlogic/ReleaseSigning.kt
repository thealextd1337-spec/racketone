package eu.advantly.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.GradleException
import org.gradle.api.Project
import java.io.File
import java.util.Properties

/**
 * Upload-Key für Google Play (Play App Signing). Phone und Wear werden mit **demselben** Schlüssel signiert.
 * Anleitung und Fingerabdrücke: `docs/release/play-console.md`.
 *
 * Quellen in dieser Reihenfolge (die erste vollständige gewinnt):
 * 1. Umgebungsvariablen [ENV_KEYSTORE], [ENV_STORE_PASSWORD], [ENV_KEY_ALIAS], [ENV_KEY_PASSWORD] (CI).
 * 2. Properties-Datei, deren Pfad in `local.properties` unter [LOCAL_PROPERTY] steht
 *    (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`; lokal, liegt **außerhalb** des Repos).
 *
 * Ohne Konfiguration bleibt `release` unsigniert (CI und `check` bauen weiter). Eine angefangene, aber
 * unvollständige Konfiguration bricht dagegen ab – ein still unsigniertes Bundle wäre die schlechtere Überraschung.
 * Gelesen wird über `providers`, damit der Configuration Cache Änderungen an Variablen und Dateien erkennt.
 */
internal data class ReleaseSigning(
    val storeFile: File,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
    val source: String,
)

internal const val ENV_KEYSTORE = "ADVANTLY_UPLOAD_KEYSTORE"
internal const val ENV_STORE_PASSWORD = "ADVANTLY_UPLOAD_STORE_PASSWORD"
internal const val ENV_KEY_ALIAS = "ADVANTLY_UPLOAD_KEY_ALIAS"
internal const val ENV_KEY_PASSWORD = "ADVANTLY_UPLOAD_KEY_PASSWORD"
internal const val LOCAL_PROPERTY = "advantly.signing.file"

internal fun Project.releaseSigning(): ReleaseSigning? = signingFromEnvironment() ?: signingFromLocalProperties()

/** Hängt die Signing-Config `release` an den Build-Typ `release`, falls ein Upload-Key konfiguriert ist. */
internal fun Project.configureReleaseSigning(android: ApplicationExtension) {
    val signing = releaseSigning() ?: return
    if (!signing.storeFile.isFile) {
        throw GradleException("$path: Upload-Keystore nicht gefunden: ${signing.storeFile} (Quelle: ${signing.source})")
    }
    val config =
        android.signingConfigs.create("release") {
            storeFile = signing.storeFile
            storePassword = signing.storePassword
            keyAlias = signing.keyAlias
            keyPassword = signing.keyPassword
        }
    android.buildTypes.getByName("release").signingConfig = config
    logger.info("$path: release wird mit dem Upload-Key signiert (Quelle: ${signing.source})")
}

private fun Project.signingFromEnvironment(): ReleaseSigning? {
    val values =
        listOf(ENV_KEYSTORE, ENV_STORE_PASSWORD, ENV_KEY_ALIAS, ENV_KEY_PASSWORD)
            .associateWith { providers.environmentVariable(it).orNull?.takeIf(String::isNotBlank) }
    if (values.values.all { it == null }) return null
    val missing = values.filterValues { it == null }.keys
    if (missing.isNotEmpty()) {
        throw GradleException("Release-Signierung unvollständig, es fehlen die Umgebungsvariablen: ${missing.joinToString()}")
    }
    return ReleaseSigning(
        storeFile = File(values.getValue(ENV_KEYSTORE)!!),
        storePassword = values.getValue(ENV_STORE_PASSWORD)!!,
        keyAlias = values.getValue(ENV_KEY_ALIAS)!!,
        keyPassword = values.getValue(ENV_KEY_PASSWORD)!!,
        source = "Umgebungsvariablen",
    )
}

private fun Project.signingFromLocalProperties(): ReleaseSigning? {
    val localProperties = readProperties(rootDir.resolve("local.properties")) ?: return null
    val path = localProperties.getProperty(LOCAL_PROPERTY)?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val file = File(path)
    val props =
        readProperties(file)
            ?: throw GradleException("$LOCAL_PROPERTY in local.properties zeigt auf eine fehlende Datei: $file")
    val keys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    val missing = keys.filter { props.getProperty(it).isNullOrBlank() }
    if (missing.isNotEmpty()) {
        throw GradleException("$file: Release-Signierung unvollständig, es fehlen: ${missing.joinToString()}")
    }
    val storeFile = File(props.getProperty("storeFile").trim()).let { if (it.isAbsolute) it else file.resolveSibling(it.path) }
    return ReleaseSigning(
        storeFile = storeFile,
        storePassword = props.getProperty("storePassword"),
        keyAlias = props.getProperty("keyAlias").trim(),
        keyPassword = props.getProperty("keyPassword"),
        source = file.path,
    )
}

/** Liest eine Properties-Datei über `providers.fileContents` (Configuration-Cache-Eingabe); `null`, wenn sie fehlt. */
private fun Project.readProperties(file: File): Properties? {
    val text =
        providers
            .fileContents(layout.projectDirectory.file(file.absolutePath))
            .asText
            .orNull ?: return null
    return Properties().apply { load(text.reader()) }
}
