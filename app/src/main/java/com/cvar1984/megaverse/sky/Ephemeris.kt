package com.cvar1984.megaverse.sky

import com.cvar1984.megaverse.sky.SkyMath.dcos
import com.cvar1984.megaverse.sky.SkyMath.dsin
import com.cvar1984.megaverse.sky.SkyMath.dtan
import com.cvar1984.megaverse.sky.SkyMath.datan2
import com.cvar1984.megaverse.sky.SkyMath.norm360
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Low-precision Sun and Moon geocentric apparent positions (Meeus, abbreviated
 * series). Accuracy is well within what a wrist compass/magnetometer can resolve.
 */
object SolarLunar {
    fun centuriesSinceJ2000(jd: Double) = (jd - 2451545.0) / 36525.0

    fun obliquity(t: Double) =
        23.439291 - 0.0130042 * t - 0.00000016 * t * t + 0.000000504 * t * t * t

    /** Ecliptic longitude/latitude (deg) + obliquity (deg) -> [ra, dec] in degrees. */
    fun eclipticToEquatorial(lonDeg: Double, latDeg: Double, epsDeg: Double): DoubleArray {
        val sinDec = dsin(latDeg) * dcos(epsDeg) + dcos(latDeg) * dsin(epsDeg) * dsin(lonDeg)
        val dec = SkyMath.dasin(sinDec)
        val y = dsin(lonDeg) * dcos(epsDeg) - dtan(latDeg) * dsin(epsDeg)
        val x = dcos(lonDeg)
        return doubleArrayOf(norm360(datan2(y, x)), dec)
    }

    /** Longitude of the ascending node of the Moon's orbit, which nutation needs. */
    private fun nutationArgument(t: Double) = 125.04 - 1934.136 * t

    /**
     * The Sun's apparent ecliptic longitude in degrees. Pulled out on its own
     * because the Moon's phase is the gap between this and the Moon's.
     */
    fun sunLongitude(jd: Double): Double {
        val t = centuriesSinceJ2000(jd)
        val l0 = norm360(280.46646 + 36000.76983 * t + 0.0003032 * t * t)
        val m = norm360(357.52911 + 35999.05029 * t - 0.0001537 * t * t)
        val c = (1.914602 - 0.004817 * t - 0.000014 * t * t) * dsin(m) +
            (0.019993 - 0.000101 * t) * dsin(2 * m) +
            0.000289 * dsin(3 * m)
        return norm360(l0 + c - 0.00569 - 0.00478 * dsin(nutationArgument(t)))
    }

    /** [ra, dec] in degrees for the given Julian Day (UTC). */
    fun sunPosition(jd: Double): DoubleArray {
        val t = centuriesSinceJ2000(jd)
        val eps = obliquity(t) + 0.00256 * dcos(nutationArgument(t))
        return eclipticToEquatorial(sunLongitude(jd), 0.0, eps)
    }

    /**
     * The Moon's horizontal parallax in degrees: how far its apparent place shifts
     * between Earth's centre, which moonPosition works from, and a watch on the
     * surface 6378 km off that centre. It runs to about 0.95 degrees, roughly two
     * full-Moon widths, so the Moon is the only body where the correction matters.
     *
     * Earth's polar radius is 0.34% shorter than its equatorial one, which would
     * move this by about 12 arcseconds; far below anything a wrist compass can
     * resolve, so the flattening is ignored.
     */
    fun moonHorizontalParallax(jd: Double): Double {
        val t = centuriesSinceJ2000(jd)
        val d = norm360(297.8501921 + 445267.1114034 * t - 0.0018819 * t * t)
        val mp = norm360(134.9633964 + 477198.8675055 * t + 0.0087414 * t * t)
        return 0.9508 +
            0.0518 * dcos(mp) +
            0.0095 * dcos(2 * d - mp) +
            0.0078 * dcos(2 * d) +
            0.0028 * dcos(2 * mp)
    }

    /** [ra, dec] in degrees for the given Julian Day (UTC). */
    fun moonPosition(jd: Double): DoubleArray {
        val ecliptic = moonEcliptic(jd)
        return eclipticToEquatorial(
            ecliptic[0], ecliptic[1], obliquity(centuriesSinceJ2000(jd))
        )
    }

    /** The Moon's apparent ecliptic longitude in degrees. */
    fun moonLongitude(jd: Double) = moonEcliptic(jd)[0]

    /**
     * How far round from the Sun the Moon has moved, in degrees: 0 at new, 90 at
     * first quarter, 180 at full. One number carries both the lit fraction and
     * which way the phase is heading, which is why it is worked out in longitude
     * rather than as the angle between the two directions in the sky.
     */
    fun moonPhaseAngle(jd: Double) = norm360(moonLongitude(jd) - sunLongitude(jd))

    /** The lit fraction of the Moon's disc, 0 at new and 1 at full. */
    fun moonIllumination(jd: Double) = (1.0 - dcos(moonPhaseAngle(jd))) / 2.0

    /**
     * The name for that phase. The four turning points get a narrow band each,
     * because "First Quarter" means one moment rather than a week of the month.
     */
    fun moonPhaseName(jd: Double): String {
        val angle = moonPhaseAngle(jd)
        return when {
            angle < 11.25 || angle >= 348.75 -> "New"
            angle < 78.75 -> "Waxing Crescent"
            angle < 101.25 -> "First Quarter"
            angle < 168.75 -> "Waxing Gibbous"
            angle < 191.25 -> "Full"
            angle < 258.75 -> "Waning Gibbous"
            angle < 281.25 -> "Last Quarter"
            else -> "Waning Crescent"
        }
    }

    /** The Moon's ecliptic longitude and latitude in degrees. */
    private fun moonEcliptic(jd: Double): DoubleArray {
        val t = centuriesSinceJ2000(jd)

        val lp = norm360(218.3164477 + 481267.88123421 * t - 0.0015786 * t * t)
        val d = norm360(297.8501921 + 445267.1114034 * t - 0.0018819 * t * t)
        val m = norm360(357.5291092 + 35999.0502909 * t - 0.0001536 * t * t)
        val mp = norm360(134.9633964 + 477198.8675055 * t + 0.0087414 * t * t)
        val f = norm360(93.2720950 + 483202.0175233 * t - 0.0036539 * t * t)

        val dl = 6.288774 * dsin(mp) +
            1.274027 * dsin(2 * d - mp) +
            0.658314 * dsin(2 * d) +
            0.213618 * dsin(2 * mp) -
            0.185116 * dsin(m) -
            0.114332 * dsin(2 * f) +
            0.058793 * dsin(2 * d - 2 * mp) +
            0.057066 * dsin(2 * d - m - mp) +
            0.053322 * dsin(2 * d + mp) +
            0.045758 * dsin(2 * d - m) -
            0.040923 * dsin(m - mp) -
            0.034720 * dsin(d) -
            0.030383 * dsin(m + mp)

        val db = 5.128122 * dsin(f) +
            0.280602 * dsin(mp + f) +
            0.277693 * dsin(mp - f) +
            0.173237 * dsin(2 * d - f) +
            0.055413 * dsin(2 * d - mp + f) +
            0.046271 * dsin(2 * d - mp - f) +
            0.032573 * dsin(2 * d + f) +
            0.017198 * dsin(2 * mp) +
            0.009266 * dsin(2 * d + mp - f) +
            0.008822 * dsin(2 * d - 2 * mp)

        return doubleArrayOf(norm360(lp + dl), db)
    }
}

/**
 * Low-precision planetary positions (Mercury-Saturn) using approximate Keplerian
 * orbital elements, in the style popularized by Paul Schlyter's "How to compute
 * planetary positions" notes. Accuracy (arcminutes) far exceeds what a wrist
 * compass can resolve.
 */
object Planets {
    fun eccentricAnomaly(mDeg: Double, e: Double): Double {
        val rad2deg = Math.toDegrees(1.0)
        var eAnom = mDeg + rad2deg * e * dsin(mDeg) * (1.0 + e * dcos(mDeg))
        repeat(8) {
            val dM = mDeg - (eAnom - rad2deg * e * dsin(eAnom))
            val dE = dM / (1.0 - e * dcos(eAnom))
            eAnom += dE
            if (abs(dE) < 0.000001) return eAnom
        }
        return eAnom
    }

    /** Heliocentric ecliptic coordinates [xh, yh, zh] in AU. */
    fun heliocentric(
        nDeg: Double, iDeg: Double, wDeg: Double, a: Double, e: Double, mDeg: Double
    ): DoubleArray {
        val eAnom = eccentricAnomaly(mDeg, e)
        val xv = a * (dcos(eAnom) - e)
        val yv = a * sqrt(1.0 - e * e) * dsin(eAnom)
        val r = sqrt(xv * xv + yv * yv)
        val vw = datan2(yv, xv) + wDeg
        return doubleArrayOf(
            r * (dcos(nDeg) * dcos(vw) - dsin(nDeg) * dsin(vw) * dcos(iDeg)),
            r * (dsin(nDeg) * dcos(vw) + dcos(nDeg) * dsin(vw) * dcos(iDeg)),
            r * (dsin(vw) * dsin(iDeg))
        )
    }

    /**
     * Sun's geocentric position [xs, ys] in AU (the Earth->Sun vector), used to
     * turn planet heliocentric coordinates into geocentric ones.
     */
    fun sunGeocentric(d: Double): DoubleArray {
        val w = norm360(282.9404 + 0.0000470935 * d)
        val e = 0.016709 - 0.000000001151 * d
        val m = norm360(356.0470 + 0.9856002585 * d)
        val eAnom = eccentricAnomaly(m, e)
        val xv = dcos(eAnom) - e
        val yv = sqrt(1.0 - e * e) * dsin(eAnom)
        val r = sqrt(xv * xv + yv * yv)
        val lonSun = norm360(datan2(yv, xv) + w)
        return doubleArrayOf(r * dcos(lonSun), r * dsin(lonSun))
    }

    /**
     * Orbital elements [N, i, w, a, e, M] (degrees/AU) at day-number d
     * (days since 2000-01-00 = JD 2451543.5).
     */
    fun elementsFor(id: String, d: Double): DoubleArray = when (id) {
        "mercury" -> doubleArrayOf(
            norm360(48.3313 + 0.0000324587 * d),
            7.0047 + 0.00000005 * d,
            norm360(29.1241 + 0.0000101444 * d),
            0.387098,
            0.205635 + 0.000000000559 * d,
            norm360(168.6562 + 4.0923344368 * d)
        )
        "venus" -> doubleArrayOf(
            norm360(76.6799 + 0.0000246590 * d),
            3.3946 + 0.0000000275 * d,
            norm360(54.8910 + 0.0000138374 * d),
            0.723330,
            0.006773 - 0.000000001302 * d,
            norm360(48.0052 + 1.6021302244 * d)
        )
        "mars" -> doubleArrayOf(
            norm360(49.5574 + 0.0000211081 * d),
            1.8497 - 0.0000000178 * d,
            norm360(286.5016 + 0.0000292961 * d),
            1.523688,
            0.093405 + 0.000000002516 * d,
            norm360(18.6021 + 0.5240207766 * d)
        )
        "jupiter" -> doubleArrayOf(
            norm360(100.4542 + 0.0000276854 * d),
            1.3030 - 0.0000001557 * d,
            norm360(273.8777 + 0.0000164505 * d),
            5.20256,
            0.048498 + 0.000000004469 * d,
            norm360(19.8950 + 0.0830853001 * d)
        )
        else -> doubleArrayOf( // saturn
            norm360(113.6634 + 0.0000238980 * d),
            2.4886 - 0.0000001081 * d,
            norm360(339.3939 + 0.0000297661 * d),
            9.55475,
            0.055546 - 0.000000009499 * d,
            norm360(316.9670 + 0.0334442282 * d)
        )
    }

    /** [ra, dec] in degrees for the given Julian Day (UTC). */
    fun planetPosition(id: String, jd: Double): DoubleArray {
        val d = jd - 2451543.5
        val sun = sunGeocentric(d)
        val el = elementsFor(id, d)
        val helio = heliocentric(el[0], el[1], el[2], el[3], el[4], el[5])

        val xg = helio[0] + sun[0]
        val yg = helio[1] + sun[1]
        val zg = helio[2]

        val lon = norm360(datan2(yg, xg))
        val lat = datan2(zg, sqrt(xg * xg + yg * yg))
        val eps = SolarLunar.obliquity((jd - 2451545.0) / 36525.0)
        return SolarLunar.eclipticToEquatorial(lon, lat, eps)
    }
}
