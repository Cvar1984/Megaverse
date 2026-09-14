package com.cvar1984.megaverse

/**
 * Watch orientations to test against, laid out the way SensorManager gives them:
 * row-major 3x3, rows the world axes (east, north, up) and columns the device axes
 * (x right across the glass, y towards 12 o'clock, z out through the glass).
 *
 * Every one of these has to be a real rotation, not a reflection. SensorManager
 * only ever produces right-handed frames, so a fixture with a mirror in it would
 * put the sky on the wrong side of the screen and take any left/right assertion
 * written against it with it.
 */
object Frames {
    /** Screen flat up on a table, 12 o'clock pointing north. The aim is the nadir. */
    val FLAT_SCREEN_UP = floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f,
    )

    /**
     * Held up on edge with the screen facing north and 12 o'clock up, so the back -
     * the aim - looks due south and level.
     */
    val LEVEL_FACING_SOUTH = floatArrayOf(
        // East is device -x: looking at a screen that faces north you are facing
        // south yourself, and your right hand then points west.
        -1f, 0f, 0f,
        0f, 0f, 1f,
        0f, 1f, 0f,
    )

    /**
     * The same aim, wrist rolled a quarter turn. The aim axis is unmoved, so the
     * written guidance must not notice, while the picture rolls with it.
     */
    val ROLLED_FACING_SOUTH = floatArrayOf(
        0f, 1f, 0f,
        0f, 0f, 1f,
        1f, 0f, 0f,
    )

    val all = listOf(
        "flat screen up" to FLAT_SCREEN_UP,
        "level facing south" to LEVEL_FACING_SOUTH,
        "rolled facing south" to ROLLED_FACING_SOUTH,
    )

    /** A rotation has determinant +1; a reflection has -1. */
    fun determinant(r: FloatArray): Double {
        val a = r.map { it.toDouble() }
        return a[0] * (a[4] * a[8] - a[5] * a[7]) -
            a[1] * (a[3] * a[8] - a[5] * a[6]) +
            a[2] * (a[3] * a[7] - a[4] * a[6])
    }
}
