package com.cvar1984.megaverse

import com.cvar1984.megaverse.sky.SkyMath
import com.cvar1984.megaverse.sky.SkyPaths
import com.cvar1984.megaverse.sky.SolarLunar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The ecliptic and the Moon's path.
 *
 * A drawn great circle is hard to eyeball wrong - it looks like a plausible line
 * wherever it is - so these check it against the bodies that are supposed to travel
 * it. If the Sun ever leaves the ecliptic the circle is not the ecliptic.
 */
class SkyPathsTest {

    private fun dates() = listOf(
        utcJd(2024, 1, 4, 3, 15, 0),
        utcJd(2026, 3, 20, 11, 0, 0),
        utcJd(2026, 9, 14, 0, 0, 0),
        utcJd(2029, 6, 21, 18, 45, 0),
        utcJd(2033, 11, 2, 7, 5, 0),
    )

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun length(v: DoubleArray) = sqrt(dot(v, v))

    /** How far a direction lies off the plane a pole defines, in degrees. */
    private fun offPlane(pole: DoubleArray, ra: Double, dec: Double) =
        SkyMath.dasin(dot(pole, SkyMath.raDecToVector(ra, dec)))

    // ------------------------------------------------------------------- circles

    @Test
    fun aGreatCircleIsUnitLengthAndSquareToItsPole() {
        // The one piece of geometry both paths are built from, so it is worth
        // pinning on poles that have nothing to do with the sky.
        for (pole in listOf(
            doubleArrayOf(0.0, 0.0, 1.0),
            doubleArrayOf(1.0, 0.0, 0.0),
            SkyMath.raDecToVector(47.0, -31.0),
            SkyPaths.eclipticPole(),
        )) {
            val run = SkyPaths.greatCircle(pole)
            assertEquals(0, run.size % 3)
            var i = 0
            while (i < run.size) {
                val point = doubleArrayOf(
                    run[i].toDouble(), run[i + 1].toDouble(), run[i + 2].toDouble()
                )
                assertEquals("unit length", 1.0, length(point), 1e-5)
                assertEquals("square to the pole", 0.0, dot(pole, point), 1e-5)
                i += 3
            }
        }
    }

    @Test
    fun theCircleClosesOnItself() {
        // It is drawn as one unbroken run, so the last point has to land back on
        // the first or the line is left with a gap in it.
        val run = SkyPaths.greatCircle(SkyPaths.eclipticPole())
        for (k in 0..2) {
            assertEquals(run[k], run[run.size - 3 + k], 1e-5f)
        }
    }

    // ------------------------------------------------------------- the ecliptic

    @Test
    fun theEclipticIsTiltedFromTheEquatorByTheObliquity() {
        // Its pole and the celestial pole are one obliquity apart, by definition.
        val celestial = doubleArrayOf(0.0, 0.0, 1.0)
        val between = SkyMath.dacos(dot(celestial, SkyPaths.eclipticPole()))
        assertEquals(SolarLunar.obliquity(0.0), between, 1e-4)
    }

    @Test
    fun theSunNeverLeavesTheEcliptic() {
        // The ecliptic is the Sun's own path, so this is the check that the circle
        // being drawn is the one it claims to be rather than some other great
        // circle at roughly the right angle.
        val pole = SkyPaths.eclipticPole()
        for (jd in dates()) {
            val sun = SolarLunar.sunPosition(jd)
            val off = offPlane(pole, sun[0], sun[1])
            assertTrue("the Sun sat $off degrees off the ecliptic", abs(off) < 0.02)
        }
    }

    @Test
    fun thePlanetsStayNearTheEcliptic() {
        // They orbit in nearly the same plane, which is why the ecliptic is worth
        // drawing at all: it is where to look for them.
        val pole = SkyPaths.eclipticPole()
        for (jd in dates()) {
            for (id in listOf("mercury", "venus", "mars", "jupiter", "saturn")) {
                val raDec = com.cvar1984.megaverse.sky.Planets.planetPosition(id, jd)
                val off = offPlane(pole, raDec[0], raDec[1])
                assertTrue("$id sat $off degrees off the ecliptic", abs(off) < 8.0)
            }
        }
    }

    // ------------------------------------------------------------ the Moon's path

    @Test
    fun theMoonPathIsTiltedFiveDegreesFromTheEcliptic() {
        for (jd in dates()) {
            val between = SkyMath.dacos(dot(SkyPaths.eclipticPole(), SkyPaths.moonPathPole(jd)))
            assertEquals(SkyPaths.MOON_INCLINATION, between, 1e-3)
        }
    }

    @Test
    fun theMoonStaysOnItsOwnPath() {
        // Within a third of a degree: the path is drawn from mean elements while
        // the Moon itself is placed from a perturbed series, and that is the size of
        // the gap between them. It is also the check that the node is being tracked
        // - a path pinned to one epoch drifts degrees away inside a year.
        for (jd in dates()) {
            val pole = SkyPaths.moonPathPole(jd)
            val moon = SolarLunar.moonPosition(jd)
            val off = offPlane(pole, moon[0], moon[1])
            assertTrue("the Moon sat $off degrees off its own path", abs(off) < 0.35)
        }
    }

    @Test
    fun theMoonPathFollowsItsNodeRoundTheEcliptic() {
        // The node goes all the way round in 18.6 years, about nineteen degrees a
        // year, so a path built today is visibly wrong in a few months. Half a
        // period apart the pole has to be on the far side of the ecliptic pole.
        val now = utcJd(2026, 1, 1, 0, 0, 0)
        val soon = SkyPaths.moonPathPole(now)
        val later = SkyPaths.moonPathPole(now + 365.25 * 9.3)
        val swung = SkyMath.dacos(dot(soon, later))
        assertEquals("half a nodal period should invert the tilt", 2 * SkyPaths.MOON_INCLINATION, swung, 0.4)

        // A month moves it measurably, though by much less than the node itself:
        // the pole rides a circle only five degrees across, so it travels at
        // sin(inclination) of the node's rate - about a ninth of 1.6 degrees.
        val month = SkyMath.dacos(dot(soon, SkyPaths.moonPathPole(now + 30.0)))
        assertEquals("a month moved the path by $month degrees", 0.14, month, 0.04)
    }

    // ------------------------------------------------------------- date marks

    /** Marks are worked out against a fixed zone so the dates do not move with the runner. */
    private val utc: java.time.ZoneId = java.time.ZoneId.of("UTC")

    @Test
    fun everyEclipticMarkLiesOnTheEcliptic() {
        // A date mark that is not on the line it labels points at the wrong patch
        // of sky, and on a drawn circle that is invisible: it would just look like
        // a label slightly off a curve.
        val pole = SkyPaths.eclipticPole()
        for (jd in dates()) {
            val marks = SkyPaths.eclipticMarks(jd, utc)
            assertEquals(12, marks.size)
            for (mark in marks) {
                assertEquals("unit length", 1.0, length(mark.vector), 1e-6)
                val off = SkyMath.dasin(dot(pole, mark.vector))
                assertTrue("${mark.label} sat $off degrees off the ecliptic", abs(off) < 0.02)
            }
        }
    }

    @Test
    fun everyMoonMarkLiesOnTheMoonPath() {
        for (jd in dates()) {
            val pole = SkyPaths.moonPathPole(jd)
            val marks = SkyPaths.moonMarks(jd, utc)
            assertEquals(27, marks.size)
            for (mark in marks) {
                assertEquals("unit length", 1.0, length(mark.vector), 1e-6)
                val off = SkyMath.dasin(dot(pole, mark.vector))
                assertTrue("${mark.label} sat $off degrees off its path", abs(off) < 0.4)
            }
        }
    }

    @Test
    fun theMarksAreSpacedTheWayTheBodiesTravel() {
        // The Sun covers a month of ecliptic between marks, the Moon a day of its
        // own path. Those are about thirty degrees and about thirteen, and getting
        // them the wrong way round is the mistake this catches.
        val jd = utcJd(2026, 9, 14, 0, 0, 0)

        val sun = SkyPaths.eclipticMarks(jd, utc)
        for (i in 1 until sun.size) {
            val apart = SkyMath.dacos(dot(sun[i - 1].vector, sun[i].vector))
            assertTrue("a month of Sun covered $apart degrees", apart > 27.0 && apart < 33.0)
        }

        val moon = SkyPaths.moonMarks(jd, utc)
        for (i in 1 until moon.size) {
            val apart = SkyMath.dacos(dot(moon[i - 1].vector, moon[i].vector))
            assertTrue("a day of Moon covered $apart degrees", apart > 11.0 && apart < 16.0)
        }
    }

    @Test
    fun theMarksAreLabelledWithRealDates() {
        val jd = utcJd(2026, 9, 14, 0, 0, 0)

        // Twelve months running from the one we are in, each named.
        val months = SkyPaths.eclipticMarks(jd, utc).map { it.label }
        assertEquals("Sep", months.first())
        assertEquals(12, months.toSet().size)

        // A full lunar circuit of day numbers starting today, running over the
        // month end and back into single figures.
        val days = SkyPaths.moonMarks(jd, utc).map { it.label }
        assertEquals(27, days.size)
        assertEquals(listOf("14", "15", "16", "17", "18", "19", "20"), days.take(7))
        assertEquals(listOf("9", "10"), days.takeLast(2))
    }

    // ------------------------------------------------------------ constellations

    private val zodiac = listOf(
        "Aries", "Taurus", "Gemini", "Cancer", "Leo", "Virgo",
        "Libra", "Scorpius", "Sagittarius", "Capricornus", "Aquarius", "Pisces",
    )

    @Test
    fun allTwelveOfTheZodiacAreDrawn() {
        // The ecliptic runs through every one of them, so a sky pointer that draws
        // the Sun's road and then leaves gaps along it is half finished.
        val drawn = com.cvar1984.megaverse.sky.Constellations.all.map { it.name }
        for (sign in zodiac) {
            assertTrue("$sign is missing", sign in drawn)
        }
        assertEquals("no figure should be listed twice", drawn.size, drawn.toSet().size)
    }

    @Test
    fun theZodiacFiguresSitInTheZodiacBand()  {
        // A zodiac constellation straddles the ecliptic by construction: that is
        // what puts it in the zodiac. A vertex far outside the band is a coordinate
        // typed wrong, and on a drawn figure that is invisible - it just looks like
        // a slightly odd shape. Scorpius reaches furthest, its sting some twenty
        // degrees south.
        val pole = SkyPaths.eclipticPole()
        for (figure in com.cvar1984.megaverse.sky.Constellations.all) {
            if (figure.name !in zodiac) continue
            for (stroke in figure.strokes) {
                for (i in 0 until stroke.size / 2) {
                    val v = SkyMath.raDecToVector(stroke[2 * i], stroke[2 * i + 1])
                    val off = SkyMath.dasin(dot(pole, v))
                    assertTrue("${figure.name} has a star $off degrees off the ecliptic", abs(off) < 25.0)
                }
            }
        }
    }

    @Test
    fun noConstellationLineStretchesAcrossTheSky() {
        // Every stroke joins neighbouring stars, so no segment should run further
        // than a constellation is wide. This is what catches a transposed digit in
        // a right ascension, which otherwise draws a plausible line to nowhere.
        for (figure in com.cvar1984.megaverse.sky.Constellations.all) {
            for (stroke in figure.strokes) {
                assertTrue("${figure.name} has a stroke of pairs", stroke.size % 2 == 0)
                assertTrue("${figure.name} needs two ends to draw", stroke.size >= 4)
                for (i in 1 until stroke.size / 2) {
                    val a = SkyMath.raDecToVector(stroke[2 * i - 2], stroke[2 * i - 1])
                    val b = SkyMath.raDecToVector(stroke[2 * i], stroke[2 * i + 1])
                    val apart = SkyMath.dacos(dot(a, b))
                    assertTrue("${figure.name} has a $apart degree segment", apart < 30.0)
                }
            }
        }
    }

    @Test
    fun everyStrokeBecomesAVectorRun() {
        // The drawn runs are the figures flattened, so the two have to stay in step:
        // a figure whose strokes go missing draws nothing and says nothing about it.
        val strokes = com.cvar1984.megaverse.sky.Constellations.all.sumOf { it.strokes.size }
        assertEquals(strokes, com.cvar1984.megaverse.sky.Constellations.vectors().size)
    }

    @Test
    fun aCircleOfLatitudeShrinksAwayFromThePlane() {
        // circleAt is the one piece of geometry the band and the two paths share, so
        // it is worth pinning on its own: at the pole it collapses to a point, and
        // at zero it is the great circle the paths are drawn from.
        val pole = SkyMath.raDecToVector(47.0, -31.0)
        val equator = SkyPaths.circleAt(pole, 0.0)
        assertTrue(SkyPaths.greatCircle(pole).contentEquals(equator))

        for (lat in listOf(0.0, 30.0, 60.0, 89.0)) {
            val run = SkyPaths.circleAt(pole, lat)
            val radius = kotlin.math.sqrt(
                (0 until run.size / 3).maxOf { i ->
                    val v = doubleArrayOf(
                        run[3 * i].toDouble(), run[3 * i + 1].toDouble(), run[3 * i + 2].toDouble()
                    )
                    val along = dot(pole, v)
                    1.0 - along * along
                }
            )
            assertEquals("radius at $lat", SkyMath.dcos(lat), radius, 1e-5)
        }
    }
}
