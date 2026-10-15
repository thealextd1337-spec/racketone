package eu.advantly.match.session

import eu.advantly.data.LocalData
import eu.advantly.data.createNativeDriver

private var counter = 0

// Läuft nur auf macOS (iosSimulatorArm64Test / watchosSimulatorArm64Test)
actual fun testLocalData(): LocalData = LocalData(createNativeDriver(name = "session-test-${++counter}.db", inMemory = true))
