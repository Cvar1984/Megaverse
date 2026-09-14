package com.cvar1984.megaverse

import com.cvar1984.megaverse.sky.Constellations
import com.cvar1984.megaverse.sky.DeviceAim
import com.cvar1984.megaverse.sky.Planets
import com.cvar1984.megaverse.sky.SkyCatalog
import com.cvar1984.megaverse.sky.SkyMath
import com.cvar1984.megaverse.sky.SkyType
import com.cvar1984.megaverse.sky.SolarLunar
import com.cvar1984.megaverse.sky.julianDayFromEpochMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Ported from the Garmin build's suite, and written the same way: invariants
 * wherever one exists, because an invariant keeps testing after someone changes a
 * constant, where a hand-copied decimal only tests that it was copied correctly.
 */
class SkyTest {

    // ------------------------------------------------------------------ angles

    @Test
    fun norm360WrapsBothWays() {
        assertEquals(350.0, SkyMath.norm360(-10.0), 1e-9)
        assertEquals(10.0, SkyMath.norm360(370.0), 1e-9)
        assertEquals(0.0, SkyMath.norm360(360.0), 1e-9)
        assertEquals(350.0, SkyMath.norm360(-730.0), 1e-9)
    }

    @Test
    fun norm180SignsTheShortWayRound() {
        assertEquals(-10.0, SkyMath.norm180(350.0), 1e-9)
        assertEquals(-170.0, SkyMath.norm180(190.0), 1e-9)
        assertEquals(10.0, SkyMath.norm180(10.0), 1e-9)
    }

    // -------------------------------------------------------------------- time

    @Test
    fun julianDayHitsTheJ2000Epoch() {
        // Noon on 1 January 2000 UTC is the definition of JD 2451545.0.
        assertEquals(2451545.0, utcJd(2000, 1, 1, 12, 0, 0), 1e-7)
    }

    @Test
    fun julianDayAdvancesOnePerDay() {
        val a = utcJd(2026, 3, 14, 0, 0, 0)
        val b = utcJd(2026, 3, 15, 0, 0, 0)
        assertEquals(1.0, b - a, 1e-7)
    }

    @Test
    fun julianDayKeepsTheTimeOfDay() {
        val midnight = utcJd(2026, 9, 6, 0, 0, 0)
        assertEquals(0.25, utcJd(2026, 9, 6, 6, 0, 0) - midnight, 1e-7)
        assertTrue(utcJd(2026, 9, 6, 0, 1, 0) > midnight)
    }

    @Test
    fun theUnixEpochSitsWhereTheAlmanacPutsIt() {
        // The whole conversion is one offset and one division, so the offset is the
        // only thing in it that can be wrong. 1970-01-01T00:00Z is JD 2440587.5 by
        // definition, and that is what pins it.
        assertEquals(2440587.5, julianDayFromEpochMillis(0L), 1e-9)
        assertEquals(2440588.0, julianDayFromEpochMillis(43_200_000L), 1e-9)
    }

    @Test
    fun siderealTimeAdvancesFasterThanTheClock() {
        // The sky turns slightly more than 360 degrees per solar day, which is why
        // a star rises about four minutes earlier each night.
        val jd = utcJd(2026, 9, 6, 0, 0, 0)
        val gained = SkyMath.norm360(SkyMath.gmst(jd + 1.0) - SkyMath.gmst(jd))
        assertTrue("gained $gained", gained > 0.9 && gained < 1.1)
    }

    @Test
    fun localSiderealTimeIsOffsetByLongitude() {
        val jd = utcJd(2026, 9, 6, 3, 30, 0)
        val between = SkyMath.norm360(SkyMath.lst(jd, 45.0) - SkyMath.lst(jd, 0.0))
        assertEquals(45.0, between, 1e-4)
    }

    // ------------------------------------------------------------- coordinates

    @Test
    fun enuVectorsAreUnitLength() {
        var az = 0.0
        while (az < 360.0) {
            var alt = -80.0
            while (alt <= 80.0) {
                assertEquals(1.0, length(SkyMath.horizontalToEnu(az, alt)), 1e-9)
                assertEquals(1.0, length(SkyMath.raDecToVector(az, alt)), 1e-9)
                alt += 40.0
            }
            az += 45.0
        }
    }

    @Test
    fun anObjectOnTheMeridianAtYourLatitudeIsOverhead() {
        // Declination equal to latitude, hour angle zero: that is the zenith, at
        // every latitude, with no exceptions to remember.
        for (lat in listOf(-60.0, -23.5, 0.0, 12.0, 51.5, 78.0)) {
            val altAz = SkyMath.raDecToAltAz(120.0, lat, lat, 120.0)
            assertEquals("lat $lat", 90.0, altAz[0], 1e-4)
        }
    }

    @Test
    fun equatorialToEnuIsTheSameRotationWrittenAsAMatrix() {
        val rows = SkyMath.equatorialToEnu(51.5, 200.0)
        for (ra in listOf(0.0, 47.0, 190.0, 355.0)) {
            for (dec in listOf(-70.0, 0.0, 81.0)) {
                val v = SkyMath.raDecToVector(ra, dec)
                val direct = enuOf(ra, dec)
                for (c in 0..2) {
                    val folded = rows[3 * c] * v[0] + rows[3 * c + 1] * v[1] + rows[3 * c + 2] * v[2]
                    assertEquals("ra $ra dec $dec axis $c", direct[c], folded.toDouble(), 1e-5)
                }
            }
        }
    }

    // --------------------------------------------------------------- the sky

    @Test
    fun refractionLiftsMostAtTheHorizonAndNotAtAllAboveIt() {
        assertTrue(SkyMath.refraction(0.0) > 0.4 && SkyMath.refraction(0.0) < 0.7)
        assertTrue(SkyMath.refraction(10.0) < SkyMath.refraction(0.0))
        assertTrue(SkyMath.refraction(80.0) < 0.01)
        assertEquals(0.0, SkyMath.refraction(-5.0), 0.0)
    }

    @Test
    fun parallaxPushesTheMoonDownAndRefractionLiftsItBack() {
        // Overhead neither correction bites: parallax goes as the cosine of the
        // altitude, and refraction has the least air to bend through.
        assertEquals(90.0, SkyMath.apparentAltitude(90.0, 0.95), 0.02)
        // At the horizon parallax acts in full and refraction lifts about 0.57 of
        // it back, leaving the Moon a little over a tenth of a degree low.
        val atHorizon = SkyMath.apparentAltitude(0.0, 0.95)
        assertTrue("$atHorizon", atHorizon > -0.95 && atHorizon < 0.0)
    }

    @Test
    fun sunStaysWithinTheTropics() {
        // The Sun's declination is bounded by the obliquity. If it ever leaves that
        // band the series has gone wrong, whatever else looks right.
        for (jd in samples()) {
            val raDec = SolarLunar.sunPosition(jd)
            assertTrue("dec ${raDec[1]}", abs(raDec[1]) <= 23.5)
            assertTrue("ra ${raDec[0]}", raDec[0] >= 0.0 && raDec[0] < 360.0)
        }
    }

    @Test
    fun sunReachesTheSolsticesAndEquinoxes() {
        assertTrue(SolarLunar.sunPosition(utcJd(2026, 6, 21, 12, 0, 0))[1] > 23.0)
        assertTrue(SolarLunar.sunPosition(utcJd(2026, 12, 21, 12, 0, 0))[1] < -23.0)
    }

    @Test
    fun eclipticToEquatorialFixesTheEquinoxAndTiltsTheSolstice() {
        // The vernal equinox is where the two frames touch, whatever the obliquity;
        // a quarter turn along the ecliptic sits one full obliquity north.
        val equinox = SolarLunar.eclipticToEquatorial(0.0, 0.0, 23.4393)
        assertEquals(0.0, SkyMath.norm180(equinox[0]), 1e-4)
        assertEquals(0.0, equinox[1], 1e-4)

        val solstice = SolarLunar.eclipticToEquatorial(90.0, 0.0, 23.4393)
        assertEquals(23.4393, solstice[1], 1e-4)
        assertEquals(90.0, solstice[0], 1e-4)
    }

    @Test
    fun theSolsticeSunIsDueSouthOverLondonAtNoon() {
        // The one end-to-end check, because the invariants above each hold even if a
        // sign is flipped somewhere between them. Around the June solstice solar
        // noon at Greenwich falls within a couple of minutes of 12:00 UTC, and the
        // Sun is then due south at an altitude of 90 - latitude + obliquity. Anyone
        // can check the date against a calendar and the number against an almanac.
        val jd = utcJd(2026, 6, 21, 12, 0, 0)
        val raDec = SolarLunar.sunPosition(jd)
        val altAz = SkyMath.raDecToAltAz(raDec[0], raDec[1], 51.5, SkyMath.lst(jd, 0.0))
        assertEquals("azimuth ${altAz[1]}", 180.0, altAz[1], 1.0)
        assertEquals("altitude ${altAz[0]}", 90.0 - 51.5 + 23.44, altAz[0], 0.5)
    }

    @Test
    fun theMoonStaysWithinItsOwnBand() {
        // The Moon's orbit is tilted about 5.1 degrees to the ecliptic, so its
        // declination is bounded by the obliquity plus that and nothing else. A
        // series that has gone wrong leaves the band whatever else looks right.
        for (jd in samples()) {
            val raDec = SolarLunar.moonPosition(jd)
            assertTrue("dec ${raDec[1]}", abs(raDec[1]) < 28.8)
            assertTrue("ra ${raDec[0]}", raDec[0] >= 0.0 && raDec[0] < 360.0)
        }
    }

    @Test
    fun theMoonMovesAboutThirteenDegreesADay() {
        // It goes right round the sky in a month, which is what makes it the one
        // object a stale position shows up on within an evening.
        for (jd in samples()) {
            val moved = between(SolarLunar.moonPosition(jd), SolarLunar.moonPosition(jd + 1.0))
            assertTrue("$moved", moved > 11.0 && moved < 16.0)
        }
    }

    @Test
    fun theMoonRunsThroughEveryPhaseInAMonth() {
        // The phase it is drawn at comes from its angle from the Sun, so that angle
        // has to sweep from new right through to full inside a synodic month. A
        // series stuck near one value would draw a Moon that never changed shape.
        var newest = 180.0
        var fullest = 0.0
        for (day in 0..29) {
            val jd = utcJd(2026, 1, 1, 0, 0, 0) + day
            val apart = between(SolarLunar.moonPosition(jd), SolarLunar.sunPosition(jd))
            newest = minOf(newest, apart)
            fullest = maxOf(fullest, apart)
        }
        assertTrue("closest approach to the Sun was $newest", newest < 10.0)
        assertTrue("furthest from the Sun was $fullest", fullest > 170.0)
    }

    @Test
    fun moonParallaxIsAboutOneDegree() {
        for (jd in samples()) {
            val hp = SolarLunar.moonHorizontalParallax(jd)
            assertTrue("$hp", hp > 0.85 && hp < 1.05)
        }
    }

    @Test
    fun planetsStayInTheZodiac() {
        // Every planet in the catalogue orbits near the ecliptic, so none of them
        // can wander more than about nine degrees off the Sun's own band.
        for (jd in samples()) {
            val eps = SolarLunar.obliquity(SolarLunar.centuriesSinceJ2000(jd))
            for (id in listOf("mercury", "venus", "mars", "jupiter", "saturn")) {
                val raDec = Planets.planetPosition(id, jd)
                assertTrue("$id dec ${raDec[1]}", abs(raDec[1]) < eps + 9.0)
                assertTrue("$id ra ${raDec[0]}", raDec[0] >= 0.0 && raDec[0] < 360.0)
            }
        }
    }

    @Test
    fun keplersEquationIsActuallySolved() {
        // E - e sin E = M is the equation being solved, so putting the answer back
        // has to give the mean anomaly it started from.
        for (e in listOf(0.0067, 0.0935, 0.2056)) {
            for (m in listOf(0.0, 37.0, 129.0, 271.0, 359.0)) {
                val eAnom = Planets.eccentricAnomaly(m, e)
                val back = eAnom - Math.toDegrees(1.0) * e * SkyMath.dsin(eAnom)
                assertEquals("e $e m $m", m, SkyMath.norm360(back), 1e-4)
            }
        }
    }

    // ------------------------------------------------------------- the catalogue

    @Test
    fun theCatalogueIsWhatTheMenusExpect() {
        assertEquals(7 + 28, SkyCatalog.objects.size)
        assertEquals(5, SkyCatalog.byType(SkyType.PLANET).size)
        assertEquals(28, SkyCatalog.byType(SkyType.STAR).size)
        // A star's id is its position in the catalogue, so star_0 must stay Sirius
        // however the menus choose to sort themselves.
        assertEquals("Sirius", SkyCatalog.findById("star_0")?.name)
        assertEquals(
            SkyCatalog.byType(SkyType.STAR).map { it.name },
            SkyCatalog.byType(SkyType.STAR).map { it.name }.sorted(),
        )
    }

    @Test
    fun theCatalogueSendsEachKindOfObjectToTheRightMaths() {
        // Everything on screen is placed through this one dispatch, so a type
        // wired to the wrong branch would move a whole class of object at once -
        // and a planet drawn with a star's fixed coordinates looks perfectly
        // plausible until you check it against the sky a month later.
        val jd = utcJd(2026, 9, 14, 3, 30)

        for (planet in SkyCatalog.byType(SkyType.PLANET)) {
            val viaCatalogue = SkyCatalog.raDec(planet, jd)
            val direct = Planets.planetPosition(planet.id, jd)
            assertEquals(planet.name, direct[0], viaCatalogue[0], 1e-9)
            assertEquals(planet.name, direct[1], viaCatalogue[1], 1e-9)
        }

        val sun = SkyCatalog.findById("sun")!!
        assertEquals(SolarLunar.sunPosition(jd)[0], SkyCatalog.raDec(sun, jd)[0], 1e-9)
        val moon = SkyCatalog.findById("moon")!!
        assertEquals(SolarLunar.moonPosition(jd)[0], SkyCatalog.raDec(moon, jd)[0], 1e-9)
    }

    @Test
    fun aStarIsHandedBackExactlyAsTheCatalogueHoldsIt() {
        // Stars do not move, so the clock must not touch them. If the dispatch ever
        // sent them through a series they would drift with the date, which is the
        // one thing a fixed catalogue position cannot do.
        for (star in SkyCatalog.byType(SkyType.STAR)) {
            for (jd in listOf(utcJd(2024, 1, 1), utcJd(2026, 9, 14), utcJd(2031, 6, 30))) {
                val raDec = SkyCatalog.raDec(star, jd)
                assertEquals(star.name, star.ra, raDec[0], 0.0)
                assertEquals(star.name, star.dec, raDec[1], 0.0)
            }
        }
    }

    @Test
    fun everyConstellationVertexIsAUnitVector() {
        // A figure with a bad coordinate pair in it would still draw, just in the
        // wrong place, so the length is what catches a mistyped declination.
        for (figure in Constellations.vectors()) {
            assertTrue(figure.size % 3 == 0)
            var i = 0
            while (i < figure.size) {
                val len = sqrt(
                    (figure[i] * figure[i] + figure[i + 1] * figure[i + 1] +
                        figure[i + 2] * figure[i + 2]).toDouble()
                )
                assertEquals(1.0, len, 1e-5)
                i += 3
            }
        }
    }

    // --------------------------------------------------------------- device aim

    @Test
    fun declinationTurnsTheFrameByExactlyThatMuchAndLeavesUpAlone() {
        val plain = Frames.LEVEL_FACING_SOUTH
        val swung = DeviceAim.applyDeclination(plain, 12.0)
        val between = SkyMath.dacos(
            (plain[3] * swung[3] + plain[4] * swung[4] + plain[5] * swung[5]).toDouble()
        )
        assertEquals(12.0, between, 0.01)
        for (k in 6..8) assertEquals(plain[k], swung[k], 1e-6f)
        assertEquals(1.0, length(doubleArrayOf(swung[0].toDouble(), swung[1].toDouble(), swung[2].toDouble())), 1e-5)
    }

    @Test
    fun zeroDeclinationChangesNothing() {
        val plain = Frames.LEVEL_FACING_SOUTH
        assertTrue(DeviceAim.applyDeclination(plain, 0.0).contentEquals(plain))
    }

    @Test
    fun aimReadsTheBackOfTheCase() {
        // Screen flat up: the back points at the nadir.
        assertEquals(-90.0, DeviceAim.aimElevation(Frames.FLAT_SCREEN_UP), 1e-3)
        // Held on edge facing north: the back is level and looking south.
        val onEdge = Frames.LEVEL_FACING_SOUTH
        assertEquals(0.0, DeviceAim.aimElevation(onEdge), 1e-3)
        assertEquals(180.0, DeviceAim.aimHeading(onEdge), 1e-3)
    }

    @Test
    fun viewOffsetPreservesLength() {
        val frame = Frames.LEVEL_FACING_SOUTH
        val enu = SkyMath.horizontalToEnu(37.0, 22.0)
        assertEquals(1.0, length(DeviceAim.viewOffset(frame, enu[0], enu[1], enu[2])), 1e-5)
    }

    @Test
    fun screenPointDropsWhatIsBehindTheWatch() {
        val frame = Frames.LEVEL_FACING_SOUTH
        val view = floatArrayOf(100f, 100f, 100f)
        // The watch is on edge looking south, so due north is behind it.
        val behindEnu = SkyMath.horizontalToEnu(0.0, 0.0)
        assertNull(DeviceAim.screenPoint(frame, behindEnu[0], behindEnu[1], behindEnu[2], view))

        val aim = DeviceAim.aimDirection(frame)
        val centre = DeviceAim.screenPoint(frame, aim[0], aim[1], aim[2], view)
        assertNotNull(centre)
        assertEquals(100f, centre!![0], 1f)
        assertEquals(100f, centre[1], 1f)
    }

    @Test
    fun guidanceIsZeroWhenAlreadyOnTheObject() {
        val basis = DeviceAim.aimBasis(20.0, 140.0)!!
        val enu = SkyMath.horizontalToEnu(140.0, 20.0)
        val aimed = DeviceAim.project(basis, enu[0], enu[1], enu[2])
        assertEquals(0.0, aimed[0], 1e-3)
        assertEquals(0.0, aimed[1], 1e-3)
        assertEquals(1.0, aimed[2], 1e-3)
    }

    @Test
    fun guidanceSignsPointTheRightWay() {
        val basis = DeviceAim.aimBasis(0.0, 0.0)!!
        val east = SkyMath.horizontalToEnu(30.0, 0.0)
        assertTrue(DeviceAim.project(basis, east[0], east[1], east[2])[0] > 0.0)
        val above = SkyMath.horizontalToEnu(0.0, 30.0)
        assertEquals(30.0, DeviceAim.project(basis, above[0], above[1], above[2])[1], 1e-3)
    }

    @Test
    fun aimBasisGivesUpWhenAimedStraightUp() {
        assertNull(DeviceAim.aimBasis(90.0, 0.0))
        assertNull(DeviceAim.aimBasis(-90.0, 0.0))
        assertNotNull(DeviceAim.aimBasis(45.0, 0.0))
    }

    @Test
    fun foldedAndTwoStepProjectionsAgree() {
        // rotateFrame folds the equatorial rotation into the device frame so a whole
        // grid can be drawn from fixed vectors; it has to land where the two-step
        // route does.
        val frame = Frames.LEVEL_FACING_SOUTH
        val rows = SkyMath.equatorialToEnu(51.5, 200.0)
        val folded = DeviceAim.rotateFrame(frame, rows)
        val v = SkyMath.raDecToVector(120.0, 35.0)

        val direct = DeviceAim.viewOffset(folded, v[0], v[1], v[2])
        val enu = enuOf(120.0, 35.0)
        val twoStep = DeviceAim.viewOffset(frame, enu[0], enu[1], enu[2])
        for (k in 0..2) assertEquals(twoStep[k], direct[k], 1e-5)
    }

    // ------------------------------------------------------------------ helpers

    /** Dates spread across a year and a couple of decades. */
    private fun samples() = listOf(
        utcJd(2024, 1, 4, 3, 15, 0),
        utcJd(2025, 3, 20, 11, 0, 0),
        utcJd(2026, 6, 21, 18, 45, 0),
        utcJd(2026, 9, 6, 0, 0, 0),
        utcJd(2027, 12, 21, 22, 30, 0),
        utcJd(2031, 8, 9, 7, 5, 0),
    )

    private fun length(v: DoubleArray) = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

    /**
     * A catalogue position as an East-North-Up direction, by the route the app
     * itself takes: altitude and azimuth, then the vector. The oracle the matrix
     * form below is checked against, so it has to be code that ships.
     */
    private fun enuOf(ra: Double, dec: Double, lat: Double = 51.5, lst: Double = 200.0): DoubleArray {
        val altAz = SkyMath.raDecToAltAz(ra, dec, lat, lst)
        return SkyMath.horizontalToEnu(altAz[1], altAz[0])
    }

    /** The angle between two catalogue positions, in degrees. */
    private fun between(a: DoubleArray, b: DoubleArray): Double {
        val va = SkyMath.raDecToVector(a[0], a[1])
        val vb = SkyMath.raDecToVector(b[0], b[1])
        return SkyMath.dacos(va[0] * vb[0] + va[1] * vb[1] + va[2] * vb[2])
    }

}
