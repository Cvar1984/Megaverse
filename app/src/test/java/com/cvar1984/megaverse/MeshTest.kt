package com.cvar1984.megaverse

import com.cvar1984.megaverse.presentation.ObjectArt
import com.cvar1984.megaverse.sky.Constellations
import com.cvar1984.megaverse.sky.EquatorialGrid
import com.cvar1984.megaverse.sky.HorizonGrid
import com.cvar1984.megaverse.sky.SkyCatalog
import com.cvar1984.megaverse.sky.SkyMath
import com.cvar1984.megaverse.sky.SkyType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * The data the sky is drawn from: the grid meshes, the constellation runs, and the
 * size and colour each object is drawn at.
 *
 * None of this is checked by rendering it, because a mesh that is silently one line
 * short or a run that is no longer unit length looks plausible on a watch and wrong
 * only against the sky.
 */
class MeshTest {

    // ------------------------------------------------------------- grid meshes

    @Test(timeout = 5_000)
    fun offIsAGridSpacingLikeAnyOther() {
        // Off is a value in the settings ring, so it reaches this the same way 60
        // does. Stepping outwards by zero degrees never gets anywhere, so a spacing
        // that draws nothing has to be answered rather than walked.
        assertTrue(HorizonGrid.meshFor(0).isEmpty())
        assertTrue(EquatorialGrid.meshFor(0).isEmpty())
        assertTrue(HorizonGrid.meshFor(-15).isEmpty())
        assertTrue(EquatorialGrid.meshFor(-15).isEmpty())
    }

    @Test
    fun theHorizonIsDrawnAtEverySpacing() {
        // Circles of equal altitude are counted outwards from the horizon rather
        // than up from the bottom, so the horizon itself survives every spacing.
        for (step in listOf(60, 45, 30, 15, 10)) {
            val mesh = HorizonGrid.meshFor(step)
            assertEquals(
                "step $step",
                1,
                mesh.count { it.color == HorizonGrid.HORIZON_COLOR },
            )
        }
    }

    @Test
    fun theCelestialEquatorIsDrawnAtEverySpacing() {
        for (step in listOf(60, 45, 30, 15, 10)) {
            val mesh = EquatorialGrid.meshFor(step)
            assertEquals(
                "step $step",
                1,
                mesh.count { it.color == EquatorialGrid.EQUATOR_COLOR },
            )
        }
    }

    @Test
    fun aCoarserSpacingPlotsFewerPoints() {
        // Halving the step roughly doubles the points plotted, which is the whole
        // reason the spacing is a setting at all.
        val coarse = HorizonGrid.meshFor(60).sumOf { it.points.size }
        val fine = HorizonGrid.meshFor(15).sumOf { it.points.size }
        assertTrue("$coarse then $fine", fine > coarse * 2)
    }

    @Test
    fun everyMeshPointIsAUnitVector() {
        // A mesh point that is not unit length is a point off the celestial sphere,
        // which draws a line that bows away from the grid it belongs to.
        for (line in HorizonGrid.meshFor(30) + EquatorialGrid.meshFor(30)) {
            assertEquals(0, line.points.size % 3)
            var i = 0
            while (i < line.points.size) {
                assertEquals(1.0, length(line.points, i), 1e-5)
                i += 3
            }
        }
    }

    @Test
    fun aMeshIsBuiltOncePerSpacingAndKept() {
        // Rebuilding a few hundred vectors on every frame is the cost this cache
        // exists to avoid, so the same spacing has to come back as the same object.
        val first = HorizonGrid.meshFor(30)
        assertSame(first, HorizonGrid.meshFor(30))
        assertNotEquals(first.size, HorizonGrid.meshFor(45).size)
        assertSame(HorizonGrid.meshFor(45), HorizonGrid.meshFor(45))
    }

    @Test
    fun theCardinalsAreLetteredTheWayACompassCounts() {
        // These are drawn even with the grid switched off, because the letters are
        // what say which way you are facing.
        assertEquals("N", HorizonGrid.cardinalName(0))
        assertEquals("E", HorizonGrid.cardinalName(90))
        assertEquals("S", HorizonGrid.cardinalName(180))
        assertEquals("W", HorizonGrid.cardinalName(270))
    }

    // ------------------------------------------------------- constellation runs

    @Test
    fun refractionOnlyEverLiftsAVertex() {
        // The figures have to sit under the stars they join, and those stars are
        // placed through apparentAltitude, which lifts them. A vertex that came back
        // lower than it went in would draw the belt above Orion instead of through it.
        val rows = SkyMath.equatorialToEnu(51.5, 200.0)
        for (figure in Constellations.vectors()) {
            val lifted = Constellations.enuRun(figure, rows)
            var i = 0
            while (i < figure.size) {
                val before = rotate(rows, figure, i)
                assertTrue(
                    "up ${before[2]} -> ${lifted[i + 2]}",
                    lifted[i + 2] >= before[2] - 1e-6f,
                )
                i += 3
            }
        }
    }

    @Test
    fun liftingAVertexKeepsItOnTheSphere() {
        // The lift moves a vertex up its own vertical circle: the level part has to
        // shrink to match, or the point leaves the sphere and the figure stretches.
        val rows = SkyMath.equatorialToEnu(-33.9, 45.0)
        for (figure in Constellations.vectors()) {
            val lifted = Constellations.enuRun(figure, rows)
            var i = 0
            while (i < lifted.size) {
                assertEquals(1.0, length(lifted, i), 1e-5)
                i += 3
            }
        }
    }

    @Test
    fun aVertexHighUpIsLeftAlone() {
        // Refraction is a fraction of a pixel above about fifteen degrees, so it is
        // not worked out up there at all and the rotation must come through untouched.
        val rows = SkyMath.equatorialToEnu(51.5, 200.0)
        val altAz = SkyMath.raDecToAltAz(200.0, 51.5, 51.5, 200.0)
        val overhead = SkyMath.horizontalToEnu(altAz[1], altAz[0])
        val run = floatArrayOf(1f, 0f, 0f)
        val vector = SkyMath.raDecToVector(200.0, 51.5)
        for (k in 0..2) run[k] = vector[k].toFloat()

        val lifted = Constellations.enuRun(run, rows)
        assertTrue("should be near the zenith", overhead[2] > 0.99)
        for (k in 0..2) assertEquals(overhead[k], lifted[k].toDouble(), 1e-5)
    }

    // ------------------------------------------------------------- object art

    @Test
    fun shadingOnlyEverDarkensAndStaysOpaque() {
        // Every shade an object is drawn in comes off the one colour it was given,
        // so a shade that brightened would break the dimming of everything underfoot.
        for (obj in SkyCatalog.objects) {
            val base = ObjectArt.color(obj)
            assertEquals("alpha ${obj.name}", 0xFF, (base ushr 24) and 0xFF)
            for (numerator in 1..4) {
                val shaded = ObjectArt.shade(base, numerator, 5)
                assertEquals("alpha ${obj.name}", 0xFF, (shaded ushr 24) and 0xFF)
                for (shift in listOf(16, 8, 0)) {
                    assertTrue(
                        "${obj.name} channel $shift",
                        (shaded shr shift) and 0xFF <= (base shr shift) and 0xFF,
                    )
                }
            }
        }
    }

    @Test
    fun brighterStarsDrawLarger() {
        // A star's disc stands in for how bright it looks, so the ordering has to
        // follow magnitude, which counts backwards.
        val stars = SkyCatalog.objects.filter { it.type == SkyType.STAR }
        val sirius = stars.first { it.name == "Sirius" }
        val mizar = stars.first { it.name == "Mizar" }
        assertTrue(ObjectArt.radius(sirius) > ObjectArt.radius(mizar))
        // Bounded against the Moon rather than against pixel counts, so turning the
        // discs up or down as a whole does not turn this into a failing test.
        val moon = SkyCatalog.findById("moon")!!
        for (star in stars) {
            val r = ObjectArt.radius(star)
            assertTrue("${star.name} $r", r > 0f && r < ObjectArt.radius(moon))
        }
    }

    @Test
    fun theSunAndMoonAreTheLargestThingsDrawn() {
        // They are the only two objects that show a face at this size, so they get
        // the room for it.
        val biggestOther = SkyCatalog.objects
            .filter { it.type != SkyType.SUN && it.type != SkyType.MOON }
            .maxOf { ObjectArt.radius(it) }
        assertTrue(ObjectArt.radius(SkyCatalog.findById("moon")!!) > biggestOther)
        assertTrue(
            ObjectArt.radius(SkyCatalog.findById("sun")!!) >
                ObjectArt.radius(SkyCatalog.findById("moon")!!)
        )
    }

    // --------------------------------------------------------------- helpers

    private fun length(v: FloatArray, at: Int) = sqrt(
        (v[at] * v[at] + v[at + 1] * v[at + 1] + v[at + 2] * v[at + 2]).toDouble()
    )

    private fun rotate(rows: FloatArray, v: FloatArray, at: Int) = floatArrayOf(
        rows[0] * v[at] + rows[1] * v[at + 1] + rows[2] * v[at + 2],
        rows[3] * v[at] + rows[4] * v[at + 1] + rows[5] * v[at + 2],
        rows[6] * v[at] + rows[7] * v[at + 1] + rows[8] * v[at + 2],
    )
}
