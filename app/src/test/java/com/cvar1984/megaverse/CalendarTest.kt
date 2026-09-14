package com.cvar1984.megaverse

import com.cvar1984.megaverse.sky.RiseSet
import com.cvar1984.megaverse.sky.SkyCatalog
import com.cvar1984.megaverse.sky.SkyMath
import com.cvar1984.megaverse.sky.SolarLunar
import com.cvar1984.megaverse.sky.epochMillisFromJulianDay
import com.cvar1984.megaverse.sky.julianDayFromEpochMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The calendar's maths: when the Sun and Moon cross the horizon, and what phase the
 * Moon is at.
 *
 * Checked against facts a calendar or an almanac would agree with - the equinox
 * gives everyone twelve hours of daylight, the midnight Sun does not set inside the
 * Arctic Circle, a lunar month is 29.5 days - rather than against recopied times.
 */
class CalendarTest {

    private val sun = SkyCatalog.findById("sun")!!
    private val moon = SkyCatalog.findById("moon")!!

    /** Local midnight is not needed for these: UTC midnight is a fine 24-hour window. */
    private fun midnight(year: Int, month: Int, day: Int) =
        utcJd(year, month, day, 0, 0, 0)

    private fun hours(jd: Double) = (jd - kotlin.math.floor(jd - 0.5) - 0.5) * 24.0

    // ------------------------------------------------------------------- the Sun

    @Test
    fun theSunRisesBeforeItSets() {
        // The order is the whole shape of a day, and it holds at every latitude
        // where the Sun rises at all.
        for (lat in listOf(-45.0, -20.0, 0.0, 35.0, 51.5)) {
            val day = RiseSet.events(sun, midnight(2026, 4, 10), lat, 0.0)
            assertNotNull("lat $lat rise", day.riseJd)
            assertNotNull("lat $lat set", day.setJd)
            assertTrue("lat $lat", day.riseJd!! < day.setJd!!)
        }
    }

    @Test
    fun theEquinoxGivesEveryoneAboutTwelveHoursOfDaylight() {
        // The Sun is on the celestial equator, so it is up for half the turn
        // wherever you stand. Refraction and the Sun's own width stretch it by a
        // few minutes, and by more the further from the equator, so the tolerance
        // widens with latitude rather than pretending the day is exactly twelve.
        for (lat in listOf(0.0, 20.0, 45.0, -40.0)) {
            val day = RiseSet.events(sun, midnight(2026, 3, 20), lat, 0.0)
            val daylight = (day.setJd!! - day.riseJd!!) * 24.0
            assertEquals("lat $lat daylight $daylight", 12.0, daylight, 0.4)
        }
    }

    @Test
    fun solarNoonAtGreenwichIsNearMidday() {
        // Halfway between sunrise and sunset is solar noon, which drifts either
        // side of 12:00 UTC across the year by the equation of time and never by
        // more than about a quarter of an hour. Read off the two crossings rather
        // than tracked separately: the Sun's day is symmetric about its highest
        // point, so the midpoint already carries it.
        for (date in listOf(
            midnight(2026, 1, 15), midnight(2026, 4, 10),
            midnight(2026, 7, 22), midnight(2026, 11, 3),
        )) {
            val day = RiseSet.events(sun, date, 51.5, 0.0)
            val noon = hours((day.riseJd!! + day.setJd!!) / 2.0)
            assertTrue("solar noon at $noon", abs(noon - 12.0) < 0.35)
        }
    }

    @Test
    fun theMidnightSunDoesNotSet() {
        // Above the Arctic Circle in midsummer the Sun stays up, and in midwinter
        // it never comes up. Both are the same code path with no crossing found, so
        // they have to be told apart by where the altitude sat, not by its absence.
        val summer = RiseSet.events(sun, midnight(2026, 6, 21), 78.0, 15.0)
        assertTrue("max ${summer.maxAltitude} min ${summer.minAltitude}", summer.alwaysUp)
        assertNull(summer.riseJd)
        assertNull(summer.setJd)

        val winter = RiseSet.events(sun, midnight(2026, 12, 21), 78.0, 15.0)
        assertTrue("max ${winter.maxAltitude}", winter.alwaysDown)
        assertNull(winter.riseJd)
    }

    @Test
    fun theSunClimbsHigherInSummerThanInWinter() {
        val summer = RiseSet.events(sun, midnight(2026, 6, 21), 51.5, 0.0)
        val winter = RiseSet.events(sun, midnight(2026, 12, 21), 51.5, 0.0)
        assertTrue(summer.maxAltitude > winter.maxAltitude)
        // At 51.5 north the solstice Sun reaches 90 - lat +/- the obliquity.
        assertEquals(90.0 - 51.5 + 23.44, summer.maxAltitude, 0.7)
        assertEquals(90.0 - 51.5 - 23.44, winter.maxAltitude, 0.7)
    }

    @Test
    fun daysAreLongerInSummerAndTheEffectGrowsWithLatitude() {
        val here = RiseSet.events(sun, midnight(2026, 6, 21), 51.5, 0.0)
        val tropics = RiseSet.events(sun, midnight(2026, 6, 21), 10.0, 0.0)
        val longDay = (here.setJd!! - here.riseJd!!) * 24.0
        val evenDay = (tropics.setJd!! - tropics.riseJd!!) * 24.0
        assertTrue("$longDay vs $evenDay", longDay > evenDay)
        assertTrue("midsummer at 51.5N is about 16.5 hours, got $longDay", longDay > 16.0)
    }

    // ------------------------------------------------------------------ the Moon

    @Test
    fun theMoonRisesAboutFiftyMinutesLaterEachDay() {
        // It goes round the sky once a month against the Earth's once a day, so it
        // loses roughly three quarters of an hour every night. This is the check
        // that the sampled search tracks the Moon's own motion rather than treating
        // it as fixed for the day.
        //
        // Compared as absolute instants, because a moonrise that slides past
        // midnight would otherwise read as a jump backwards. The nightly figure
        // swings widely with the Moon's declination - anywhere from a quarter of an
        // hour to well over one - so each night only has to be plausible while the
        // month's average carries the real assertion.
        val slips = mutableListOf<Double>()
        var previous: Double? = null
        for (offset in 0..31) {
            val rise = RiseSet.events(moon, midnight(2026, 5, 1) + offset, 51.5, 0.0).riseJd
            if (rise != null && previous != null) {
                val later = (rise - previous - 1.0) * 1440.0
                // A day with no rise breaks the chain, so only adjacent pairs count.
                if (later > 0.0 && later < 120.0) slips.add(later)
            }
            previous = rise ?: previous
        }

        assertTrue("should have several nights to compare, got ${slips.size}", slips.size >= 20)
        val mean = slips.average()
        assertEquals("mean nightly slip was $mean minutes", 50.0, mean, 10.0)
    }

    @Test
    fun theMoonPhaseRunsRoundOnceALunarMonth() {
        // A synodic month is 29.53 days, so the phase angle has to come back to
        // where it started after that and not after a sidereal month of 27.3.
        val start = midnight(2026, 2, 1)
        val angle = SolarLunar.moonPhaseAngle(start)
        val later = SolarLunar.moonPhaseAngle(start + 29.53)
        assertEquals("$angle then $later", 0.0, SkyMath.norm180(later - angle), 3.0)
    }

    @Test
    fun illuminationFollowsThePhaseAngle() {
        // New is dark, full is lit, and the quarters are half lit, whatever month
        // it happens to be.
        for (jd in listOf(midnight(2026, 1, 1), midnight(2026, 7, 15), midnight(2027, 3, 9))) {
            for (day in 0..29) {
                val at = jd + day
                val angle = SolarLunar.moonPhaseAngle(at)
                val lit = SolarLunar.moonIllumination(at)
                assertTrue("illumination $lit out of range", lit in 0.0..1.0)
                // The lit fraction is symmetric about full, so it depends only on
                // how far round from the Sun the Moon is.
                val expected = (1.0 - SkyMath.dcos(angle)) / 2.0
                assertEquals(expected, lit, 1e-9)
            }
        }
    }

    @Test
    fun thePhaseNamesSitOnTheirTurningPoints() {
        // The names are read off the angle, so they can be checked directly against
        // the four moments they are named for.
        val names = (0..359 step 5).map { SolarLunar.moonPhaseName(namedAt(it.toDouble())) }
        assertTrue("New" in names)
        assertTrue("First Quarter" in names)
        assertTrue("Full" in names)
        assertTrue("Last Quarter" in names)
        assertTrue("Waxing Crescent" in names)
        assertTrue("Waning Gibbous" in names)
    }

    @Test
    fun aWaxingMoonIsGettingBrighter() {
        // Under half way round it is waxing, so tomorrow is brighter than today.
        var waxingChecked = 0
        for (day in 0..29) {
            val at = midnight(2026, 8, 1) + day
            val angle = SolarLunar.moonPhaseAngle(at)
            if (angle > 10.0 && angle < 170.0) {
                assertTrue(
                    "angle $angle should be brightening",
                    SolarLunar.moonIllumination(at + 1) > SolarLunar.moonIllumination(at),
                )
                waxingChecked += 1
            }
        }
        assertTrue("should have found waxing nights, got $waxingChecked", waxingChecked >= 8)
    }

    // -------------------------------------------------------------------- clocks

    @Test
    fun theJulianDayAndTheClockConvertBothWays() {
        // The calendar puts an answer back on a wall clock, so the trip out and
        // back has to land where it started.
        for (millis in listOf(0L, 946_728_000_000L, 1_788_665_400_000L, 1_900_000_000_000L)) {
            assertEquals(millis, epochMillisFromJulianDay(julianDayFromEpochMillis(millis)))
        }
    }

    /** A Julian Day whose phase angle is near [target] degrees, found by walking a month. */
    private fun namedAt(target: Double): Double {
        var best = midnight(2026, 1, 1)
        var bestGap = 360.0
        for (step in 0..(29 * 24)) {
            val at = midnight(2026, 1, 1) + step / 24.0
            val gap = abs(SkyMath.norm180(SolarLunar.moonPhaseAngle(at) - target))
            if (gap < bestGap) {
                bestGap = gap
                best = at
            }
        }
        return best
    }
}
