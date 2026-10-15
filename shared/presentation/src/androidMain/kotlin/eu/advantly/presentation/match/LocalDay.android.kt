package eu.advantly.presentation.match

import java.time.LocalDate

actual fun systemLocalDay(): LocalDay = LocalDate.now().let { LocalDay(it.year, it.monthValue, it.dayOfMonth) }

actual fun localDayAt(epochMs: Long): LocalDay =
    java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneId.systemDefault()).toLocalDate().let {
        LocalDay(it.year, it.monthValue, it.dayOfMonth)
    }
