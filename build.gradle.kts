// Root-Build: Plugins nur auf den Klassenpfad legen, angewendet werden sie in den Modulen
// (direkt oder über die Convention Plugins aus build-logic/).
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.skie) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.sqldelight) apply false
}

// Apple-Version aus derselben Quelle wie Android (gradle.properties) ableiten.
// Erzeugt iosApp/Config/Version.xcconfig (nicht eingecheckt), das XcodeGen für iOS + watchOS einbindet.
// CI (macOS) ruft den Task vor `xcodegen generate` auf.
tasks.register("generateAppleVersionConfig") {
    group = "build setup"
    description = "Erzeugt iosApp/Config/Version.xcconfig aus advantly.versionName/advantly.buildNumber."
    val versionName = providers.gradleProperty("advantly.versionName")
    val buildNumber = providers.gradleProperty("advantly.buildNumber")
    val output = layout.projectDirectory.file("iosApp/Config/Version.xcconfig")
    inputs.property("versionName", versionName)
    inputs.property("buildNumber", buildNumber)
    outputs.file(output)
    doLast {
        val name = versionName.get()
        val build = buildNumber.get()
        require(Regex("""\d+\.\d+\.\d+""").matches(name)) { "advantly.versionName muss x.y.z sein (Apple), ist '$name'" }
        require(Regex("""[1-9]\d*""").matches(build)) { "advantly.buildNumber muss eine positive Ganzzahl sein, ist '$build'" }
        output.asFile.apply {
            parentFile.mkdirs()
            writeText(
                """
                |// Generiert von `./gradlew generateAppleVersionConfig` aus gradle.properties – nicht von Hand ändern.
                |MARKETING_VERSION = $name
                |CURRENT_PROJECT_VERSION = $build
                |
                """.trimMargin(),
            )
        }
    }
}

// Play-Release (docs/release/play-console.md): Phone- und Wear-AAB in einem Lauf. Signiert wird mit dem Upload-Key,
// sofern konfiguriert (Umgebungsvariablen oder advantly.signing.file in local.properties), sonst unsigniert.
tasks.register("bundleAllRelease") {
    group = "build"
    description = "Baut die Release-AABs von androidApp (versionCode …1) und wearApp (…2)."
    dependsOn(":androidApp:bundleRelease", ":wearApp:bundleRelease")
}
