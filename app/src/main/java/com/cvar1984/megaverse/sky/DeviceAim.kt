package com.cvar1984.megaverse.sky

import com.cvar1984.megaverse.sky.SkyMath.dcos
import com.cvar1984.megaverse.sky.SkyMath.dsin
import kotlin.math.sqrt

/**
 * Turns the watch into a viewfinder. You hold it up with the screen toward you and
 * the back toward the sky, and the object is drawn where it sits in the sky behind
 * the watch, the same idea as a phone astronomy app pointed with its camera.
 *
 * The aim axis is therefore the watch's Z axis, straight out through the back, not
 * the 12 o'clock edge. That changes what roll means: looking through a window,
 * turning your wrist turns what you see through it, so the picture is built in the
 * watch's own frame and does rotate with the wrist. The written turn/tilt guidance
 * stays measured against gravity, because it describes how to move your arm and
 * should not care how the watch is rotated in your hand.
 *
 * [frame] throughout is Android's rotation matrix: row-major 3x3, rows the world
 * axes (East, North, Up) and columns the device axes (x right, y 12 o'clock,
 * z out through the glass). SensorManager builds it; [applyDeclination] swings it
 * from magnetic north onto true.
 */
object DeviceAim {
    /**
     * Which way along Z the back of the case lies. Android's Z points out through
     * the screen, so the back - the face you aim at the sky - is the other way.
     *
     * This sign applies to forward alone. Flipping the sideways and forward
     * components together cancels in the sideways divide and can only invert up and
     * down. Inverting forward turns both screen axes over at once, which shows up
     * as the object following the watch instead of sliding against it.
     */
    const val BACK_SIGN = -1.0

    /**
     * Below this the aim axis is within ~2 degrees of vertical, where "which way is
     * right" stops being defined.
     */
    const val MIN_HORIZONTAL = 0.035

    /**
     * An object further off the aim than about 84 degrees is level with the back of
     * the watch or behind it, where a perspective projection has nothing sensible
     * to say.
     */
    const val MIN_FORWARD = 0.1

    /**
     * The same frame swung from magnetic north round to true north.
     *
     * Every object placed on this screen comes from RA/Dec and sidereal time, which
     * are true by construction. A magnetic frame would turn the whole drawn sky by
     * the local declination against the numbers printed underneath it: a few
     * degrees in most places, twenty in parts of Canada, against an eight degree
     * lock. The picture is built from this frame, so correcting only the heading
     * readout would still leave it wrong.
     *
     * Declination is measured east-positive, so magnetic north sits that far east
     * of true and true north is the same angle back the other way. Up is a rotation
     * axis here, so it comes through untouched.
     */
    fun applyDeclination(frame: FloatArray, declinationDeg: Double): FloatArray {
        if (declinationDeg == 0.0) return frame
        val c = dcos(declinationDeg).toFloat()
        val s = dsin(declinationDeg).toFloat()
        val out = FloatArray(9)
        for (k in 0..2) {
            out[k] = frame[k] * c + frame[3 + k] * s          // east
            out[3 + k] = frame[3 + k] * c - frame[k] * s      // north
            out[6 + k] = frame[6 + k]                         // up
        }
        return out
    }

    /**
     * Where the back of the case points, as a world East-North-Up unit vector. It
     * is the device's -Z axis carried into the world, which is the third column of
     * the frame negated.
     */
    fun aimDirection(frame: FloatArray): DoubleArray = doubleArrayOf(
        BACK_SIGN * frame[2], BACK_SIGN * frame[5], BACK_SIGN * frame[8]
    )

    /**
     * How high the back of the watch points, in degrees above the horizon. Shown on
     * the pointer screen, so a wrong sign convention is visible rather than silently
     * mirroring everything.
     */
    fun aimElevation(frame: FloatArray) = SkyMath.dasin(aimDirection(frame)[2])

    /** The true-north azimuth the back of the watch points along, in degrees. */
    fun aimHeading(frame: FloatArray): Double {
        val d = aimDirection(frame)
        return SkyMath.norm360(SkyMath.datan2(d[0], d[1]))
    }

    /**
     * Where a sky direction sits as seen through the back of the watch, as
     * [right, up, forward]: how far it lies to the right of the screen, how far up
     * it, and how far out in front of the back face. Forward at or below zero means
     * it is behind you. All three are direction cosines, ready for a perspective
     * divide.
     */
    fun viewOffset(frame: FloatArray, tE: Double, tN: Double, tU: Double): DoubleArray {
        val x = tE * frame[0] + tN * frame[3] + tU * frame[6]
        val y = tE * frame[1] + tN * frame[4] + tU * frame[7]
        val z = tE * frame[2] + tN * frame[5] + tU * frame[8]
        // X is 3 o'clock and Y is 12 o'clock, so both already read as the screen
        // does. Only forward turns round, because the aim is out through the back.
        return doubleArrayOf(x, y, BACK_SIGN * z)
    }

    /**
     * Where a sky direction lands on screen, or null when it is not out in front of
     * the watch's back. [view] is [cx, cy, focal], where focal is the
     * pixels-per-radian scale that sets how wide a piece of sky the screen covers.
     * Dividing by "forward" lines the sky up the way a camera would, instead of
     * only pointing in the right direction.
     */
    fun screenPoint(frame: FloatArray, tE: Double, tN: Double, tU: Double, view: FloatArray): FloatArray? =
        toScreen(viewOffset(frame, tE, tN, tU), view)

    /**
     * The same divide for a caller that already holds the offset. Anything drawing
     * a face - a phase, a set of rings - needs the offset to orient it as well as to
     * place it, and working the offset out twice to get both would be silly.
     */
    fun toScreen(offset: DoubleArray, view: FloatArray): FloatArray? {
        val forward = offset[2]
        if (forward < MIN_FORWARD) return null
        val focal = view[2]
        return floatArrayOf(
            view[0] + focal * (offset[0] / forward).toFloat(),
            view[1] - focal * (offset[1] / forward).toFloat()
        )
    }

    /**
     * The frame the watch aims in, as world East-North-Up vectors:
     * [dE,dN,dU, rE,rN,rU, uE,uN,uU] for the direction it points, plus "right" and
     * "up" perpendicular to it. Null if it is aimed too near vertical, where "which
     * way is right" stops being defined.
     *
     * Right and up are built from gravity, not from the watch body, which keeps
     * roll out of the written guidance: how to swing your arm to reach the object
     * does not depend on how the watch is turned in your hand.
     */
    fun aimBasis(elevDeg: Double, headingDeg: Double): DoubleArray? {
        val cosElev = dcos(elevDeg)
        if (cosElev < MIN_HORIZONTAL) return null
        val dE = cosElev * dsin(headingDeg)
        val dN = cosElev * dcos(headingDeg)
        val dU = dsin(elevDeg)

        // "Right" of the aim: horizontal, perpendicular to it. This is d x up,
        // which works out to (dN, -dE, 0), of length cos(elevation).
        val rE = dN / cosElev
        val rN = -dE / cosElev

        // "Up" from the aim: perpendicular to both, so tilting along it climbs
        // straight toward the zenith rather than sideways. This is right x d.
        return doubleArrayOf(
            dE, dN, dU,
            rE, rN, 0.0,
            rN * dU, -rE * dU, rE * dN - rN * dE
        )
    }

    /**
     * Puts a world direction into that frame, as [turnDeg, tiltDeg, forward]: how
     * far right and how far up it sits from the aim, plus the cosine of the angle
     * off the aim axis, which is negative when it is behind the watch.
     */
    fun project(basis: DoubleArray, e: Double, n: Double, u: Double): DoubleArray {
        val alongAim = e * basis[0] + n * basis[1] + u * basis[2]
        val alongRight = e * basis[3] + n * basis[4] + u * basis[5]
        val alongUp = e * basis[6] + n * basis[7] + u * basis[8]

        // Tilt is measured off the aim's own horizontal plane, not straight off the
        // aim axis. Both amount to the same thing near the object, but this stays
        // well defined when it is far to one side, where "aim" and "up" both fall to
        // zero and dividing one by the other is meaningless.
        val acrossAim = sqrt(alongAim * alongAim + alongRight * alongRight)

        return doubleArrayOf(
            SkyMath.datan2(alongRight, alongAim),
            SkyMath.datan2(alongUp, acrossAim),
            alongAim
        )
    }

    /**
     * Which of [directions] the watch is pointing closest to, as an index, or -1 if
     * the nearest is further off the aim than [withinDeg].
     *
     * Compared as cosines rather than angles: the arccosine is monotonic, so the
     * largest dot product is the smallest angle, and skipping it saves an inverse
     * trig call per object on a list walked every frame.
     */
    fun nearest(aim: DoubleArray, directions: List<DoubleArray>, withinDeg: Double): Int {
        var best = -1
        var bestCos = dcos(withinDeg)
        for (i in directions.indices) {
            val d = directions[i]
            val cos = aim[0] * d[0] + aim[1] * d[1] + aim[2] * d[2]
            if (cos > bestCos) {
                bestCos = cos
                best = i
            }
        }
        return best
    }

    /**
     * The frame for vectors kept in some other set of axes. [rows] maps those axes
     * into East-North-Up, row-major with rows E, N and U. Folding it in once a frame
     * lets a whole grid kept in that other set go straight through the draw loop.
     */
    fun rotateFrame(frame: FloatArray, rows: FloatArray): FloatArray {
        val out = FloatArray(9)
        for (c in 0..2) {
            for (k in 0..2) {
                out[3 * c + k] =
                    frame[k] * rows[c] + frame[3 + k] * rows[3 + c] + frame[6 + k] * rows[6 + c]
            }
        }
        return out
    }
}
