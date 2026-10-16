// Root-Build: Plugins nur auf den Klassenpfad legen, angewendet werden sie in den Modulen
// (direkt oder über die Convention Plugins aus build-logic/).
plugins {
    id("advantly.build.info")
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

// Play-Release (docs/release/play-console.md): Phone- und Wear-AAB in einem Lauf. Signiert wird mit dem Upload-Key,
// sofern konfiguriert (Umgebungsvariablen oder advantly.signing.file in local.properties), sonst unsigniert.
tasks.register("bundleAllRelease") {
    group = "build"
    description = "Baut die Release-AABs von androidApp (versionCode …1) und wearApp (…2)."
    dependsOn(":androidApp:bundleRelease", ":wearApp:bundleRelease")
}
