package eu.advantly.data

import app.cash.sqldelight.db.SqlDriver

actual fun createTestDriver(): SqlDriver = createInMemoryJvmDriver()
