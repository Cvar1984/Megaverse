package com.cvar1984.megaverse.sky

import kotlin.math.sqrt

/**
 * The constellation stick figures, drawn as lines joining their stars.
 *
 * The vertices live here rather than in SkyCatalog because most of them are not
 * stars you would ever aim at. A figure needs its faint stars to read: Orion
 * without Mintaka and Saiph is three dots, and the Plough without Dubhe and Merak
 * is unrecognisable. Putting fifty of those into the catalogue would bury the 28
 * bright ones in the Stars menu and scatter them over Show All. They are line
 * endpoints, not objects, so they are held as plain coordinates.
 *
 * Positions are J2000 right ascension and declination in degrees, same as
 * SkyCatalog. A dozen of the vertices are bright enough to be in SkyCatalog as
 * well; they are repeated here rather than looked up, so that a figure is one flat
 * run of numbers instead of a mix of names and coordinates. The cost is that the
 * two lists have to be corrected together: a position fixed in only one of them
 * leaves the figure hanging off its own star.
 */
object Constellations {
    /**
     * A steel blue-grey, desaturated enough to stay clear of the horizon grid's
     * greens and the equatorial grid's saturated blues, so all three can be on at
     * once and still be told apart.
     */
    const val LINE_COLOR = 0xFF557788.toInt()

    /**
     * Refraction is only worked out for vertices lower than this. At 15 degrees up
     * it is already down to 0.06 degrees, a fraction of a pixel. It is the sine of
     * that altitude, because the up component of each vertex is already to hand.
     */
    private const val REFRACTION_BELOW = 0.2588f

    /**
     * The figures, each named and traced by one or more strokes. A stroke is right
     * ascension and declination in degrees, in pairs, drawn point to point; a
     * constellation that does not trace in a single unbroken line takes more than one.
     *
     * All twelve of the zodiac are here, which is what the ecliptic runs through, plus
     * six bright ones off it that are what most people can already find.
     */
    private val figures = arrayOf(
        // ------------------------------------------------------------ off the zodiac

        // Orion. Shoulders, then down one side through the belt and up the other,
        // then a leg from each end of the belt.
        figure(
            "Orion",
            doubleArrayOf(88.793, 7.407, 81.283, 6.350),
            doubleArrayOf(81.283, 6.350, 83.002, -0.299, 84.053, -1.202, 85.190, -1.943, 88.793, 7.407),
            doubleArrayOf(83.002, -0.299, 78.634, -8.202),
            doubleArrayOf(85.190, -1.943, 86.939, -9.670),
        ),

        // Ursa Major, the Plough. Handle first, round the bowl, and back to Megrez
        // to close it.
        figure(
            "Ursa Major",
            doubleArrayOf(
                206.885, 49.313, 200.981, 54.925, 193.507, 55.960, 183.857, 57.033,
                178.458, 53.695, 165.460, 56.382, 165.932, 61.751, 183.857, 57.033
            ),
        ),

        // Ursa Minor. The same shape the other way up, hanging off Polaris.
        figure(
            "Ursa Minor",
            doubleArrayOf(
                37.955, 89.264, 263.054, 86.586, 251.492, 82.037, 236.015, 77.794,
                244.376, 75.755, 230.182, 71.834, 222.676, 74.155, 236.015, 77.794
            ),
        ),

        // Cassiopeia, the W.
        figure(
            "Cassiopeia",
            doubleArrayOf(2.295, 59.150, 10.127, 56.537, 14.177, 60.717, 21.454, 60.235, 28.599, 63.670),
        ),

        // Cygnus, the Northern Cross: the spine down from Deneb, then the crossbar
        // through Sadr.
        figure(
            "Cygnus",
            doubleArrayOf(310.358, 45.280, 305.557, 40.257, 292.680, 27.960),
            doubleArrayOf(296.244, 45.131, 305.557, 40.257, 311.553, 33.970),
        ),

        // Crux, the Southern Cross. Two bars that meet at nothing.
        figure(
            "Crux",
            doubleArrayOf(186.650, -63.099, 187.792, -57.113),
            doubleArrayOf(191.930, -59.689, 183.786, -58.749),
        ),

        // ---------------------------------------------------------------- the zodiac

        // Aries. Three stars in a bent line, with 41 Arietis trailing off Hamal.
        figure(
            "Aries",
            doubleArrayOf(28.383, 19.294, 28.660, 20.808, 31.793, 23.462, 42.496, 27.261),
        ),

        // Taurus. The V of the Hyades along the face, then a horn from each end:
        // Elnath off the northern side, Zeta off Aldebaran.
        figure(
            "Taurus",
            doubleArrayOf(60.170, 12.490, 64.948, 15.628, 65.734, 17.543, 67.154, 19.180, 81.573, 28.608),
            doubleArrayOf(64.948, 15.628, 68.980, 16.509, 84.411, 21.143),
        ),

        // Gemini. The two heads joined, then a body hanging from each.
        figure(
            "Gemini",
            doubleArrayOf(113.650, 31.888, 116.329, 28.026),
            doubleArrayOf(113.650, 31.888, 100.983, 25.131, 95.740, 22.514, 93.719, 22.507),
            doubleArrayOf(116.329, 28.026, 110.031, 21.982, 106.027, 20.570, 99.428, 16.399),
        ),

        // Cancer. An upside-down Y, with the two Aselli either side of the middle.
        figure(
            "Cancer",
            doubleArrayOf(131.674, 28.760, 130.821, 21.469, 131.171, 18.154, 124.129, 9.186),
            doubleArrayOf(131.171, 18.154, 134.622, 11.858),
        ),

        // Leo. The sickle that makes the head, then the body back to Denebola.
        figure(
            "Leo",
            doubleArrayOf(
                146.463, 23.774, 148.191, 26.007, 154.173, 23.417, 154.993, 19.842,
                151.833, 16.763, 152.093, 11.967
            ),
            doubleArrayOf(
                152.093, 11.967, 168.560, 15.429, 177.265, 14.572, 168.527, 20.524, 154.993, 19.842
            ),
        ),

        // Virgo. The arc of the shoulders, down through Heze to Spica, then the leg
        // running east.
        figure(
            "Virgo",
            doubleArrayOf(195.544, 10.959, 193.901, 3.397, 190.415, -1.449, 184.977, -0.667, 177.674, 1.765),
            doubleArrayOf(190.415, -1.449, 203.673, -0.596, 201.298, -11.161),
            doubleArrayOf(203.673, -0.596, 214.004, -6.001, 220.765, -5.658),
        ),

        // Libra. The beam of the scales, closed back on Zubenelgenubi so the pans hang.
        figure(
            "Libra",
            doubleArrayOf(
                226.018, -25.282, 222.720, -16.042, 229.252, -9.383, 233.882, -14.789, 222.720, -16.042
            ),
        ),

        // Scorpius. The head, then Antares, then the curve of the tail.
        figure(
            "Scorpius",
            doubleArrayOf(
                241.359, -19.805, 240.083, -22.622, 247.352, -26.432, 252.542, -34.293,
                264.330, -42.998, 263.402, -37.104
            ),
        ),

        // Sagittarius, the Teapot: spout and base, then lid and upper body, then the
        // handle on the east side.
        figure(
            "Sagittarius",
            doubleArrayOf(271.452, -30.424, 275.249, -29.828, 276.043, -34.385, 285.653, -29.880),
            doubleArrayOf(275.249, -29.828, 276.993, -25.422, 284.737, -26.990, 283.816, -26.297, 285.653, -29.880),
            doubleArrayOf(283.816, -26.297, 286.173, -27.670, 285.653, -29.880),
        ),

        // Capricornus. A broad triangle, traced round and closed at Algedi.
        figure(
            "Capricornus",
            doubleArrayOf(
                304.514, -12.545, 305.253, -14.781, 312.955, -26.919, 321.667, -22.411,
                326.760, -16.127, 325.023, -16.662, 316.487, -17.233, 304.514, -12.545
            ),
        ),

        // Aquarius. The shoulders and the water jar, then the stream falling to Skat.
        figure(
            "Aquarius",
            doubleArrayOf(
                311.919, -9.496, 322.890, -5.571, 331.446, -0.320, 335.414, -1.387,
                337.211, -0.020, 338.839, -0.117
            ),
            doubleArrayOf(337.211, -0.020, 343.154, -7.580, 343.663, -15.821),
        ),

        // Pisces. The western circlet, the long cord east to Alrescha, and the
        // northern fish above it.
        figure(
            "Pisces",
            doubleArrayOf(345.970, 3.282, 349.290, 3.282, 349.958, 6.379, 352.822, 5.626, 345.970, 3.282),
            doubleArrayOf(352.822, 5.626, 359.828, 6.863, 17.376, 7.585, 19.870, 7.890, 30.512, 2.764),
            doubleArrayOf(30.512, 2.764, 28.140, 9.157, 22.871, 15.346),
        ),
    )

    private fun figure(name: String, vararg strokes: DoubleArray) = Figure(name, strokes.toList())

    /** Every figure, for anything that needs to know what is drawn and where. */
    val all: List<Figure> get() = figures.toList()

    /** Every figure as runs of fixed equatorial unit vectors, worked out once. */
    private val runs: List<FloatArray> by lazy(LazyThreadSafetyMode.NONE) {
        figures.flatMap { figure ->
            figure.strokes.map { points ->
                run(points.size / 2) { i -> SkyMath.raDecToVector(points[2 * i], points[2 * i + 1]) }
            }
        }
    }

    fun vectors(): List<FloatArray> = runs

    /**
     * One figure turned into East-North-Up for this frame.
     *
     * Refraction is applied here, unlike in the grids. A grid line is its own
     * reference and nothing has to agree with it, but these lines have to sit under
     * the stars they join, and those stars are placed through
     * SkyMath.apparentAltitude, which lifts them. Without it Orion's belt draws a
     * couple of pixels below its own three stars as the constellation rises. The
     * lift moves a vertex up its vertical circle, so the up component takes the new
     * altitude and the level part shrinks to match.
     *
     * Parallax is not applied: it is zero for stars, and every vertex here is one.
     */
    fun enuRun(vecs: FloatArray, rows: FloatArray): FloatArray {
        val into = FloatArray(vecs.size)
        var j = 0
        while (j < vecs.size) {
            val x = vecs[j]
            val y = vecs[j + 1]
            val z = vecs[j + 2]
            var e = rows[0] * x + rows[1] * y + rows[2] * z
            var n = rows[3] * x + rows[4] * y + rows[5] * z
            var u = rows[6] * x + rows[7] * y + rows[8] * z
            if (u < REFRACTION_BELOW) {
                val level = sqrt(e * e + n * n)
                if (level > 0.000001f) {
                    val lifted = SkyMath.apparentAltitude(SkyMath.dasin(u.toDouble()), 0.0)
                    val k = (SkyMath.dcos(lifted) / level).toFloat()
                    e *= k
                    n *= k
                    u = SkyMath.dsin(lifted).toFloat()
                }
            }
            into[j] = e
            into[j + 1] = n
            into[j + 2] = u
            j += 3
        }
        return into
    }
}

/** One constellation: what it is called, and the strokes that trace it. */
class Figure(val name: String, val strokes: List<DoubleArray>)
