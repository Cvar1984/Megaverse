package com.cvar1984.megaverse.presentation

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.atan2
import kotlin.math.roundToInt
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
            // Pale gold, and it has to be: this is what the rings are drawn in, and
            // they sit against a globe the map paints cream. Saturn used to fall
            // through to Mars's orange, which no one saw while the globe was a flat
            // disc of the same wrong colour.
            "saturn" -> 0xFFE8D8A8.toInt()
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
 * phase, the lighting and the rings.
 *
 * Bodies with a map of their surface are drawn from it; the rest fall back to a
 * shaded disc in the object's own colour, which is also what happens on a screen too
 * small for a map to say anything.
 */
fun DrawScope.drawSkyObject(
    context: Context,
    x: Float,
    y: Float,
    obj: SkyObject,
    base: Int,
    scale: Float,
    offset: DoubleArray,
    sunOffset: DoubleArray,
    zenith: DoubleArray,
) {
    val r = ObjectArt.radius(obj) * scale
    val sphere = PlanetTexture.sphere(context, obj, (2 * r).roundToInt())
    when {
        // A star, which has no map and is a point of light anyway. Also what a body
        // whose map somehow would not load falls back to, which is the whole of the
        // fallback: a coloured disc, not a second way of drawing everything.
        sphere == null -> drawCircle(Color(base), r, Offset(x, y))
        obj.type == SkyType.SUN -> drawSun(x, y, base, r, sphere, offset, zenith)
        obj.type == SkyType.MOON -> drawMoon(x, y, base, r, sphere, offset, sunOffset, zenith)
        else -> drawPlanet(x, y, obj, base, r, scale, sphere, offset, sunOffset, zenith)
    }
}

/**
 * How far the bright spot sits from the middle of a disc, as a fraction of its
 * radius. Enough to read as lit from one side without pushing the highlight onto
 * the limb, where at these radii it would be a couple of pixels and lost.
 */
private const val HIGHLIGHT_OFFSET = 0.38f

/** How far the Sun's corona reaches, in disc radii. */
private const val CORONA_REACH = 2.4f

/** How hard the Sun's own limb darkening is laid on. */
private const val SUN_RELIEF = 0.55f

/**
 * How much relief the Moon is given. Far less than a planet, and that is not a
 * matter of taste: the Moon's dust backscatters, so a full Moon reads as a flat
 * disc rather than a shaded ball. Only a little darkening at the limb.
 */
private const val MOON_RELIEF = 0.35f

/** How far down the unlit side of the Moon is turned. Earthshine is faint. */
private const val EARTHSHINE = 6

/** White, so [shadingBrush] leaves a surface's own colours alone where it is lit. */
private val SHADING = 0xFFFFFFFF.toInt()

/**
 * How a ball is lit, as a mask to multiply over a photograph of its surface: white
 * towards the light, falling away to a dark limb. It says how lit each part of the
 * face is and nothing about what colour it is.
 *
 * ([lx], [ly]) is the screen direction the light comes from, and may be (0, 0) when
 * there is nothing to go on - the gradient then sits centred and still rounds the
 * disc. [relief] scales the whole effect, both the shift of the highlight and the
 * depth of the limb, so one number says how much of a ball this is.
 *
 * The gradient's radius is derived from the shift rather than fixed, so the far limb
 * always lands at the dark end whatever the relief.
 */
private fun shadingBrush(
    x: Float, y: Float, r: Float, lx: Float, ly: Float, relief: Float,
): Brush {
    val white = Color(SHADING)
    val shift = HIGHLIGHT_OFFSET * relief
    return Brush.radialGradient(
        0f to white,
        0.45f to white,
        1f to lerp(white, Color.Black, 0.75f * relief),
        center = Offset(x + lx * r * shift, y + ly * r * shift),
        radius = r * (1f + shift) * 1.08f,
    )
}

/**
 * The surface, turned so the body's north pole points the way the zenith does.
 *
 * The sprite is baked with north straight up, and the wrist is not. Rolling it here
 * is the same reasoning as Saturn's rings: what is drawn belongs to the sky, so it
 * has to turn with the sky rather than sit fixed to the glass.
 */
private fun DrawScope.drawSphere(sphere: ImageBitmap, x: Float, y: Float, poleDeg: Float) {
    val half = sphere.width / 2f
    rotate(poleDeg, Offset(x, y)) {
        drawImage(sphere, topLeft = Offset(x - half, y - half))
    }
}

/**
 * Which way round to turn a sprite so its top points at the zenith, in degrees
 * clockwise. Zero when there is nothing to go on, which leaves north up the screen.
 */
private fun poleAngle(offset: DoubleArray, zenith: DoubleArray): Float {
    val up = tangentScreenDir(offset, zenith) ?: return 0f
    return Math.toDegrees(atan2(up[0].toDouble(), -up[1].toDouble())).toFloat()
}

/**
 * A corona outside the disc, and the photosphere inside it.
 *
 * The corona is brightest against the limb and thins outwards, which is why it is
 * one gradient rather than the two rings it used to be: a ring has an outer edge and
 * the real thing does not. Limb darkening is the one feature of the Sun's face the
 * naked eye picks out through cloud - the disc is measurably dimmer at the edge than
 * the middle - so it is multiplied over the granulation rather than replacing it.
 */
private fun DrawScope.drawSun(
    x: Float, y: Float, base: Int, r: Float,
    sphere: ImageBitmap, offset: DoubleArray, zenith: DoubleArray,
) {
    val at = Offset(x, y)
    val body = Color(base)
    val reach = r * CORONA_REACH
    val limb = r / reach

    // Three stops past the limb rather than one, because a straight ramp out to
    // nothing has a visible edge where it lands. This falls off fast and then slowly.
    drawCircle(
        Brush.radialGradient(
            0f to body.copy(alpha = 0.50f),
            limb to body.copy(alpha = 0.50f),
            limb + (1f - limb) * 0.28f to body.copy(alpha = 0.13f),
            1f to body.copy(alpha = 0f),
            center = at,
            radius = reach,
        ),
        reach,
        at,
    )

    // Lit from within, so the shading is concentric rather than thrown from one side.
    val rr = sphere.width / 2f
    drawSphere(sphere, x, y, poleAngle(offset, zenith))
    drawCircle(
        shadingBrush(x, y, rr, 0f, 0f, SUN_RELIEF), rr, at,
        blendMode = BlendMode.Multiply,
    )
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
 *
 * With a map of the surface the order goes the other way round from the plain
 * version: the whole face is drawn, turned down to earthshine, and then the lit part
 * is drawn again over the top. Multiplying can darken but never brighten, so the
 * lit side has to be put back rather than left behind.
 */
private fun DrawScope.drawMoon(
    x: Float, y: Float, base: Int, r: Float, sphere: ImageBitmap,
    offset: DoubleArray, sunOffset: DoubleArray, zenith: DoubleArray,
) {
    val dot = offset[0] * sunOffset[0] + offset[1] * sunOffset[1] + offset[2] * sunOffset[2]
    val c = -dot

    // Sun dead behind or dead in front leaves no bright limb to point at, but the
    // phase is then full or new and the orientation does not show.
    val toward = tangentScreenDir(offset, sunOffset) ?: floatArrayOf(1f, 0f)
    val bx = toward[0]
    val by = toward[1]

    val rr = sphere.width / 2f
    val at = Offset(x, y)
    val pole = poleAngle(offset, zenith)

    drawSphere(sphere, x, y, pole)
    drawCircle(
        Color(ObjectArt.shade(SHADING, 1, EARTHSHINE)), rr, at,
        blendMode = BlendMode.Multiply,
    )
    clipPath(litPath(x, y, bx, by, c, rr)) {
        drawSphere(sphere, x, y, pole)
        drawCircle(
            shadingBrush(x, y, rr, bx, by, MOON_RELIEF), rr, at,
            blendMode = BlendMode.Multiply,
        )
    }
}

/**
 * The lit part of a disc at phase [c]: the half facing the Sun, with the terminator
 * ellipse added when gibbous and taken away when a crescent.
 *
 * Two convex halves combined rather than one path, because a crescent is concave and
 * the sweep that draws a half-ellipse cannot make one.
 */
private fun litPath(x: Float, y: Float, bx: Float, by: Float, c: Double, r: Float): Path {
    val facing = halfEllipse(x, y, bx, by, r, r)
    val terminator = halfEllipse(x, y, bx, by, (-c * r).toFloat(), r)
    return Path().apply {
        op(
            facing,
            terminator,
            if (c >= 0) PathOperation.Union else PathOperation.Difference,
        )
    }
}

/**
 * A planet as a ball lit from wherever the Sun is, which for Mercury and Venus is
 * often well off to one side and reads as the phase they actually show.
 *
 * Saturn gets its rings, which no map of the globe carries, lying along the planet's
 * own equator. That is taken as square to the local vertical, worked out from where
 * the zenith is in the watch's axes, so the rings roll with the sky rather than with
 * the wrist. A ring pinned to the screen would be wrong as soon as you turned your
 * arm. Jupiter's belts are drawn only when there is no map to show the real ones.
 */
private fun DrawScope.drawPlanet(
    x: Float, y: Float, obj: SkyObject, base: Int, r: Float, scale: Float,
    sphere: ImageBitmap, offset: DoubleArray, sunOffset: DoubleArray, zenith: DoubleArray,
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

    val toward = tangentScreenDir(offset, sunOffset) ?: floatArrayOf(0f, 0f)
    val rr = sphere.width / 2f
    drawSphere(sphere, x, y, poleAngle(offset, zenith))
    drawCircle(
        shadingBrush(x, y, rr, toward[0], toward[1], 1f), rr, Offset(x, y),
        blendMode = BlendMode.Multiply,
    )
}

/**
 * Which way [other] lies from the object, as a unit direction on the screen: the
 * part of [other] square to the object, with screen y running down. Null when the
 * two lie along the same line and there is no direction to be had.
 *
 * Both the Sun, which orients the phase and the lighting, and the zenith, which
 * orients the poles and the rings, are wanted this way round.
 */
private fun tangentScreenDir(offset: DoubleArray, other: DoubleArray): FloatArray? {
    val dot = offset[0] * other[0] + offset[1] * other[1] + offset[2] * other[2]
    val vx = (other[0] - dot * offset[0]).toFloat()
    val vy = -(other[1] - dot * offset[1]).toFloat()
    val len = sqrt(vx * vx + vy * vy)
    if (len < 0.000001f) return null
    return floatArrayOf(vx / len, vy / len)
}

/**
 * Screen direction of the object's equator: square to the way the zenith lies from
 * it. Falls back to across the screen when there is nothing to go on.
 */
private fun equatorDirection(offset: DoubleArray, zenith: DoubleArray): FloatArray {
    val up = tangentScreenDir(offset, zenith) ?: return floatArrayOf(1f, 0f)
    return floatArrayOf(-up[1], up[0])
}

/**
 * Half an ellipse as a closed path: semi-axis [ax] along ([bx], [by]) and [r] across
 * it, closed along the diameter. [ax] may be negative, which puts the half on the
 * other side, as the terminator needs.
 */
private fun halfEllipse(
    x: Float, y: Float, bx: Float, by: Float, ax: Float, r: Float,
): Path {
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
    return path
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
