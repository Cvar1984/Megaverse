package com.cvar1984.megaverse

import com.cvar1984.megaverse.presentation.DEFAULT_STEP
import com.cvar1984.megaverse.presentation.STEPS
import com.cvar1984.megaverse.presentation.TRAVEL_LIMIT_MILLIS
import com.cvar1984.megaverse.presentation.saturatingAdd
import com.cvar1984.megaverse.presentation.travelLabel
import com.cvar1984.megaverse.sky.SkyMath
import com.cvar1984.megaverse.sky.SolarLunar
import com.cvar1984.megaverse.sky.julianDayFromEpochMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Moving the sky off the present.
 *
 * The offset itself is a number added to the clock in one place, so what is worth
 * checking is not that the addition happens but that the sky it produces is the sky
 * of that other time: the right amount of turn for the hours asked for, and the
 * right motion for the days. Those are facts an almanac would agree with, so they
 * are what these check against.
 */
class TimeTravelTest {
    private val hour = 3_600_000L
    private val day = 24 * hour

    /** A fixed instant to travel from, so none of this depends on the day it is run. */
    private val at = 1_700_000_000_000L

    // ------------------------------------------------------- what travel does

    @Test
    fun sixHoursOnTurnsTheSkyAQuarterOfTheWayRound() {
        // The sky turns once per sidereal day, which is not 24 hours but about
        // 23h56m, so six hours of clock is a little more than a quarter turn. That
        // excess is the whole reason the stars rise four minutes earlier each night.
        val here = 106.8
        val before = SkyMath.lst(julianDayFromEpochMillis(at), here)
        val after = SkyMath.lst(julianDayFromEpochMillis(at + 6 * hour), here)

        val turned = ((after - before) + 360.0) % 360.0
        assertEquals(90.25, turned, 0.02)
    }

    @Test
    fun aDayOnMovesTheMoonAboutThirteenDegrees() {
        // The Moon laps the sky in 27.3 days, so it slides about 13.2 degrees east
        // against the stars every day. Travelling a day forward has to show that, or
        // the offset is reaching the clock but not the ephemeris.
        val jd = julianDayFromEpochMillis(at)
        val before = SolarLunar.moonPosition(jd)
        val after = SolarLunar.moonPosition(jd + 1.0)

        val moved = ((after[0] - before[0]) + 360.0) % 360.0
        assertTrue("moved $moved", moved > 11.0 && moved < 16.0)
    }

    @Test
    fun travellingBackAndForwardAgainLandsOnTheSameSky() {
        // The offset is added to the clock rather than accumulated onto the last
        // answer, so a round trip comes back exactly where it started rather than to
        // somewhere a rounding error away.
        val there = julianDayFromEpochMillis(at - 3 * day)
        val back = julianDayFromEpochMillis(at - 3 * day + 3 * day)
        assertEquals(julianDayFromEpochMillis(at), back, 0.0)
        assertTrue(there < back)
    }

    // ------------------------------------------------------------- the limits

    @Test
    fun everyStepIsAForwardStepAndTheyGrow() {
        // 30 * 86_400_000 does not fit in an Int and comes back negative, which
        // would quietly turn the largest step into a jump backwards. This is here to
        // catch a dropped Long suffix rather than to restate the list.
        var last = 0L
        for ((label, millis) in STEPS) {
            assertTrue("$label is $millis", millis > 0)
            assertTrue("$label is not larger than the step before it", millis > last)
            last = millis
        }
    }

    @Test
    fun theStepItStartsOnIsOneThereIs() {
        // It indexes the list, and the sky screen reads the same index for the
        // crown, so a trimmed list would take the app down on the first turn rather
        // than anywhere near the line that shortened it.
        assertTrue(DEFAULT_STEP in STEPS.indices)
    }

    @Test
    fun theLimitIsFartherThanAnyoneCanTurn() {
        // The crown is meant to spin on without hitting a wall, so the limit has to
        // sit past where turning one could ever take you. The largest step is thirty
        // days; even at an implausible fifty detents a second this is years of
        // unbroken spinning.
        val fastestPerSecond = 50 * STEPS.last().second
        val secondsOfSpinning = TRAVEL_LIMIT_MILLIS / fastestPerSecond
        assertTrue("$secondsOfSpinning s", secondsOfSpinning > 7L * 24 * 60 * 60)
    }

    @Test
    fun theLimitLeavesRoomForAStepBeyondIt() {
        // It exists to stop the arithmetic wrapping, not to stop the user, so it has
        // to sit far enough inside Long that adding to it cannot overflow even
        // before the clamp sees the number.
        assertTrue(TRAVEL_LIMIT_MILLIS > 0)
        assertTrue(TRAVEL_LIMIT_MILLIS < Long.MAX_VALUE / 4)
    }

    // --------------------------------------------------- spinning without end

    @Test
    fun addingStopsAtTheEndInsteadOfWrappingPastIt() {
        // A Long add that overflows comes back on the far side of zero, which during
        // a spin would throw the sky from the far future to the far past between one
        // detent and the next. That reads as a crash, not as a limit.
        assertEquals(Long.MAX_VALUE, saturatingAdd(Long.MAX_VALUE - 5, 100))
        assertEquals(Long.MIN_VALUE, saturatingAdd(Long.MIN_VALUE + 5, -100))
    }

    @Test
    fun addingStopsAtTheEndTheStepWasHeadedFor() {
        // Once it has overflowed the sum no longer says which way it was going, so
        // the direction has to come off what was asked for.
        assertEquals(Long.MAX_VALUE, saturatingAdd(Long.MAX_VALUE, Long.MAX_VALUE))
        assertEquals(Long.MIN_VALUE, saturatingAdd(Long.MIN_VALUE, Long.MIN_VALUE))
    }

    @Test
    fun ordinaryAddingIsLeftAlone() {
        // The saturating add is on the path every detent of every spin takes, so it
        // had better be plain addition everywhere anyone will ever be.
        assertEquals(3 * hour, saturatingAdd(hour, 2 * hour))
        assertEquals(-hour, saturatingAdd(hour, -2 * hour))
        assertEquals(0L, saturatingAdd(TRAVEL_LIMIT_MILLIS, -TRAVEL_LIMIT_MILLIS))
    }

    // -------------------------------------------------------------- the label

    @Test
    fun theLabelSaysNowWhenItIsNow() {
        assertEquals("now", travelLabel(0))
    }

    @Test
    fun theLabelCarriesItsSign() {
        // Without a sign the two directions read identically, and "3 h" pointing the
        // wrong way is worse than no label at all.
        assertTrue(travelLabel(3 * hour).startsWith("+"))
        assertTrue(travelLabel(-3 * hour).startsWith("-"))
    }

    @Test
    fun theLabelChangesUnitWhereTheUnitRunsOut() {
        // Every boundary between the five forms. A unit short of each, the smaller
        // unit is still used; at each, the larger one takes over.
        assertEquals("+59 s", travelLabel(59_000L))
        assertEquals("+1 min 0 s", travelLabel(60_000L))
        assertEquals("+59 min 0 s", travelLabel(59 * 60_000L))
        assertEquals("+1 h 0 min", travelLabel(hour))
        assertEquals("+47 h 0 min", travelLabel(47 * hour))
        assertEquals("+2 d 0 h", travelLabel(48 * hour))
        assertEquals("+364 d 0 h", travelLabel(364 * day))
        assertEquals("+1 y 0 d", travelLabel(365 * day))
    }

    @Test
    fun theLabelKeepsTheRemainderRatherThanRounding() {
        // A label that said "+1 h" for anything inside the hour would be useless for
        // stepping ten minutes at a time, which is the smallest step there is.
        assertEquals("+1 h 30 min", travelLabel(hour + 30 * 60_000L))
        assertEquals("+2 min 5 s", travelLabel(125_000L))
        assertEquals("-3 d 6 h", travelLabel(-(3 * day + 6 * hour)))
    }

    @Test
    fun theLabelIsSymmetricAboutNow() {
        // The same distance either way reads the same but for the sign, so the two
        // directions can be compared at a glance.
        for (millis in listOf(20 * 60_000L, hour + 5 * 60_000L, 9 * day)) {
            assertEquals(
                travelLabel(millis).removePrefix("+"),
                travelLabel(-millis).removePrefix("-"),
            )
        }
    }

    @Test
    fun theLabelNeverRunsPastTheWidthItHas() {
        // It sits on one line under the clock on the time screen, beside buttons
        // that set the width. The longest it ever gets is at the limit.
        for (millis in listOf(TRAVEL_LIMIT_MILLIS, -TRAVEL_LIMIT_MILLIS)) {
            assertTrue(travelLabel(millis), travelLabel(millis).length <= 16)
        }
    }

    @Test
    fun theSkyStaysANumberEvenAtTheFarEndOfTheLimit() {
        // Spinning on forever is only safe if the maths stays finite when you get
        // there. The answers are nonsense that far out and are documented as such,
        // but a NaN would take the screen down rather than merely being wrong.
        for (millis in listOf(TRAVEL_LIMIT_MILLIS, -TRAVEL_LIMIT_MILLIS)) {
            val jd = julianDayFromEpochMillis(at + millis)
            assertTrue(jd.isFinite())
            val lst = SkyMath.lst(jd, 106.8)
            assertTrue("lst $lst", lst.isFinite() && lst >= 0.0 && lst < 360.0)
            for (value in SolarLunar.moonPosition(jd)) assertTrue(value.isFinite())
            for (value in SolarLunar.sunPosition(jd)) assertTrue(value.isFinite())
        }
    }

    @Test
    fun theFinestStepIsOneTheLabelCanShow() {
        // The default step is a second, so the first click has to read as something
        // other than "now" or the crown looks dead. It used to round to "+0 min".
        assertEquals(1_000L, STEPS[DEFAULT_STEP].second)
        assertEquals("+1 s", travelLabel(STEPS[DEFAULT_STEP].second))
        assertEquals("-1 s", travelLabel(-STEPS[DEFAULT_STEP].second))
        assertEquals("+30 s", travelLabel(30_000L))
    }
}
