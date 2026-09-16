package com.cvar1984.megaverse

import com.cvar1984.megaverse.sky.Galactic
import com.cvar1984.megaverse.sky.SkyMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The galactic frame, and the one matrix the shader walks every pixel with.
 *
 * A rotation that is subtly wrong still draws a plausible band across the sky - it
 * is simply the wrong part of the sky - so these check it against positions an
 * almanac agrees with rather than against how it looks.
 */
class GalacticTest {

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun apply(m: DoubleArray, v: DoubleArray) = doubleArrayOf(
        m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
        m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
        m[6] * v[0] + m[7] * v[1] + m[8] * v[2],
    )

    private fun applyF(m: FloatArray, v: DoubleArray) = doubleArrayOf(
        m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
        m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
        m[6] * v[0] + m[7] * v[1] + m[8] * v[2],
    )

    @Test
    fun theGalacticCentreSitsAtTheOriginOfTheFrame() {
        // By construction the centre is galactic longitude and latitude zero, which
        // in cartesian is the x axis and nothing else.
        // Within the third of an arcsecond the two published positions are rounded
        // by: squaring the frame up moves the centre off its own axis by exactly
        // that much, which is the price of the matrix being a true rotation.
        val centre = SkyMath.raDecToVector(Galactic.CENTRE_RA, Galactic.CENTRE_DEC)
        val g = apply(Galactic.fromEquatorial, centre)
        assertEquals(1.0, g[0], 1e-9)
        assertEquals(0.0, g[1], 1e-5)
        assertEquals(0.0, g[2], 1e-5)
    }

    @Test
    fun theGalacticPoleSitsOnTheZAxis() {
        val pole = SkyMath.raDecToVector(Galactic.POLE_RA, Galactic.POLE_DEC)
        val g = apply(Galactic.fromEquatorial, pole)
        assertEquals(1.0, g[2], 1e-9)
        assertEquals(0.0, sqrt(g[0] * g[0] + g[1] * g[1]), 1e-9)
    }

    @Test
    fun theFrameIsARotationAndNotAReflection() {
        // Three orthonormal rows and a determinant of +1. A reflection here would
        // mirror the whole band left to right and still look entirely plausible.
        val m = Galactic.fromEquatorial
        for (r in 0..2) {
            val row = doubleArrayOf(m[3 * r], m[3 * r + 1], m[3 * r + 2])
            assertEquals("row $r unit length", 1.0, sqrt(dot(row, row)), 1e-9)
        }
        for (a in 0..2) for (b in a + 1..2) {
            val ra = doubleArrayOf(m[3 * a], m[3 * a + 1], m[3 * a + 2])
            val rb = doubleArrayOf(m[3 * b], m[3 * b + 1], m[3 * b + 2])
            assertEquals("rows $a,$b perpendicular", 0.0, dot(ra, rb), 1e-9)
        }
        val det = m[0] * (m[4] * m[8] - m[5] * m[7]) -
            m[1] * (m[3] * m[8] - m[5] * m[6]) +
            m[2] * (m[3] * m[7] - m[4] * m[6])
        assertEquals(1.0, det, 1e-9)
    }

    @Test
    fun theGalacticPlaneIsTiltedRightOverFromTheCelestialEquator() {
        // About 63 degrees between the two poles. The Milky Way crosses the sky at
        // a steep angle to the equator, which is why it can stand almost upright at
        // some hours and lie almost flat at others.
        val celestial = doubleArrayOf(0.0, 0.0, 1.0)
        val pole = SkyMath.raDecToVector(Galactic.POLE_RA, Galactic.POLE_DEC)
        assertEquals(62.87, SkyMath.dacos(dot(celestial, pole)), 0.05)
    }

    @Test
    fun starsKnownToLieInTheBandComeOutNearZeroLatitude() {
        // Deneb and Sadr in Cygnus, Acrux in the Southern Cross: all three sit in
        // the Milky Way as anyone can see it. Arcturus, Polaris and Vega sit clear
        // of it. If the frame were wrong the two groups would not separate.
        //
        // Orion is deliberately not in the first list. It looks like it ought to be,
        // but the band passes beside it - Alnilam is seventeen degrees off the plane.
        val inBand = listOf(310.358 to 45.280, 305.557 to 40.257, 186.650 to -63.099)
        for ((ra, dec) in inBand) {
            val lat = SkyMath.dasin(apply(Galactic.fromEquatorial, SkyMath.raDecToVector(ra, dec))[2])
            assertTrue("ra $ra sat $lat off the plane", abs(lat) < 5.0)
        }
        val outOfBand = listOf(213.916 to 19.182, 37.955 to 89.264, 279.234 to 38.784)
        for ((ra, dec) in outOfBand) {
            val lat = SkyMath.dasin(apply(Galactic.fromEquatorial, SkyMath.raDecToVector(ra, dec))[2])
            assertTrue("ra $ra sat only $lat off the plane", abs(lat) > 15.0)
        }
    }

    @Test
    fun theShaderMatrixAgreesWithGoingRoundTheLongWay() {
        // The shader folds three rotations into one so it can do three dot products
        // a pixel. That fold has to land where taking each step separately does.
        val frame = Frames.LEVEL_FACING_SOUTH
        val lat = 51.5
        val lst = 200.0
        val folded = Galactic.fromDevice(frame, lat, lst)

        for (ra in listOf(0.0, 77.0, 190.0, 300.0)) {
            for (dec in listOf(-60.0, -10.0, 25.0, 70.0)) {
                // the long way: equatorial -> ENU -> the watch's own axes
                val enu = SkyMath.raDecToAltAz(ra, dec, lat, lst).let {
                    SkyMath.horizontalToEnu(it[1], it[0])
                }
                val device = com.cvar1984.megaverse.sky.DeviceAim
                    .viewOffset(frame, enu[0], enu[1], enu[2])
                // viewOffset turns the forward axis over; undo that to get the
                // plain device direction the shader works with.
                val ray = doubleArrayOf(device[0], device[1], -device[2])

                val viaShader = applyF(folded, ray)
                val direct = apply(Galactic.fromEquatorial, SkyMath.raDecToVector(ra, dec))
                for (k in 0..2) {
                    assertEquals("ra $ra dec $dec axis $k", direct[k], viaShader[k], 1e-5)
                }
            }
        }
    }
}
