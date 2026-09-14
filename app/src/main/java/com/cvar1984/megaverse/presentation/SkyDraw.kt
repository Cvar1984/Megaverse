package com.cvar1984.megaverse.presentation

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.cvar1984.megaverse.sky.DeviceAim
import com.cvar1984.megaverse.sky.SkyMath
import com.cvar1984.megaverse.sky.SkyObject
import com.cvar1984.megaverse.sky.SkyType
import kotlin.math.sqrt

/**
 * Draws a run of directions, flat triples in whatever axes [frame] maps from, as
 * one line. A point behind the watch breaks the line there instead of joining
 * across the gap. Both grids and the constellation figures draw through this.
 *
 * The projection is written out here with the frame held in locals, because every
 * overlay point goes through this loop: no call and no new object per point.
 */
fun DrawScope.drawRun(frame: FloatArray, run: FloatArray, view: FloatArray, color: Color) {
    val cx = view[0]
    val cy = view[1]
    val focal = view[2]
    val back = DeviceAim.BACK_SIGN.toFloat()
    val minForward = DeviceAim.MIN_FORWARD.toFloat()

    var havePrevious = false
    var previousX = 0f
    var previousY = 0f
    var i = 0
    while (i < run.size) {
        val tE = run[i]
        val tN = run[i + 1]
        val tU = run[i + 2]
        val forward = back * (tE * frame[2] + tN * frame[5] + tU * frame[8])
        if (forward >= minForward) {
            val px = cx + focal * (tE * frame[0] + tN * frame[3] + tU * frame[6]) / forward
            val py = cy - focal * (tE * frame[1] + tN * frame[4] + tU * frame[7]) / forward
            if (havePrevious) {
                drawLine(color, Offset(previousX, previousY), Offset(px, py), strokeWidth = 1f)
            }
            previousX = px
            previousY = py
            havePrevious = true
        } else {
            havePrevious = false
        }
        i += 3
    }
}

/**
 * How each object is drawn: its colour, its size, and the little that can be shown
 * of its face at the size a watch has room for.
 *
 * Nothing here is a texture image. At this size only a handful of features are
 * still recognisable as shapes: the Sun's corona, the Moon's phase, Saturn's rings,
 * Jupiter's belts. The phase is computed the way Stellarium does it, from the angle
 * between the object and the Sun as seen from here.
 */
/** Points sampled along each half-ellipse. Twelve is smooth at these radii. */
private const val ARC_STEPS = 12

object ObjectArt {
    /**
     * The display width the radii below were chosen against. A Wear OS panel is
     * half as wide again, so everything is scaled to keep the same share of the
     * glass and the same fraction of the field of view.
     */
    const val REFERENCE_WIDTH = 260f

    /** Approximate on-screen colour for an object. */
    fun color(obj: SkyObject): Int = when (obj.type) {
        SkyType.SUN -> 0xFFFFFF00.toInt()
        SkyType.MOON -> 0xFFAAAAAA.toInt()
        SkyType.STAR -> 0xFFFFFFFF.toInt()
        SkyType.PLANET -> when (obj.id) {
            "mars" -> 0xFFFF5500.toInt()
            "venus" -> 0xFFFFFFFF.toInt()
            "mercury" -> 0xFFAAAAAA.toInt()
            "jupiter" -> 0xFFFFFF00.toInt()
            else -> 0xFFFF5500.toInt()
        }
    }

    /**
     * How large the discs are drawn against the sizes below.
     *
     * None of them is a true angular size - the real Sun is half a degree across,
     * not seven - so they are symbols, and this is the knob that says how loud. It
     * is turned down because at full size they crowded the very sky they are meant
     * to be pointing into. One number, so every body shrinks together and the sizes
     * below keep meaning what they say relative to each other.
     */
    private const val DISC_SCALE = 0.6f

    /**
     * Disc radius: fixed per body for the Sun, Moon and planets, magnitude-scaled
     * for stars, where brighter stars draw larger the way they look. The planets
     * differ a little in size, because belts and rings need a couple of pixels to
     * land in.
     */
    fun radius(obj: SkyObject): Float = DISC_SCALE * when (obj.type) {
        SkyType.SUN -> 13f
        SkyType.MOON -> 10f
        SkyType.PLANET -> when (obj.id) {
            "jupiter" -> 8f
            "saturn" -> 7f
            "venus" -> 7f
            "mercury" -> 5f
            else -> 6f
        }
        SkyType.STAR -> if (obj.mag.isNaN()) 3f else (5.0 - obj.mag).toFloat().coerceIn(2f, 7f)
    }

    /**
     * A darker version of a colour, channel by channel, so every shade an object is
     * drawn in comes off the one colour it was given.
     */
    fun shade(color: Int, numerator: Int, denominator: Int): Int {
        val r = ((color shr 16) and 0xFF) * numerator / denominator
        val g = ((color shr 8) and 0xFF) * numerator / denominator
        val b = (color and 0xFF) * numerator / denominator
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}

/**
 * Draws the object centred at ([x], [y]).
 *
 * [base] is the colour to build from. The caller dims it for anything under the
 * horizon, and every shade here comes off it, so a dimmed object stays dimmed all
 * the way through. [offset], [sunOffset] and [zenith] are directions in the watch's
 * own axes: the object, the Sun, and straight up. The last two are what orient the
 * phase and the rings.
 */
fun DrawScope.drawSkyObject(
    x: Float,
    y: Float,
    obj: SkyObject,
    base: Int,
    scale: Float,
    offset: DoubleArray,
    sunOffset: DoubleArray?,
    zenith: DoubleArray?,
) {
    val r = ObjectArt.radius(obj) * scale
    when {
        obj.type == SkyType.SUN -> drawSun(x, y, base, r)
        obj.type == SkyType.MOON && sunOffset != null -> drawMoon(x, y, base, r, offset, sunOffset)
        obj.type == SkyType.PLANET -> drawPlanet(x, y, obj, base, r, scale, offset, zenith)
        else -> drawCircle(Color(base), r, Offset(x, y))
    }
}

/**
 * Corona outside the disc, and a disc that brightens towards the middle. Limb
 * darkening is the one feature of the Sun's face visible to the naked eye through
 * cloud, and it keeps the disc from reading as a flat yellow dot.
 */
private fun DrawScope.drawSun(x: Float, y: Float, base: Int, r: Float) {
    // Each ring is a fraction of the disc rather than a fixed pixel offset, so the
    // whole thing keeps its shape at any size. The fractions are the original
    // offsets read off its own radius: +6, +3 and -7 against a disc of 20.
    val at = Offset(x, y)
    drawCircle(Color(ObjectArt.shade(base, 1, 4)), r * 1.3f, at, style = Stroke(1f))
    drawCircle(Color(ObjectArt.shade(base, 2, 5)), r * 1.15f, at, style = Stroke(1f))
    drawCircle(Color(ObjectArt.shade(base, 4, 5)), r, at)
    drawCircle(Color(base), r * 0.65f, at)
}

/**
 * The Moon at its current phase.
 *
 * Both directions are unit vectors, so their dot product is the cosine of the
 * elongation, and the lit fraction follows from it directly: k = (1 - m.s) / 2.
 * What the drawing wants is c = 2k - 1, which is -(m.s). It runs from -1 at new,
 * through 0 at half, to +1 at full, and it is the width of the terminator ellipse
 * as a fraction of the disc.
 *
 * The bright limb faces the Sun, so the whole thing is oriented by the tangent
 * direction from the Moon towards it. That comes out of the geometry rather than
 * off the screen, which is why it stays right as the wrist rolls.
 */
private fun DrawScope.drawMoon(
    x: Float, y: Float, base: Int, r: Float, offset: DoubleArray, sunOffset: DoubleArray,
) {
    val dark = ObjectArt.shade(base, 1, 6)
    val dot = offset[0] * sunOffset[0] + offset[1] * sunOffset[1] + offset[2] * sunOffset[2]
    val c = -dot

    // Sun towards the Moon with the along-the-Moon part taken out: what is left
    // points along the sphere from one to the other. Screen y runs down where up
    // runs up, which is the only reason for the sign.
    var bx = (sunOffset[0] - dot * offset[0]).toFloat()
    var by = -(sunOffset[1] - dot * offset[1]).toFloat()
    val len = sqrt(bx * bx + by * by)
    if (len < 0.000001f) {
        // Sun dead behind or dead in front of the Moon, where there is no bright
        // limb to point at. The phase is then full or new, so it does not matter.
        bx = 1f
        by = 0f
    } else {
        bx /= len
        by /= len
    }

    // Unlit side drawn faint rather than left out: on a black panel a thin crescent
    // would otherwise be all there is of the Moon, and easy to lose against the stars.
    drawCircle(Color(dark), r, Offset(x, y))
    fillHalfEllipse(x, y, bx, by, r, r, Color(base))

    // The terminator bulges past the middle into the dark side when gibbous and
    // bites into the bright side when a crescent. Same ellipse either way: only the
    // colour it is painted in changes, and its width is c.
    fillHalfEllipse(x, y, bx, by, (-c * r).toFloat(), r, Color(if (c >= 0) base else dark))
}

/**
 * Saturn gets its rings and Jupiter its belts, both lying along the object's own
 * equator. That is taken as square to the local vertical, worked out from where the
 * zenith is in the watch's axes, so the rings roll with the sky rather than with
 * the wrist. A ring pinned to the screen would be wrong as soon as you turned your arm.
 */
private fun DrawScope.drawPlanet(
    x: Float, y: Float, obj: SkyObject, base: Int, r: Float, scale: Float,
    offset: DoubleArray, zenith: DoubleArray?,
) {
    val along = equatorDirection(offset, zenith)
    val ax = along[0]
    val ay = along[1]

    if (obj.id == "saturn") {
        // Drawn before the disc so the planet sits in front of its own rings.
        val reach = 2 * r + 2 * scale
        drawLine(
            Color(ObjectArt.shade(base, 3, 5)),
            Offset(x - reach * ax, y - reach * ay),
            Offset(x + reach * ax, y + reach * ay),
            strokeWidth = 2f,
        )
    }

    drawCircle(Color(base), r, Offset(x, y))

    if (obj.id == "jupiter") {
        // Two belts either side of the equator, which is as much as any small
        // telescope shows and as much as there is room for here.
        val belt = Color(ObjectArt.shade(base, 3, 5))
        drawBelt(x, y, r, ax, ay, r / 2, belt)
        drawBelt(x, y, r, ax, ay, -r / 2, belt)
    }
}

/**
 * A belt across the disc at the given distance from the equator, cut to the chord
 * so it stops at the limb instead of running past it.
 */
private fun DrawScope.drawBelt(
    x: Float, y: Float, r: Float, ax: Float, ay: Float, gap: Float, color: Color,
) {
    val half = sqrt((r * r - gap * gap).coerceAtLeast(0f))
    val cx = x + gap * -ay
    val cy = y + gap * ax
    drawLine(
        color,
        Offset(cx - half * ax, cy - half * ay),
        Offset(cx + half * ax, cy + half * ay),
        strokeWidth = 1f,
    )
}

/**
 * Screen direction of the object's equator: square to the way the zenith lies from
 * it. Falls back to across the screen when there is nothing to go on.
 */
private fun equatorDirection(offset: DoubleArray, zenith: DoubleArray?): FloatArray {
    if (zenith == null) return floatArrayOf(1f, 0f)
    val dot = offset[0] * zenith[0] + offset[1] * zenith[1] + offset[2] * zenith[2]
    val upX = (zenith[0] - dot * offset[0]).toFloat()
    val upY = -(zenith[1] - dot * offset[1]).toFloat()
    val len = sqrt(upX * upX + upY * upY)
    if (len < 0.000001f) return floatArrayOf(1f, 0f)
    // Square to the local vertical.
    return floatArrayOf(-upY / len, upX / len)
}

/**
 * Half an ellipse as a filled path: semi-axis [ax] along ([bx], [by]) and [r]
 * across it, closed along the diameter. [ax] may be negative, which puts the half
 * on the other side, as the terminator needs.
 *
 * Two convex halves rather than one lune, because a crescent is concave.
 */
private fun DrawScope.fillHalfEllipse(
    x: Float, y: Float, bx: Float, by: Float, ax: Float, r: Float, color: Color,
) {
    val px = -by
    val py = bx
    val path = Path()
    for (i in 0..ARC_STEPS) {
        val t = (180.0 * i) / ARC_STEPS
        val a = ax * SkyMath.dsin(t).toFloat()
        val b = r * SkyMath.dcos(t).toFloat()
        val vx = x + a * bx + b * px
        val vy = y + a * by + b * py
        if (i == 0) path.moveTo(vx, vy) else path.lineTo(vx, vy)
    }
    path.close()
    drawPath(path, color)
}

/**
 * Small triangle beyond a pinned marker, pointing further the way to move.
 *
 * ([dirX], [dirY]) is a unit vector and may point anywhere, not just along an axis:
 * the tip goes twice the chevron's size out along it and the base sits one size
 * out, squared off across it. Built this way round because the object is off in
 * some particular direction, and there is no reason to round that to the nearest
 * quarter turn.
 */
fun DrawScope.drawChevron(x: Float, y: Float, dirX: Float, dirY: Float, size: Float) {
    val acrossX = size * -dirY
    val acrossY = size * dirX
    val path = Path()
    path.moveTo(x + 2 * size * dirX, y + 2 * size * dirY)
    path.lineTo(x + size * dirX - acrossX, y + size * dirY - acrossY)
    path.lineTo(x + size * dirX + acrossX, y + size * dirY + acrossY)
    path.close()
    drawPath(path, Color.White)
}
