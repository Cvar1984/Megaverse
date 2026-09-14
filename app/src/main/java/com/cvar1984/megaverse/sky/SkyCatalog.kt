package com.cvar1984.megaverse.sky

enum class SkyType { SUN, MOON, PLANET, STAR }

/**
 * One thing the app can locate. ra/dec are the J2000 catalogue position and only
 * mean anything for stars; the Sun, Moon and planets are worked out from the clock.
 */
data class SkyObject(
    val id: String,
    val name: String,
    val type: SkyType,
    val ra: Double = 0.0,
    val dec: Double = 0.0,
    val mag: Double = Double.NaN,
)

/** Registry of everything the app can locate: Sun, Moon, planets and stars. */
object SkyCatalog {
    // Bright naked-eye stars: J2000 RA/Dec (degrees) and visual magnitude. Proper
    // motion and precession are ignored; the error is far below what a wrist
    // compass/magnetometer can resolve. Order is the id: star_0 is Sirius.
    private val starData = arrayOf(
        Triple("Sirius", doubleArrayOf(101.287, -16.716), -1.46),
        Triple("Canopus", doubleArrayOf(95.988, -52.696), -0.74),
        Triple("Alpha Centauri", doubleArrayOf(219.900, -60.834), -0.27),
        Triple("Arcturus", doubleArrayOf(213.916, 19.182), -0.05),
        Triple("Vega", doubleArrayOf(279.234, 38.784), 0.03),
        Triple("Capella", doubleArrayOf(79.172, 45.998), 0.08),
        Triple("Rigel", doubleArrayOf(78.634, -8.202), 0.13),
        Triple("Procyon", doubleArrayOf(114.825, 5.225), 0.34),
        Triple("Betelgeuse", doubleArrayOf(88.793, 7.407), 0.50),
        Triple("Achernar", doubleArrayOf(24.429, -57.237), 0.46),
        Triple("Hadar", doubleArrayOf(210.956, -60.373), 0.61),
        Triple("Altair", doubleArrayOf(297.696, 8.868), 0.76),
        Triple("Aldebaran", doubleArrayOf(68.980, 16.509), 0.85),
        Triple("Antares", doubleArrayOf(247.352, -26.432), 0.96),
        Triple("Spica", doubleArrayOf(201.298, -11.161), 1.04),
        Triple("Pollux", doubleArrayOf(116.329, 28.026), 1.14),
        Triple("Fomalhaut", doubleArrayOf(344.413, -29.622), 1.16),
        Triple("Deneb", doubleArrayOf(310.358, 45.280), 1.25),
        Triple("Regulus", doubleArrayOf(152.093, 11.967), 1.36),
        Triple("Castor", doubleArrayOf(113.650, 31.888), 1.58),
        Triple("Bellatrix", doubleArrayOf(81.283, 6.350), 1.64),
        Triple("Alnilam", doubleArrayOf(84.053, -1.202), 1.69),
        Triple("Alnitak", doubleArrayOf(85.190, -1.943), 1.77),
        Triple("Alkaid", doubleArrayOf(206.885, 49.313), 1.86),
        Triple("Polaris", doubleArrayOf(37.955, 89.264), 1.98),
        Triple("Alphard", doubleArrayOf(141.897, -8.659), 1.98),
        Triple("Mizar", doubleArrayOf(200.981, 54.925), 2.23),
        Triple("Denebola", doubleArrayOf(177.265, 14.572), 2.14),
    )

    val objects: List<SkyObject> by lazy {
        listOf(
            SkyObject("sun", "Sun", SkyType.SUN),
            SkyObject("moon", "Moon", SkyType.MOON),
            SkyObject("mercury", "Mercury", SkyType.PLANET),
            SkyObject("venus", "Venus", SkyType.PLANET),
            SkyObject("mars", "Mars", SkyType.PLANET),
            SkyObject("jupiter", "Jupiter", SkyType.PLANET),
            SkyObject("saturn", "Saturn", SkyType.PLANET),
        ) + starData.mapIndexed { i, (name, radec, mag) ->
            SkyObject("star_$i", name, SkyType.STAR, radec[0], radec[1], mag)
        }
    }

    fun findById(id: String): SkyObject? = objects.firstOrNull { it.id == id }

    /**
     * Listed by name, whatever order the catalogue holds them in. The catalogue
     * orders stars by brightness and planets by distance from the Sun, and neither
     * helps when you are thumbing down 28 entries looking for Vega. A star's id is
     * its position in the catalogue, so only the menu presentation is sorted.
     */
    fun byType(type: SkyType): List<SkyObject> =
        objects.filter { it.type == type }.sortedBy { it.name }

    /**
     * How far the object's apparent place shifts between Earth's centre and the
     * surface, in degrees. Only the Moon is close enough for this to register: the
     * Sun comes to 0.0024 degrees, the planets at their closest to 0.009, and the
     * stars to nothing.
     */
    fun horizontalParallax(obj: SkyObject, jd: Double): Double =
        if (obj.type == SkyType.MOON) SolarLunar.moonHorizontalParallax(jd) else 0.0

    /** [ra, dec] in degrees for the given object at Julian Day jd (UTC). */
    fun raDec(obj: SkyObject, jd: Double): DoubleArray = when (obj.type) {
        SkyType.SUN -> SolarLunar.sunPosition(jd)
        SkyType.MOON -> SolarLunar.moonPosition(jd)
        SkyType.PLANET -> Planets.planetPosition(obj.id, jd)
        SkyType.STAR -> doubleArrayOf(obj.ra, obj.dec)
    }
}
