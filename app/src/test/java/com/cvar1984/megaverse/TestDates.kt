package com.cvar1984.megaverse

import com.cvar1984.megaverse.sky.julianDayFromEpochMillis
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * A Julian Day for a UTC calendar instant, built the way the app builds one: clock
 * hands to epoch milliseconds, milliseconds to a Julian Day.
 *
 * The calendar arithmetic is java.time's, which is the point. The app does not own
 * a Gregorian algorithm any more, so nothing here should be exercising one - and
 * an independent implementation makes a better oracle than a second copy of ours.
 */
fun utcJd(
    year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0, second: Int = 0,
): Double = julianDayFromEpochMillis(
    LocalDateTime.of(year, month, day, hour, minute, second)
        .toInstant(ZoneOffset.UTC)
        .toEpochMilli()
)
