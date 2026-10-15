package eu.advantly.buildlogic

import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

/**
 * System-SQLite für alle Kotlin/Native-Binaries (Test-Executables, Frameworks) der Apple-Targets.
 *
 * Hintergrund (Spike S1): Der SQLDelight-native-driver nutzt sqliter, dessen cinterop-klib KEIN `-lsqlite3`
 * mitbringt. Das SQLDelight-Gradle-Plugin ergänzt die Option nur in dem Modul, in dem es angewendet wird
 * (`:shared:data`). Alle Module, die `data` transitiv linken (`presentation`, `ui-phone`, `umbrella-*`),
 * bräuchten sie sonst einzeln. Für statische Frameworks wirkt die Option nicht – dort setzt
 * `iosApp/project.yml` `-lsqlite3` in `OTHER_LDFLAGS`.
 */
internal fun KotlinMultiplatformExtension.linkSystemSqlite() {
    targets.withType<KotlinNativeTarget>().configureEach {
        binaries.configureEach {
            linkerOpts("-lsqlite3")
        }
    }
}
