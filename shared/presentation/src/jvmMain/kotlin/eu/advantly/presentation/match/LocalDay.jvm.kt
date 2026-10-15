package eu.advantly.presentation.match

import java.time.LocalDate

actual fun systemLocalDay(): LocalDay = LocalDate.now().let { LocalDay(it.year, it.monthValue, it.dayOfMonth) }
