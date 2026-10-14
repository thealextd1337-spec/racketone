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
}
