package eu.advantly.umbrella.watch

import eu.advantly.data.LocalData
import eu.advantly.data.createNativeDriver

private var counter = 0

// Läuft nur auf macOS (watchosSimulatorArm64Test)
internal actual fun testLocalData(): LocalData = LocalData(createNativeDriver(name = "watch-test-${++counter}.db", inMemory = true))
