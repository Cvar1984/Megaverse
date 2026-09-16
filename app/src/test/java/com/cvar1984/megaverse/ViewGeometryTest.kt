package com.cvar1984.megaverse

import com.cvar1984.megaverse.sky.DeviceAim
import com.cvar1984.megaverse.sky.SkyMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the sky actually lands on the glass.
 *
 * This is the layer a screenshot would check, and the one a sign flip hides in:
 * the maths can be right in every component and still put the object on the wrong
 * side of the screen. None of it needs a device, because the projection is pure.
 */
class ViewGeometryTest {

    /** A 200 by 200 screen. At a 45 degree half-field the focal length is half the width. */
    private val view = floatArrayOf(100f, 100f, 100f)

    @Test
    fun everyFixtureIsAnOrientationAWatchCanActuallyBeIn() {
        // A reflection preserves length and dot products, so it passes almost every
        // test written against it and fails only on which way round the world is.
        for ((name, frame) in Frames.all) {
            assertEquals(name, 1.0, Frames.determinant(frame), 1e-5)
        }
    }

    @Test
    fun theAimItselfLandsDeadCentre() {
        for ((name, frame) in Frames.all) {
            val aim = DeviceAim.aimDirection(frame)
            val point = DeviceAim.screenPoint(frame, aim[0], aim[1], aim[2], view)
            assertEquals(name, 100f, point!![0], 0.01f)
            assertEquals(name, 100f, point[1], 0.01f)
        }
    }

    @Test
    fun somethingHigherInTheSkyDrawsHigherOnTheGlass() {
        // Screen y runs down where the sky runs up, which is the one place that sign
        // has to be turned over and the easiest to leave out.
        val frame = Frames.LEVEL_FACING_SOUTH
        val enu = SkyMath.horizontalToEnu(180.0, 30.0)
        val point = DeviceAim.screenPoint(frame, enu[0], enu[1], enu[2], view)!!
        assertEquals("should stay on the middle line", 100f, point[0], 0.01f)
        assertTrue("y ${point[1]}", point[1] < 100f)
    }

    @Test
    fun facingSouthTheWestSideOfTheSkyIsOnTheRight() {
        // Stand facing south and west is on your right hand, so an object past due
        // south towards the west has to draw right of centre. Get this one backwards
        // and the marker slides away from the object as you turn onto it.
        val frame = Frames.LEVEL_FACING_SOUTH
        val southwest = SkyMath.horizontalToEnu(210.0, 0.0)
        val southeast = SkyMath.horizontalToEnu(150.0, 0.0)
        val right = DeviceAim.screenPoint(frame, southwest[0], southwest[1], southwest[2], view)!!
        val left = DeviceAim.screenPoint(frame, southeast[0], southeast[1], southeast[2], view)!!
        assertTrue("southwest at ${right[0]}", right[0] > 100f)
        assertTrue("southeast at ${left[0]}", left[0] < 100f)
        // Symmetric about the centre, since both are the same angle off the aim.
        assertEquals(right[0] - 100f, 100f - left[0], 0.01f)
    }

    @Test
    fun thePerspectiveDivideSpreadsTheSkyOutTowardsTheEdge() {
        // A plain direction cosine would crowd everything towards the rim; the
        // divide puts it where a camera would. At the half-field angle the object
        // sits exactly on the edge of the glass.
        val frame = Frames.LEVEL_FACING_SOUTH
        val atHalfField = SkyMath.horizontalToEnu(180.0 + 45.0, 0.0)
        val edge = DeviceAim.screenPoint(frame, atHalfField[0], atHalfField[1], atHalfField[2], view)!!
        assertEquals(200f, edge[0], 0.01f)
    }

    @Test
    fun nothingBehindTheWatchIsDrawn() {
        // A perspective projection has nothing to say about what is behind the
        // camera, and drawing it anyway folds the far sky back across the near one.
        val frame = Frames.LEVEL_FACING_SOUTH
        for (az in listOf(0.0, 45.0, 315.0)) {
            val enu = SkyMath.horizontalToEnu(az, 0.0)
            assertNull("az $az", DeviceAim.screenPoint(frame, enu[0], enu[1], enu[2], view))
        }
    }

    @Test
    fun theMarkerAndTheWrittenGuidanceAgreeWhenTheWristIsNotRolled() {
        // The two are worked out in different frames on purpose: the picture in the
        // watch's own axes, so it rolls with the wrist, and the guidance against
        // gravity, so it describes how to swing your arm. Held square they have to
        // say the same thing, and the app leans on that - the README's point is that
        // a sensor axis wired backwards makes the two diverge as you close in.
        val frame = Frames.LEVEL_FACING_SOUTH
        val aimElev = DeviceAim.aimElevation(frame)
        val aimAz = DeviceAim.aimHeading(frame)
        val basis = DeviceAim.aimBasis(aimElev, aimAz)!!

        for (az in listOf(150.0, 170.0, 190.0, 210.0)) {
            for (alt in listOf(-20.0, 0.0, 25.0)) {
                val enu = SkyMath.horizontalToEnu(az, alt)
                val point = DeviceAim.screenPoint(frame, enu[0], enu[1], enu[2], view)!!
                val guidance = DeviceAim.project(basis, enu[0], enu[1], enu[2])
                val where = "az $az alt $alt"
                // Turn right and the marker is right of centre; tilt up and it is above.
                assertEquals(where, guidance[0] > 0, point[0] > 100f)
                assertEquals(where, guidance[1] > 0, point[1] < 100f)
            }
        }
    }

    @Test
    fun rollingTheWristTurnsThePictureAndLeavesTheGuidanceAlone() {
        // Looking through a window, turning your wrist turns what you see through
        // it. How to swing your arm to reach the object does not change with it.
        val enu = SkyMath.horizontalToEnu(180.0, 30.0)

        val square = DeviceAim.screenPoint(
            Frames.LEVEL_FACING_SOUTH, enu[0], enu[1], enu[2], view
        )!!
        val rolled = DeviceAim.screenPoint(
            Frames.ROLLED_FACING_SOUTH, enu[0], enu[1], enu[2], view
        )!!

        // Held square the object is straight above the centre; rolled a quarter
        // turn it is straight out to one side, the same distance away.
        assertTrue("square at ${square.toList()}", square[1] < 100f && square[0] == 100f)
        assertTrue("rolled at ${rolled.toList()}", rolled[0] != 100f && rolled[1] == 100f)
        assertEquals(100f - square[1], kotlin.math.abs(rolled[0] - 100f), 0.01f)

        // Both frames aim at the same place, so the guidance is the same sentence.
        for (frame in listOf(Frames.LEVEL_FACING_SOUTH, Frames.ROLLED_FACING_SOUTH)) {
            val basis = DeviceAim.aimBasis(
                DeviceAim.aimElevation(frame), DeviceAim.aimHeading(frame)
            )!!
            val guidance = DeviceAim.project(basis, enu[0], enu[1], enu[2])
            assertEquals(0.0, guidance[0], 1e-3)
            assertEquals(30.0, guidance[1], 1e-3)
        }
    }

    // --------------------------------------------------------- naming what you see

    @Test
    fun theNearestDirectionIsTheOneNamed() {
        // Whatever is closest to the aim wins, and only if it is close enough. This
        // is what stops the screen confidently naming something a quarter of the sky
        // away just because nothing nearer happened to be in the list.
        val aim = SkyMath.horizontalToEnu(180.0, 20.0)
        val candidates = listOf(
            SkyMath.horizontalToEnu(180.0, 60.0),  // 40 deg off
            SkyMath.horizontalToEnu(183.0, 21.0),  // ~3 deg off, the winner
            SkyMath.horizontalToEnu(140.0, 20.0),  // far
        )
        assertEquals(1, DeviceAim.nearest(aim, candidates, 5.0))
        // Tighten the window past the winner and nothing is named at all.
        assertEquals(-1, DeviceAim.nearest(aim, candidates, 2.0))
        assertEquals(-1, DeviceAim.nearest(aim, emptyList(), 5.0))
    }

    @Test
    fun theAimNamesWhateverItIsExactlyOn() {
        // Pointing straight at something must pick that thing, at any part of the
        // sky - including near the poles, where azimuth stops meaning much.
        for (az in listOf(0.0, 95.0, 180.0, 275.0)) {
            for (alt in listOf(-80.0, -10.0, 0.0, 45.0, 88.0)) {
                val here = SkyMath.horizontalToEnu(az, alt)
                val candidates = listOf(
                    SkyMath.horizontalToEnu(az + 40.0, alt),
                    here,
                    SkyMath.horizontalToEnu(az, (alt - 40.0).coerceAtLeast(-90.0)),
                )
                assertEquals("az $az alt $alt", 1, DeviceAim.nearest(here, candidates, 5.0))
            }
        }
    }

    @Test
    fun nothingBehindYouGetsNamed() {
        // The search is over the whole sphere, so a direction diametrically opposite
        // the aim must never be picked up by a small window.
        val aim = SkyMath.horizontalToEnu(0.0, 10.0)
        // Ten degrees short of antipodal, so a wide enough window can still reach it
        // - exactly opposite would sit on the boundary and prove nothing.
        val behind = listOf(SkyMath.horizontalToEnu(180.0, 0.0))
        assertEquals(-1, DeviceAim.nearest(aim, behind, 5.0))
        // A window wide enough to span the sky does reach it, which shows the -1
        // above was the threshold talking and not a sign error.
        assertEquals(0, DeviceAim.nearest(aim, behind, 179.0))
    }
}
