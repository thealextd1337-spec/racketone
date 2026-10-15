package eu.advantly.presentation.live

import eu.advantly.presentation.match.MonotonicClock

internal actual fun liveTelemetryClock(): MonotonicClock = MonotonicClock { android.os.SystemClock.elapsedRealtime() }
