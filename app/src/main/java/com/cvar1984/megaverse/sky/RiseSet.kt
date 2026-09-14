package com.cvar1984.megaverse.sky

/** What a body does in one day: when it crossed the horizon, and how high it got. */
class DayEvents(
    val riseJd: Double?,
    val setJd: Double?,
    val maxAltitude: Double,
    val minAltitude: Double,
) {
    /** Up the whole day, as the summer Sun is inside the Arctic Circle. */
    val alwaysUp get() = riseJd == null && setJd == null && minAltitude > RiseSet.HORIZON

    /** Never up at all, as the winter Sun is in the same place. */
    val alwaysDown get() = riseJd == null && setJd == null && maxAltitude <= RiseSet.HORIZON
}

/**
 * When the Sun and Moon cross the horizon.
 *
 * Worked out by walking the day and watching the altitude change sign, rather than
 * from the usual closed-form hour angle. That formula treats the body as fixed for
 * the whole day, which is fine for a star and wrong for the Moon: it moves thirteen
 * degrees between one moonrise and the next, enough to shift the time by the best
 * part of an hour. Walking the day costs more arithmetic and asks nothing of how
 * fast the body moves, so the same code answers for both.
 */
object RiseSet {
    /**
     * The altitude of the centre at the moment the upper limb touches the horizon.
     * The Sun and Moon are discs about half a degree across, so they are already
     * showing while their middle is still below the skyline. Refraction is not in
     * this number - [SkyMath.apparentAltitude] has already applied it.
     */
    const val HORIZON = -0.2666

    /**
     * Ten minutes between samples, and a straight line across the pair that
     * brackets a crossing. Altitude moves at about a quarter of a degree a minute
     * near the horizon and is very nearly straight over ten of them, so this lands
     * inside a minute.
     *
     * ponytail: fixed grid, halve the step if seconds ever matter.
     */
    private const val STEP_MINUTES = 10
    private const val STEP = STEP_MINUTES / 1440.0
    private const val STEPS = 1440 / STEP_MINUTES

    /** Apparent altitude in degrees, corrected to the observer on the surface. */
    fun altitude(obj: SkyObject, jd: Double, latDeg: Double, lonDeg: Double): Double {
        val raDec = SkyCatalog.raDec(obj, jd)
        val altAz = SkyMath.raDecToAltAz(raDec[0], raDec[1], latDeg, SkyMath.lst(jd, lonDeg))
        return SkyMath.apparentAltitude(altAz[0], SkyCatalog.horizontalParallax(obj, jd))
    }

    /**
     * Everything [obj] does in the 24 hours from [startJd], which the caller sets
     * to local midnight so the answers land on one calendar day.
     *
     * Only the first rise and the first set of the day are reported. The Moon can
     * manage two of either when one falls just after midnight, and a calendar row
     * has space for one of each.
     */
    fun events(obj: SkyObject, startJd: Double, latDeg: Double, lonDeg: Double): DayEvents {
        var rise: Double? = null
        var set: Double? = null

        var previous = altitude(obj, startJd, latDeg, lonDeg)
        var peak = previous
        var lowest = previous

        for (i in 1..STEPS) {
            val jd = startJd + i * STEP
            val current = altitude(obj, jd, latDeg, lonDeg)

            if (rise == null && previous <= HORIZON && current > HORIZON) {
                rise = crossing(jd - STEP, previous, jd, current)
            }
            if (set == null && previous > HORIZON && current <= HORIZON) {
                set = crossing(jd - STEP, previous, jd, current)
            }
            if (current > peak) peak = current
            if (current < lowest) lowest = current

            previous = current
        }

        return DayEvents(rise, set, peak, lowest)
    }

    /** Where the straight line between two samples crosses the horizon. */
    private fun crossing(jd0: Double, alt0: Double, jd1: Double, alt1: Double): Double {
        val span = alt1 - alt0
        if (span == 0.0) return jd0
        return jd0 + (HORIZON - alt0) / span * (jd1 - jd0)
    }
}
