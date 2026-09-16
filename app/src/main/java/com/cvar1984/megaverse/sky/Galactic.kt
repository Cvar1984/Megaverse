package com.cvar1984.megaverse.sky

/**
 * The galactic frame: the plane the Milky Way lies in.
 *
 * Built from two directions rather than from a table of numbers - the north
 * galactic pole and the galactic centre - because that is what the frame *is*, and
 * two catalogue positions are easier to check against an almanac than nine matrix
 * entries are.
 */
object Galactic {
    /** North galactic pole, J2000. */
    const val POLE_RA = 192.85948
    const val POLE_DEC = 27.12825

    /** The galactic centre, in Sagittarius, J2000. */
    const val CENTRE_RA = 266.40510
    const val CENTRE_DEC = -28.93617

    /**
     * Rows x, y, z of the galactic frame written in equatorial components, so that
     * multiplying an equatorial unit vector by this gives galactic cartesian.
     *
     * x points at the centre, z at the pole, y completes a right-handed set.
     */
    val fromEquatorial: DoubleArray by lazy(LazyThreadSafetyMode.NONE) {
        val z = SkyMath.raDecToVector(POLE_RA, POLE_DEC)
        val centre = SkyMath.raDecToVector(CENTRE_RA, CENTRE_DEC)

        // The two published positions are not quite at right angles to each other -
        // they are rounded, and land about a third of an arcsecond out. Left alone
        // that makes this a very slight skew rather than a rotation, so the centre
        // is squared up against the pole before the third axis is taken from them.
        val skew = z[0] * centre[0] + z[1] * centre[1] + z[2] * centre[2]
        val x = normalise(DoubleArray(3) { centre[it] - z[it] * skew })

        val y = doubleArrayOf(
            z[1] * x[2] - z[2] * x[1],
            z[2] * x[0] - z[0] * x[2],
            z[0] * x[1] - z[1] * x[0],
        )
        doubleArrayOf(x[0], x[1], x[2], y[0], y[1], y[2], z[0], z[1], z[2])
    }

    /**
     * One matrix taking a direction in the watch's own axes straight to galactic
     * cartesian, so a shader walking every pixel does three dot products and no
     * coordinate conversions.
     *
     * Three rotations folded together: the watch frame back out to East-North-Up,
     * up to equatorial, and round to galactic.
     */
    fun fromDevice(frame: FloatArray, latDeg: Double, lstDeg: Double): FloatArray {
        // frame's rows are the world axes in device components, so reading it as a
        // matrix turns a device direction into East-North-Up.
        val enuFromDevice = DoubleArray(9) { frame[it].toDouble() }

        // equatorialToEnu maps the other way and is orthonormal, so its transpose
        // is its inverse.
        val m = SkyMath.equatorialToEnu(latDeg, lstDeg)
        val equatorialFromEnu = DoubleArray(9)
        for (r in 0..2) for (c in 0..2) equatorialFromEnu[3 * r + c] = m[3 * c + r].toDouble()

        val out = multiply(multiply(fromEquatorial, equatorialFromEnu), enuFromDevice)
        return FloatArray(9) { out[it].toFloat() }
    }

    private fun normalise(v: DoubleArray): DoubleArray {
        val len = kotlin.math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        return DoubleArray(3) { v[it] / len }
    }

    /** Row-major 3x3 product. */
    private fun multiply(a: DoubleArray, b: DoubleArray): DoubleArray {
        val out = DoubleArray(9)
        for (r in 0..2) for (c in 0..2) {
            var s = 0.0
            for (k in 0..2) s += a[3 * r + k] * b[3 * k + c]
            out[3 * r + c] = s
        }
        return out
    }
}
