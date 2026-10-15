package eu.advantly.umbrella.watch

import eu.advantly.data.LocalData
import eu.advantly.data.createInMemoryJvmLocalData

internal actual fun testLocalData(): LocalData = createInMemoryJvmLocalData()
