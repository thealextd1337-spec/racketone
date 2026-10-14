package eu.advantly.buildlogic

import org.gradle.api.Project
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

/**
 * Simulator für Kotlin/Native-Tests (nur macOS). Die Standard-Gerätenamen von KGP passen nicht immer
 * zum installierten Xcode, deshalb übergibt die CI eine Simulator-UDID bzw. einen Namen:
 * `-Padvantly.simulator.ios=<udid>` und `-Padvantly.simulator.watchos=<udid>`.
 */
internal fun Project.configureAppleSimulatorTests() {
    val iosDevice = providers.gradleProperty("advantly.simulator.ios")
    val watchosDevice = providers.gradleProperty("advantly.simulator.watchos")
    tasks.withType<KotlinNativeSimulatorTest>().configureEach {
        val device =
            when {
                name.startsWith("ios") -> iosDevice
                name.startsWith("watchos") -> watchosDevice
                else -> null
            }
        if (device != null && device.isPresent) {
            this.device.set(device)
        }
    }
}
