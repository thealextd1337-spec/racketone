package eu.advantly.data

import app.cash.sqldelight.db.SqlDriver

private var counter = 0

// Läuft nur auf macOS (iosSimulatorArm64Test / watchosSimulatorArm64Test) – deckt den native-driver ab (Spike S1)
actual fun createTestDriver(): SqlDriver = createNativeDriver(name = "test-${++counter}.db", inMemory = true)
