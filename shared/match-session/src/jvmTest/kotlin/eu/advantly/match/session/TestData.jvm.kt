package eu.advantly.match.session

import eu.advantly.data.LocalData
import eu.advantly.data.createInMemoryJvmLocalData

actual fun testLocalData(): LocalData = createInMemoryJvmLocalData()
