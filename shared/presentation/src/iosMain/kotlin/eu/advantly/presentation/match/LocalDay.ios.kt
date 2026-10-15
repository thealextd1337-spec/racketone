package eu.advantly.presentation.match

import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSDate

actual fun systemLocalDay(): LocalDay {
    val parts =
        NSCalendar.currentCalendar.components(
            NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay,
            fromDate = NSDate(),
        )
    return LocalDay(parts.year.toInt(), parts.month.toInt(), parts.day.toInt())
}
