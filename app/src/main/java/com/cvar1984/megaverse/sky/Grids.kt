package com.cvar1984.megaverse.sky

/** One line of a grid: the colour it is drawn in, and a flat run of unit-vector triples. */
class GridLine(val color: Int, val points: FloatArray)

/**
 * The horizon coordinate grid: circles of equal altitude, and the vertical circles
 * running between zenith and nadir.
 *
 * This is the frame you are standing in rather than the one the stars turn in, so
 * it stays put as the night passes and reads directly: the horizon is the horizon,
 * north is north, and straight up is the middle of the sky. Rest the watch flat on
 * a table and the screen faces the zenith while the aim points at the nadir, so the
 * vertical circles should all converge on the centre of the display.
 *
 * The mesh is built once per spacing and kept. Nothing in azimuth/altitude to
 * East-North-Up depends on the clock, on where you stand, or on how the watch is
 * held, so the same few hundred vectors come out every frame. Only the rotation
 * into the watch's axes is redone per frame.
 */
object HorizonGrid {
    const val ALT_LIMIT = 60    // highest and lowest circle of equal altitude drawn
    const val AZ_SAMPLE = 20    // plotted point spacing round a circle of equal altitude
    const val ALT_SAMPLE = 20   // plotted point spacing along a vertical circle

    // Greens, so the frame you are standing in tells apart at a glance from the
    // blues the equatorial grid draws. One hue, three brightnesses: the horizon is
    // the line you steer by, so it is the brightest; below it the ground is in the
    // way, so those circles are the dimmest.
    const val HORIZON_COLOR = 0xFF00FF00.toInt()
    const val LINE_COLOR = 0xFF00AA00.toInt()
    const val BELOW_COLOR = 0xFF006600.toInt()

    private var mesh: List<GridLine>? = null
    private var meshStep = 0

    fun meshFor(step: Int): List<GridLine> {
        // Off is a value in the settings ring, so it arrives here the same way 60
        // does, and stepping outwards by nothing never reaches the limit. The old
        // mesh is left cached, so switching the grid back on is free.
        if (step <= 0) return emptyList()
        mesh?.let { if (meshStep == step) return it }

        // Let go of the old mesh before building the new one. Holding both at once
        // doubles the peak.
        mesh = null

        val lines = ArrayList<GridLine>()
        // Counted outwards from the horizon rather than up from the bottom, so the
        // horizon itself is always one of the lines whatever the spacing is set to.
        var alt = 0
        while (alt <= ALT_LIMIT) {
            lines.add(GridLine(altitudeColor(alt), altitudeRun(alt.toDouble())))
            if (alt != 0) lines.add(GridLine(altitudeColor(-alt), altitudeRun(-alt.toDouble())))
            alt += step
        }
        var az = 0
        while (az < 360) {
            lines.add(GridLine(LINE_COLOR, verticalRun(az.toDouble())))
            az += step
        }

        mesh = lines
        meshStep = step
        return lines
    }

    /** A circle of equal altitude, running parallel to the horizon all the way round. */
    private fun altitudeRun(altDeg: Double) = run(360 / AZ_SAMPLE + 1) { i ->
        SkyMath.horizontalToEnu((i * AZ_SAMPLE).toDouble(), altDeg)
    }

    /**
     * A vertical circle: straight up the sky from nadir to zenith on one bearing.
     * Sampled right to both poles, so they all meet at the point overhead and the
     * point underfoot.
     */
    private fun verticalRun(azDeg: Double) = run(180 / ALT_SAMPLE + 1) { i ->
        SkyMath.horizontalToEnu(azDeg, (-90 + i * ALT_SAMPLE).toDouble())
    }

    /**
     * The horizon is drawn brighter than the other circles and the ones below it
     * dimmer, so the grid reads as one thing with a near side and a far side.
     */
    private fun altitudeColor(altDeg: Int) = when {
        altDeg == 0 -> HORIZON_COLOR
        altDeg < 0 -> BELOW_COLOR
        else -> LINE_COLOR
    }

    fun cardinalName(azDeg: Int) = when (azDeg) {
        0 -> "N"
        90 -> "E"
        180 -> "S"
        else -> "W"
    }
}

/**
 * The equatorial coordinate grid: circles of equal declination, and the hour
 * circles running between the celestial poles.
 *
 * This is the frame the stars are fixed in rather than the one you are standing in.
 * It turns with the sky through the night and carries the objects round with it, so
 * a star sits at the same place on this grid at every hour, which is why a
 * catalogue gives positions in it. Where HorizonGrid answers "how high, and which
 * way", this answers "where among the stars".
 *
 * The points are held in the equatorial frame, where they never move, and each
 * frame turns the whole grid with one matrix folded into the watch axes: nine
 * multiplications a point. Refraction is not applied: it is a fraction of a degree
 * at the horizon and nothing higher up, under the width of these lines.
 */
object EquatorialGrid {
    const val DEC_LIMIT = 60    // highest and lowest circle of equal declination drawn
    const val RA_SAMPLE = 20    // plotted point spacing round a circle of equal declination
    const val DEC_SAMPLE = 20   // plotted point spacing along an hour circle

    // Blues, against the greens HorizonGrid draws, and kept dimmer than those: the
    // horizon frame is the one you steer by, and this sits behind it. The celestial
    // equator gets the brighter of the two.
    const val EQUATOR_COLOR = 0xFF0000AA.toInt()
    const val LINE_COLOR = 0xFF000055.toInt()

    private var mesh: List<GridLine>? = null
    private var meshStep = 0

    fun meshFor(step: Int): List<GridLine> {
        // Off never reaches the limit by stepping outwards zero degrees at a time.
        if (step <= 0) return emptyList()
        mesh?.let { if (meshStep == step) return it }
        mesh = null

        val lines = ArrayList<GridLine>()
        // Counted outwards from the celestial equator rather than up from the south
        // pole, so the equator itself is always one of the lines whatever the
        // spacing is set to.
        var dec = 0
        while (dec <= DEC_LIMIT) {
            lines.add(GridLine(declinationColor(dec), declinationRun(dec.toDouble())))
            if (dec != 0) lines.add(GridLine(declinationColor(-dec), declinationRun(-dec.toDouble())))
            dec += step
        }
        var ra = 0
        while (ra < 360) {
            lines.add(GridLine(LINE_COLOR, hourRun(ra.toDouble())))
            ra += step
        }

        mesh = lines
        meshStep = step
        return lines
    }

    /**
     * The celestial equator gets its own colour: it is the zero of declination,
     * where the Sun crosses at the equinoxes.
     */
    private fun declinationColor(decDeg: Int) =
        if (decDeg == 0) EQUATOR_COLOR else LINE_COLOR

    private fun declinationRun(decDeg: Double) = run(360 / RA_SAMPLE + 1) { i ->
        SkyMath.raDecToVector((i * RA_SAMPLE).toDouble(), decDeg)
    }

    /**
     * An hour circle: straight from pole to pole at one right ascension. Sampled to
     * both poles, so they all meet at the two points the sky turns about.
     */
    private fun hourRun(raDeg: Double) = run(180 / DEC_SAMPLE + 1) { i ->
        SkyMath.raDecToVector(raDeg, (-90 + i * DEC_SAMPLE).toDouble())
    }
}

/**
 * A run of [count] unit vectors as one flat float array. Sized up front and filled
 * in place: the point count is known before the loop starts, so growing the array a
 * value at a time would be wasted work.
 */
internal inline fun run(count: Int, point: (Int) -> DoubleArray): FloatArray {
    val out = FloatArray(count * 3)
    for (i in 0 until count) {
        val v = point(i)
        out[3 * i] = v[0].toFloat()
        out[3 * i + 1] = v[1].toFloat()
        out[3 * i + 2] = v[2].toFloat()
    }
    return out
}
