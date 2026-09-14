package com.cvar1984.megaverse.sky

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * Time, coordinate conversion and the two atmospheric corrections.
 *
 * Everything here is degrees in and degrees out, which is what the catalogue and
 * the readouts are both written in. The original ran on a 32-bit VM and had to
 * force Julian Days into 64-bit by hand; Kotlin's Double is already 64-bit, so
 * that care is now the default rather than an annotation.
 */
object SkyMath {
    fun dsin(deg: Double) = sin(Math.toRadians(deg))
    fun dcos(deg: Double) = cos(Math.toRadians(deg))
    fun dtan(deg: Double) = tan(Math.toRadians(deg))

    fun dasin(x: Double) = Math.toDegrees(asin(x.coerceIn(-1.0, 1.0)))
    fun dacos(x: Double) = Math.toDegrees(acos(x.coerceIn(-1.0, 1.0)))
    fun datan2(y: Double, x: Double) = Math.toDegrees(atan2(y, x))

    /** Normalise degrees to [0,360). */
    fun norm360(deg: Double): Double {
        val d = deg - 360.0 * floor(deg / 360.0)
        return if (d < 0) d + 360.0 else d
    }

    /** Normalise degrees to (-180,180]. */
    fun norm180(deg: Double) = norm360(deg + 180.0) - 180.0

    /** Greenwich Mean Sidereal Time in degrees for a given Julian Day. */
    fun gmst(jd: Double): Double {
        val days = jd - 2451545.0
        val t = days / 36525.0
        return norm360(
            280.46061837 + 360.98564736629 * days + 0.000387933 * t * t - (t * t * t) / 38710000.0
        )
    }

    /** Local Sidereal Time in degrees (east-positive longitude, degrees). */
    fun lst(jd: Double, lonDeg: Double) = norm360(gmst(jd) + lonDeg)

    /**
     * The rotation from the equatorial frame into East-North-Up, as a matrix: rows
     * E, N and U, columns the equatorial axes. Worked out once a frame and applied
     * to fixed unit vectors, it costs nine multiplications a point instead of the
     * four trig calls going round by altitude and azimuth would.
     */
    fun equatorialToEnu(latDeg: Double, lstDeg: Double): FloatArray {
        val sinL = dsin(lstDeg).toFloat()
        val cosL = dcos(lstDeg).toFloat()
        val sinP = dsin(latDeg).toFloat()
        val cosP = dcos(latDeg).toFloat()
        return floatArrayOf(
            -sinL, cosL, 0.0f,
            -sinP * cosL, -sinP * sinL, cosP,
            cosP * cosL, cosP * sinL, sinP
        )
    }

    /** A catalogue position as a unit vector in the equatorial frame. */
    fun raDecToVector(raDeg: Double, decDeg: Double): DoubleArray {
        val cosDec = dcos(decDeg)
        return doubleArrayOf(cosDec * dcos(raDeg), cosDec * dsin(raDeg), dsin(decDeg))
    }

    /** Azimuth/altitude (deg) as a world East-North-Up unit vector. */
    fun horizontalToEnu(azDeg: Double, altDeg: Double): DoubleArray {
        val cosAlt = dcos(altDeg)
        return doubleArrayOf(cosAlt * dsin(azDeg), cosAlt * dcos(azDeg), dsin(altDeg))
    }

    /**
     * How much the atmosphere lifts an object above its true height, in degrees
     * (Bennett's formula). About 0.57 degrees right at the horizon, 0.09 at ten
     * degrees up, and negligible overhead, so it matters most for objects just
     * clearing the skyline, which are the hardest to find anyway.
     */
    fun refraction(altDeg: Double): Double {
        if (altDeg < -2.0) return 0.0
        val denom = (altDeg + 7.31 / (altDeg + 4.4)).coerceAtLeast(0.1)
        val r = (1.0 / dtan(denom)) / 60.0
        if (r < 0.0) return 0.0
        return r.coerceAtMost(1.0)
    }

    /**
     * Where an object appears from the ground, given its height as seen from
     * Earth's centre. Parallax pushes it down, by more the closer it is, and
     * refraction lifts it back up, by more the lower it is. Both act along the
     * vertical circle, so the azimuth is untouched.
     */
    fun apparentAltitude(geocentricAltDeg: Double, horizontalParallaxDeg: Double): Double {
        val alt = geocentricAltDeg - horizontalParallaxDeg * dcos(geocentricAltDeg)
        return alt + refraction(alt)
    }

    /**
     * RA/Dec (deg) + observer latitude (deg) + LST (deg) to [altitude, azimuth] in
     * degrees. Azimuth is measured from North, clockwise through East.
     */
    fun raDecToAltAz(raDeg: Double, decDeg: Double, latDeg: Double, lstDeg: Double): DoubleArray {
        val h = norm180(lstDeg - raDeg)

        // Clamped before the arcsine, the way dasin and dacos already are. An
        // object passing close to overhead makes this sum come to a shade over 1 in
        // floating point, and asin outside its domain is not a number, which then
        // poisons the altitude, the azimuth and everything drawn from either.
        val sinAlt = (dsin(decDeg) * dsin(latDeg) + dcos(decDeg) * dcos(latDeg) * dcos(h))
            .coerceIn(-1.0, 1.0)

        val altRad = asin(sinAlt)
        val alt = Math.toDegrees(altRad)
        val cosAlt = cos(altRad)
        val az = if (abs(cosAlt) < 0.000001) {
            0.0
        } else {
            val a = dacos((dsin(decDeg) - dsin(latDeg) * sin(altRad)) / (dcos(latDeg) * cosAlt))
            if (dsin(h) > 0) 360.0 - a else a
        }
        return doubleArrayOf(alt, az)
    }
}

/**
 * Julian Day straight from a Unix epoch millisecond count. JD 2440587.5 is
 * 1970-01-01T00:00:00Z, so the whole conversion is one division and one offset,
 * with no calendar object and no time zone to get wrong.
 *
 * Unix time ignores leap seconds where UTC counts them, which puts this under a
 * second out: four thousandths of a degree of sky rotation, far below anything a
 * wrist compass can resolve. [SkyMath.julianDay] is the same value by the
 * calendrical route, and the tests hold the two together.
 */
fun julianDayFromEpochMillis(millis: Long): Double = millis / 86_400_000.0 + 2440587.5

/**
 * The inverse of [julianDayFromEpochMillis], for putting an answer back on a clock.
 *
 * Rounded rather than truncated. A Julian Day this century is about 2.46 million,
 * which leaves a Double just barely enough mantissa for millisecond resolution, so
 * the trip out and back lands a fraction of a millisecond short and truncation turns
 * that into a whole one - which is how 1900000000000 came back as ...999.
 */
fun epochMillisFromJulianDay(jd: Double): Long =
    Math.round((jd - 2440587.5) * 86_400_000.0)
