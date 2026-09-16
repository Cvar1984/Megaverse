package com.cvar1984.megaverse.sky

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * The two roads the sky's moving bodies travel: the ecliptic, which is the Sun's
 * path through the year and the line the planets never stray far from, and the
 * Moon's own path, tilted five degrees off it.
 *
 * Both are great circles, so both are built the same way - from the pole they turn
 * about. That is the whole of the difference between them: where the pole sits.
 * Held as equatorial unit vectors like the equatorial grid, so a frame costs one
 * matrix and nine multiplications a point.
 */
object SkyPaths {
    private val MONTH_NAME: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM")

    /** The Sun's road: gold, because it is the Sun's. */
    const val ECLIPTIC_COLOR = 0xFF998800.toInt()

    /**
     * The Moon's, in a pale silver-lilac. It runs within five degrees of the
     * ecliptic and so is almost always drawn alongside it, which is why the two are
     * as far apart in colour as gold and silver rather than two shades of one hue.
     */
    const val MOON_PATH_COLOR = 0xFF8888AA.toInt()

    // The date marks sit on top of their own line, so they are drawn a shade
    // brighter than it: a label the same brightness as the line under it is the one
    // thing on this screen that has to be read rather than just seen.
    const val ECLIPTIC_MARK_COLOR = 0xFFCCAA33.toInt()
    const val MOON_MARK_COLOR = 0xFFAAAACC.toInt()

    /** Months of the ecliptic to mark, which is all of them. */
    private const val ECLIPTIC_MONTHS = 12

    /**
     * Days of the Moon's path to mark, which is one full circuit: it comes back
     * round in 27.3. A fortnight only dates half the circle, and since the line is
     * drawn whole you then get long stretches of it carrying no date at all -
     * which, aimed at the wrong half, means no dates anywhere on screen.
     */
    private const val MOON_DAYS = 27

    /**
     * Degrees between plotted points. Finer than the grids, which get away with
     * twenty because there are dozens of them and the eye reads the mesh rather
     * than any one line. These two are drawn alone, where a corner cut across
     * twenty degrees of a 90 degree view would show.
     */
    private const val SAMPLE = 5

    /** The tilt of the Moon's orbit to the ecliptic, in degrees. */
    const val MOON_INCLINATION = 5.145

    // The ecliptic never moves, so it is worked out on first use and kept for good.
    private val ecliptic by lazy(LazyThreadSafetyMode.NONE) { greatCircle(eclipticPole()) }

    private val eclipticMarkCache = Daily<List<PathMark>>()
    private val moonMarkCache = Daily<List<PathMark>>()
    private val moonPathCache = Daily<FloatArray>()

    /**
     * The pole of the ecliptic in equatorial coordinates: a quarter turn round from
     * the equinox, and one obliquity short of the celestial pole.
     *
     * Worked out at J2000 and left there. The obliquity loses about 47 arcseconds a
     * century, which over the life of this app is far under the width of the line
     * it draws.
     */
    fun eclipticPole(): DoubleArray =
        SkyMath.raDecToVector(270.0, 90.0 - SolarLunar.obliquity(0.0))

    /**
     * The pole of the Moon's orbit, which is the one thing about its path that
     * moves: the orbit's tilt holds at about five degrees while its node slides all
     * the way round the ecliptic every 18.6 years, some nineteen degrees a year.
     * That is slow enough to rebuild once a day and far too fast to ignore.
     */
    fun moonPathPole(jd: Double): DoubleArray {
        val t = SolarLunar.centuriesSinceJ2000(jd)
        val node = SkyMath.norm360(125.0445479 - 1934.1362891 * t)
        // The pole of a plane sits a quarter turn behind its ascending node and a
        // full inclination short of the pole of the plane it is measured against.
        // The same obliquity eclipticPole uses, not the one for this date. The two
        // poles are only a fixed inclination apart if they are measured against the
        // same tilt; letting this one track the epoch put a few thousandths of a
        // degree between them for no gain, since the obliquity is what does not move.
        val raDec = SolarLunar.eclipticToEquatorial(
            node - 90.0, 90.0 - MOON_INCLINATION, SolarLunar.obliquity(0.0)
        )
        return SkyMath.raDecToVector(raDec[0], raDec[1])
    }

    /**
     * Where the Sun stands at the start of each month for a year ahead, so the line
     * reads as a calendar: the Sun covers about a degree a day, and the marks say
     * which part of the year each stretch of it belongs to.
     */
    fun eclipticMarks(nowJd: Double, zone: ZoneId): List<PathMark> {
        val first = today(nowJd, zone).withDayOfMonth(1)
        return eclipticMarkCache.get(nowJd) {
            (0 until ECLIPTIC_MONTHS).map { month ->
                val at = first.plusMonths(month.toLong())
                PathMark(
                    position(at, zone) { SolarLunar.sunPosition(it) },
                    at.format(MONTH_NAME),
                )
            }
        }
    }

    /**
     * Where the Moon stands at each of the next fortnight of midnights. It covers
     * thirteen degrees a day, so these are far enough apart to read and close
     * enough together to say which way along the path it is heading.
     */
    fun moonMarks(nowJd: Double, zone: ZoneId): List<PathMark> {
        val today = today(nowJd, zone)
        return moonMarkCache.get(nowJd) {
            (0 until MOON_DAYS).map { ahead ->
                val at = today.plusDays(ahead.toLong())
                PathMark(
                    position(at, zone) { SolarLunar.moonPosition(it) },
                    at.dayOfMonth.toString(),
                )
            }
        }
    }

    private fun today(nowJd: Double, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(epochMillisFromJulianDay(nowJd)).atZone(zone).toLocalDate()

    /** Where [body] stands at midnight on [date], as an equatorial unit vector. */
    private fun position(
        date: LocalDate, zone: ZoneId, body: (Double) -> DoubleArray,
    ): DoubleArray {
        val jd = julianDayFromEpochMillis(date.atStartOfDay(zone).toInstant().toEpochMilli())
        val raDec = body(jd)
        return SkyMath.raDecToVector(raDec[0], raDec[1])
    }

    fun eclipticRun(): FloatArray = ecliptic

    fun moonPathRun(jd: Double): FloatArray =
        moonPathCache.get(jd) { greatCircle(moonPathPole(jd)) }

    /**
     * Every direction at right angles to [pole], as a closed run of unit vectors.
     *
     * Two perpendicular directions in the plane are enough to sweep it: take any
     * vector the pole is not parallel to, cross it with the pole for the first, and
     * cross back for the second. The circle is then just a cosine of one plus a
     * sine of the other, with no trig per axis and no coordinate conversion.
     */
    fun greatCircle(pole: DoubleArray): FloatArray = circleAt(pole, 0.0)

    /**
     * Every direction [latDeg] away from the plane [pole] defines, as a closed run.
     *
     * At zero this is the great circle itself. Away from it the circle shrinks by
     * the cosine of the latitude and lifts along the pole by its sine, which is all
     * a circle of constant latitude is - so the band and the line it is drawn about
     * come out of one piece of geometry.
     */
    fun circleAt(pole: DoubleArray, latDeg: Double): FloatArray {
        // Anything not lying along the pole will do. The equatorial z axis is the
        // obvious pick and is never within five degrees of either pole here, but
        // the fallback costs one comparison and removes the only way this can fail.
        val seed = if (kotlin.math.abs(pole[2]) > 0.9) {
            doubleArrayOf(1.0, 0.0, 0.0)
        } else {
            doubleArrayOf(0.0, 0.0, 1.0)
        }

        val u = normalise(cross(seed, pole))
        val v = normalise(cross(pole, u))

        val ring = SkyMath.dcos(latDeg)
        val lift = SkyMath.dsin(latDeg)

        val count = 360 / SAMPLE + 1
        val run = FloatArray(count * 3)
        for (i in 0 until count) {
            val angle = (i * SAMPLE).toDouble()
            val c = SkyMath.dcos(angle) * ring
            val s = SkyMath.dsin(angle) * ring
            run[3 * i] = (c * u[0] + s * v[0] + lift * pole[0]).toFloat()
            run[3 * i + 1] = (c * u[1] + s * v[1] + lift * pole[1]).toFloat()
            run[3 * i + 2] = (c * u[2] + s * v[2] + lift * pole[2]).toFloat()
        }
        return run
    }

    private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

    private fun normalise(v: DoubleArray): DoubleArray {
        val len = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        return doubleArrayOf(v[0] / len, v[1] / len, v[2] / len)
    }
}

/** A dated point on one of the paths: where a body stands, and when. */
class PathMark(val vector: DoubleArray, val label: String)

/**
 * Something worked out once a day and kept until the date turns over.
 *
 * All three of these move too slowly to be worth redoing per frame and too fast to
 * pin to one epoch: the lunar node slides degrees in a month, and the date marks
 * change at midnight.
 */
private class Daily<T> {
    private var held: T? = null
    private var day = Long.MIN_VALUE

    fun get(jd: Double, build: () -> T): T {
        val today = floor(jd).toLong()
        held?.let { if (day == today) return it }
        return build().also {
            held = it
            day = today
        }
    }
}
