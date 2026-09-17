package com.cvar1984.megaverse.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.cvar1984.megaverse.sky.Constellations
import com.cvar1984.megaverse.sky.DeviceAim
import com.cvar1984.megaverse.sky.EquatorialGrid
import com.cvar1984.megaverse.sky.HorizonGrid
import com.cvar1984.megaverse.sky.SkyMath
import com.cvar1984.megaverse.sky.SkyObject
import com.cvar1984.megaverse.sky.PathMark
import com.cvar1984.megaverse.sky.SkyPaths
import com.cvar1984.megaverse.sky.SkyType
import com.cvar1984.megaverse.sky.SolarLunar
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Half the field of view mapped across the display. The picture fills the whole
 * screen, with nothing carved out for the text, so the width of the glass and this
 * angle alone set how much sky fits. At 45 degrees the focal length comes out as
 * exactly half the screen width.
 */
private const val FOV_HALF = 45.0

/** Within this much of the object the marker takes a green ring. */
private const val LOCK_DEGREES = 8.0

/**
 * How close the aim has to be before Show All names what it is pointing at. Tighter
 * than the lock, because naming the wrong one of two neighbouring objects is worse
 * than naming neither.
 */
private const val IDENTIFY_DEGREES = 5.0

/**
 * How far up the Milky Way is turned. The real thing is faint, and this lies under
 * the grids and every object on the screen, so it has to read as sky without
 * drowning what is drawn on top of it.
 */
private const val MILKY_WAY_GAIN = 0.75f

/** The reticle: a hole this wide, then a tick this long, in reference units. */
private const val CROSSHAIR_GAP = 4f
private const val CROSSHAIR_TICK = 4f

/** Half-width of the edge chevron, and how far a marker pins inside the rim so that
 *  the marker and its chevron both stay on the glass. */
private const val CHEVRON_SIZE = 10f
private const val MARKER_REACH = 22f

/**
 * Live pointer screen, for one selected object or - with [target] null - for the
 * whole catalogue as a plain sky map.
 *
 * The back of the watch is the aim, and objects are placed in the watch's own
 * frame, so the picture rolls with the wrist the way the view through a window
 * does. The written guidance underneath is measured against gravity instead,
 * because it describes how to swing your arm.
 */
@Composable
fun SkyScreen(target: SkyObject?, state: SkyState, onSettings: () -> Unit) {
    // You are looking at the sky rather than at the watch, and glancing back to a
    // display that has timed out makes it useless for aiming. Android holds the
    // panel on for as long as this is set, so there is nothing to re-arm.
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> state.start()
                Lifecycle.Event.ON_PAUSE -> state.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        state.start()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            state.stop()
        }
    }

    // The catalogue and the position both move far too slowly to be worth a frame
    // of their own, so they are worked out on their own slow tick.
    LaunchedEffect(Unit) {
        while (true) {
            state.refreshSky()
            state.refreshLocation()
            kotlinx.coroutines.delay(1000)
        }
    }

    // Both of these read the live frame, so both are held behind a derivedStateOf:
    // the sky is redrawn fifty times a second, and neither the status line nor a
    // rounded degree changes anything like that often.
    val status by remember {
        derivedStateOf {
            when {
                // Checked before the position, because without a compass there is
                // nothing to aim with however long the fix takes.
                !state.hasCompass -> "No compass\non this watch"
                !state.locationAllowed -> "Location permission\nneeded"
                state.location == null ->
                    if (state.locating) "Acquiring fix..." else "Locating..."
                state.frame == null || state.sky == null -> "Waiting for sensors...\nShake your device"
                else -> null
            }
        }
    }
    val readout by remember(target) { derivedStateOf { state.readout(target) } }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Every setting changes what is on the screen underneath, so they are
            // reachable from the screen they affect. A watch has no menu button, so
            // the hold lands on the sky itself.
            //
            // Written out rather than using combinedClickable, which claims the
            // gesture the moment the finger goes down and so swallows the sideways
            // drag that dismisses the screen. On a watch that drag is the only way
            // back, so a full-screen clickable here strands you on the sky. Nothing
            // is consumed: the long press is noticed and the drag still travels up
            // to the navigation host.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (awaitLongPressOrCancellation(down.id) != null) onSettings()
                }
            }
    ) {
        val waiting = status
        if (waiting != null) {
            Text(
                waiting,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
            )
            return@Box
        }

        SkyCanvas(target, state, LocalConfiguration.current.isScreenRound)
        Chrome(readout)
    }
}

/** The sky itself: both grids, the constellation figures, and the objects. */
@Composable
private fun SkyCanvas(target: SkyObject?, state: SkyState, round: Boolean) {
    val density = LocalDensity.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val cardinalPaint = remember {
        skyPaint(HorizonGrid.HORIZON_COLOR, with(density) { 11.sp.toPx() })
    }
    // The date marks sit along their own line, so their labels are smaller than the
    // cardinals: those name the whole horizon, these name one point on a line.
    val eclipticPaint = remember {
        skyPaint(SkyPaths.ECLIPTIC_MARK_COLOR, with(density) { 9.sp.toPx() })
    }
    val moonMarkPaint = remember {
        skyPaint(SkyPaths.MOON_MARK_COLOR, with(density) { 9.sp.toPx() })
    }
    val zone = remember { ZoneId.systemDefault() }

    Canvas(Modifier.fillMaxSize()) {
        val frame = state.frame ?: return@Canvas
        val snapshot = state.sky ?: return@Canvas

        val cx = size.width / 2f
        val cy = size.height / 2f
        val view = floatArrayOf(cx, cy, (cx / SkyMath.dtan(FOV_HALF)).toFloat())
        val scale = size.minDimension / ObjectArt.REFERENCE_WIDTH

        // Nothing is clipped and nothing is reserved: the sky is laid down across
        // the whole display first, and the text goes on top of it.
        //
        // The Milky Way goes down before any of it. The grids and the figures are
        // references drawn over the sky; this is the sky.
        if (Settings.milkyWay && MilkyWay.supported) {
            with(MilkyWay) {
                drawMilkyWay(context, frame, snapshot.latDeg, snapshot.lstDeg, view, MILKY_WAY_GAIN)
            }
        }

        for (line in HorizonGrid.meshFor(Settings.horizon)) {
            drawRun(frame, line.points, view, Color(line.color))
        }
        // Drawn even with the grid switched off, because the letters say which way
        // you are facing.
        drawCardinals(frame, view, cardinalPaint, scale)

        // Sidereal time is the only thing in the equatorial grid that moves, so
        // pinning it to one reading holds that grid still. It is held by default,
        // because a grid that follows the sky keeps creeping and a still reference
        // is easier to read against.
        val equatorial = EquatorialGrid.meshFor(Settings.equatorial)
        if (equatorial.isNotEmpty()) {
            val gridLst =
                if (Settings.dynEquatorial) snapshot.lstDeg else state.gridLst ?: snapshot.lstDeg
            val skyFrame = DeviceAim.rotateFrame(
                frame, SkyMath.equatorialToEnu(snapshot.latDeg, gridLst)
            )
            for (line in equatorial) {
                drawRun(skyFrame, line.points, view, Color(line.color))
            }
        }

        // The two roads, over the grids and under the objects that travel them.
        // Always from the live sidereal time, never the equatorial grid's pinned
        // one: the ecliptic has to stay under the Sun, and the Sun is placed from
        // the current time.
        val ecliptic = Settings.ecliptic
        val moonPath = Settings.moonPath
        if (ecliptic > 0 || moonPath > 0) {
            val skyFrame = DeviceAim.rotateFrame(
                frame, SkyMath.equatorialToEnu(snapshot.latDeg, snapshot.lstDeg)
            )
            if (ecliptic > 0) {
                drawRun(skyFrame, SkyPaths.eclipticRun(), view, Color(SkyPaths.ECLIPTIC_COLOR))
                if (ecliptic > 1) {
                    drawMarks(
                        skyFrame, SkyPaths.eclipticMarks(snapshot.jd, zone), view,
                        eclipticPaint, scale, Color(SkyPaths.ECLIPTIC_MARK_COLOR),
                    )
                }
            }
            if (moonPath > 0) {
                drawRun(
                    skyFrame, SkyPaths.moonPathRun(snapshot.jd), view,
                    Color(SkyPaths.MOON_PATH_COLOR),
                )
                if (moonPath > 1) {
                    drawMarks(
                        skyFrame, SkyPaths.moonMarks(snapshot.jd, zone), view,
                        moonMarkPaint, scale, Color(SkyPaths.MOON_MARK_COLOR),
                    )
                }
            }
        }

        // Over the grids so the figures read on top of them, under the objects so
        // the stars themselves sit on top of the lines that join them. Drawn from
        // the live sidereal time, never the pinned one: these have to stay under
        // their stars.
        if (Settings.constellations) {
            val rows = SkyMath.equatorialToEnu(snapshot.latDeg, snapshot.lstDeg)
            val color = Color(Constellations.LINE_COLOR)
            for (figure in Constellations.vectors()) {
                drawRun(frame, Constellations.enuRun(figure, rows), view, color)
            }
        }

        // The Sun lights everything else out there, so it is what sets the Moon a
        // phase; the zenith is what lays Saturn a set of rings the right way up.
        // Worked out once here and handed down rather than found again per object.
        val sun = snapshot.sunEnu
        val sunOffset = DeviceAim.viewOffset(frame, sun[0], sun[1], sun[2])
        val zenith = DeviceAim.viewOffset(frame, 0.0, 0.0, 1.0)

        if (target == null) {
            drawWholeSky(
                context, snapshot, frame, view, scale, sunOffset, zenith,
                snapshot.identify(frame)?.obj,
            )
        } else {
            snapshot.find(target)?.let {
                drawTarget(context, it, frame, view, scale, round, sunOffset, zenith)
            }
        }

        drawCrosshair(view, scale)
    }
}

/** A text brush for labels drawn out on the sky itself. */
private fun skyPaint(color: Int, size: Float) = android.graphics.Paint().apply {
    this.color = color
    textAlign = android.graphics.Paint.Align.CENTER
    isAntiAlias = true
    textSize = size
}

/**
 * The dated points along a path: a dot where the body will stand, and the date
 * beside it. Anything behind the watch comes back null from the projection and is
 * simply left out, the same as every other overlay.
 *
 * The label sits above its dot rather than on it, so the line it marks still runs
 * through unbroken.
 */
private fun DrawScope.drawMarks(
    skyFrame: FloatArray,
    marks: List<PathMark>,
    view: FloatArray,
    paint: android.graphics.Paint,
    scale: Float,
    color: Color,
) {
    for (mark in marks) {
        val v = mark.vector
        val point = DeviceAim.screenPoint(skyFrame, v[0], v[1], v[2], view) ?: continue
        drawCircle(color, 2f * scale, Offset(point[0], point[1]))
        drawContext.canvas.nativeCanvas.drawText(
            mark.label, point[0], point[1] - 5 * scale, paint
        )
    }
}

/**
 * North, east, south and west, lettered where they meet the horizon. There are only
 * four points, so they are projected each frame instead of meshed.
 */
private fun DrawScope.drawCardinals(
    frame: FloatArray, view: FloatArray, paint: android.graphics.Paint, scale: Float,
) {
    for (az in 0 until 360 step 90) {
        val enu = SkyMath.horizontalToEnu(az.toDouble(), 0.0)
        val point = DeviceAim.screenPoint(frame, enu[0], enu[1], enu[2], view) ?: continue
        drawContext.canvas.nativeCanvas.drawText(
            HorizonGrid.cardinalName(az), point[0], point[1] - 8 * scale, paint
        )
    }
}

/**
 * Every object in the catalogue at once, drawn as a plain sky map: no marker is
 * pinned to the rim and no chevron points off it, because with the whole sky on
 * show there is nothing in particular to be steered towards. An object that is not
 * out in front of the watch is left out.
 *
 * Below the horizon they are darkened rather than greyed out, so they still read as
 * underfoot without losing the colour and the face that identify them.
 */
private fun DrawScope.drawWholeSky(
    context: android.content.Context,
    snapshot: SkySnapshot,
    frame: FloatArray,
    view: FloatArray,
    scale: Float,
    sunOffset: DoubleArray,
    zenith: DoubleArray,
    identified: SkyObject?,
) {
    for (placed in snapshot.placed) {
        val enu = placed.enu
        // The offset orients the phase and the rings as well as placing the dot, so
        // it is kept rather than worked out twice.
        val offset = DeviceAim.viewOffset(frame, enu[0], enu[1], enu[2])
        val point = DeviceAim.toScreen(offset, view) ?: continue
        val px = point[0]
        val py = point[1]
        if (px < 0 || px >= size.width || py < 0 || py >= size.height) continue

        // Everything is drawn at full strength wherever it is. An object underfoot
        // is still placed correctly, and the altitude in the readout is what says it
        // is under you.
        drawSkyObject(
            context, px, py, placed.obj, ObjectArt.color(placed.obj), scale,
            offset, sunOffset, zenith,
        )

        // A ring round the one being named underneath, so there is no doubt which
        // of two neighbours the name belongs to. Sits outside the crosshair's reach,
        // because the two land on top of each other exactly when you are on target.
        if (placed.obj.id == identified?.id) {
            drawCircle(
                Color.White,
                ObjectArt.radius(placed.obj) * scale + (CROSSHAIR_GAP + CROSSHAIR_TICK + 3f) * scale,
                Offset(px, py),
                style = Stroke(1f),
            )
        }
    }
}

/**
 * Whatever the watch is pointing closest to, if anything is near enough to name.
 */
private fun SkySnapshot.identify(frame: FloatArray): Placed? {
    val i = DeviceAim.nearest(DeviceAim.aimDirection(frame), directions, IDENTIFY_DEGREES)
    return if (i < 0) null else placed[i]
}

/**
 * A small gapped reticle on the aim point.
 *
 * Four ticks with a hole in the middle rather than a cross through it. Where the
 * watch points is the centre of the display whether it is marked or not, so the
 * only thing a solid reticle would add is something sitting on top of the object
 * exactly as you close on it.
 */
private fun DrawScope.drawCrosshair(view: FloatArray, scale: Float) {
    val cx = view[0]
    val cy = view[1]
    val gap = CROSSHAIR_GAP * scale
    val tick = CROSSHAIR_TICK * scale
    val colour = Color(0xFF707070)
    drawLine(colour, Offset(cx - gap - tick, cy), Offset(cx - gap, cy), strokeWidth = 1f)
    drawLine(colour, Offset(cx + gap, cy), Offset(cx + gap + tick, cy), strokeWidth = 1f)
    drawLine(colour, Offset(cx, cy - gap - tick), Offset(cx, cy - gap), strokeWidth = 1f)
    drawLine(colour, Offset(cx, cy + gap), Offset(cx, cy + gap + tick), strokeWidth = 1f)
}

/**
 * The one object being aimed at, as a dot that slides in from the edge of the
 * screen as the offset from the aim axis shrinks. Outside the field of view the dot
 * pins to the edge with a chevron pointing further the way to move.
 */
private fun DrawScope.drawTarget(
    context: android.content.Context,
    placed: Placed,
    frame: FloatArray,
    view: FloatArray,
    scale: Float,
    round: Boolean,
    sunOffset: DoubleArray,
    zenith: DoubleArray,
) {
    val cx = view[0]
    val cy = view[1]
    val enu = placed.enu
    val offset = DeviceAim.viewOffset(frame, enu[0], enu[1], enu[2])
    val right = offset[0]
    val up = offset[1]

    var dotX: Float
    var dotY: Float
    // The perspective divide puts the object where a camera would, not only in the
    // right general direction. Null means it is not out in front of the back face.
    val onGlass = DeviceAim.toScreen(offset, view)
    if (onGlass != null) {
        dotX = onGlass[0]
        dotY = onGlass[1]
    } else {
        // Level with the back of the watch or behind it, where perspective has
        // nothing to say. Push it right out along the way it lies instead, so the
        // edge marker still points the way to swing.
        val span = sqrt(right * right + up * up)
        val push = 4 * size.width
        if (span < 0.000001) {
            // Dead behind the watch, where no way round is more the way to turn
            // than any other. Sent straight up rather than divided by almost
            // nothing, which would leave the marker sitting in the middle of the
            // screen as though the watch were already on it.
            dotX = cx
            dotY = cy - push
        } else {
            dotX = cx + push * (right / span).toFloat()
            dotY = cy - push * (up / span).toFloat()
        }
    }

    // Which way the object lies, and how far out the projection put it. Both are
    // read before any pinning, because the direction the object lies in is what the
    // marker and its chevron are both placed along.
    val awayX = dotX - cx
    val awayY = dotY - cy
    val away = sqrt(awayX * awayX + awayY * awayY)

    // Pin to the edge along that same line. The limit has to follow the line:
    // clamping the two axes separately would drag an object lying off to the right
    // and barely above centre out to a corner. The limit stops short of the edge by
    // the chevron's reach, so a pinned marker and its chevron are both drawn whole.
    val limit = markerLimit(awayX, awayY, away, scale, round)
    val pinned = away > limit
    if (pinned) {
        dotX = cx + limit * awayX / away
        dotY = cy + limit * awayY / away
    }

    val radius = ObjectArt.radius(placed.obj) * scale
    // Forward is the cosine of the angle off the aim, so it settles the lock.
    if (SkyMath.dacos(offset[2]) < LOCK_DEGREES) {
        drawCircle(Color.Green, radius + 6 * scale, Offset(dotX, dotY), style = Stroke(2f))
    }

    drawSkyObject(
        context, dotX, dotY, placed.obj, ObjectArt.color(placed.obj), scale,
        offset, sunOffset, zenith,
    )

    // One chevron, along the line the object lies on. That is the same line the
    // marker was pinned along, so the two agree. A chevron per axis would draw two
    // at right angles for an object off a corner, and neither would point at it.
    if (pinned) {
        drawChevron(dotX, dotY, awayX / away, awayY / away, CHEVRON_SIZE * scale)
    }
}

/**
 * How far out a marker may be pinned along the direction it lies in.
 *
 * A radius on a round display. On a flat-sided one it is the distance along that
 * same line to the edge of the box, so a rectangular screen uses its corners
 * instead of the circle drawn inside them. The direction is preserved either way,
 * so the marker and the chevron agree on where the object is.
 */
private fun DrawScope.markerLimit(
    awayX: Float, awayY: Float, away: Float, scale: Float, round: Boolean,
): Float {
    val reach = MARKER_REACH * scale
    val halfW = size.width / 2f - reach
    if (round) return halfW
    val halfH = size.height / 2f - reach

    // Scale along the line until it meets whichever side it reaches first.
    var limit = if (abs(awayX) < 0.000001f) Float.MAX_VALUE else halfW * away / abs(awayX)
    if (abs(awayY) > 0.000001f) limit = minOf(limit, halfH * away / abs(awayY))
    return limit
}

/**
 * Everything drawn on top of the sky: the object's name, what each grid's cells are
 * worth, and the readout table along the bottom.
 *
 * It is drawn onto the picture rather than into a band of its own, so the sky runs
 * to all four edges instead of sitting in a letterbox, and everything is sized to
 * stay legible over grid lines.
 */
private data class Readout(
    val name: String?,
    val scales: List<String>,
    val aimAz: String,
    val aimAlt: String,
    val objAz: String?,
    val objAlt: String?,
    val objBelow: Boolean,
    val note: String?,
    val tilt: String?,
)

/** Dim enough to sit behind the numbers, bright enough to read over a grid line. */
private val DimText = Color(0xFF8A8A8A)

/**
 * How far down the screen the two blocks of text are at their narrowest, as a
 * fraction of its height: the object's name at the top, and the bottom-most readout
 * row. Both blocks are sized to the row with the least glass under it rather than
 * to the widest, because the columns have to line up all the way down and a round
 * screen narrows towards both ends.
 */
private const val NAME_ROW = 0.12f
private const val LAST_ROW = 0.93f

@Composable
private fun androidx.compose.foundation.layout.BoxScope.Chrome(readout: Readout?) {
    if (readout == null) return
    val round = LocalConfiguration.current.isScreenRound

    Column(
        Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth(chordFraction(round, NAME_ROW))
            .padding(top = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Not pushed hard against the rim: the screen is round, so the rows at the
        // very top are the narrowest on it, and a long name such as Alpha Centauri
        // would have its ends cut off by the bezel up there.
        Text(
            readout.name ?: "",
            fontSize = 16.sp,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        // What each grid's cells are worth, one line per grid that is switched on,
        // in the top right where the sky is emptiest. Each line names its frame,
        // since two grids at different spacings would otherwise be a bare number.
        for (scale in readout.scales) {
            Text(
                scale,
                fontSize = 9.sp,
                color = DimText,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth(chordFraction(round, LAST_ROW))
            .padding(bottom = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The table appears whenever something is named, whether it was chosen from
        // the menu or simply found under the crosshair. With nothing named there is
        // no label column and so nothing to range on: the pair is centred, each
        // number under its own head, where a round screen is widest.
        if (readout.name == null) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                Head("Az")
                Head("Alt")
            }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                Text(readout.aimAz, fontSize = 13.sp)
                Text(readout.aimAlt, fontSize = 13.sp)
            }
            return@Column
        }

        // Object against where the watch is aimed, a row each, so the two pairs
        // read straight down their columns instead of having to be picked out of a
        // sentence. They converge as you settle onto the object, which makes a
        // sensor axis that runs the wrong way obvious rather than puzzling.
        Row(Modifier.fillMaxWidth()) {
            Cell("", Modifier.weight(1f), TextAlign.Start, DimText, 9.sp)
            Cell("Az", Modifier.weight(1f), TextAlign.End, DimText, 9.sp)
            Cell("Alt", Modifier.weight(1f), TextAlign.End, DimText, 9.sp)
        }
        if (readout.objAz != null && readout.objAlt != null) {
            Row(Modifier.fillMaxWidth()) {
                Cell("Obj", Modifier.weight(1f), TextAlign.Start)
                Cell(readout.objAz, Modifier.weight(1f), TextAlign.End)
                // An object below the horizon is the one value here worth colouring
                // on its own rather than reddening the whole line.
                Cell(
                    readout.objAlt, Modifier.weight(1f), TextAlign.End,
                    if (readout.objBelow) Color.Red else Color.White,
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Cell("Aim", Modifier.weight(1f), TextAlign.Start)
            Cell(readout.aimAz, Modifier.weight(1f), TextAlign.End)
            Cell(readout.aimAlt, Modifier.weight(1f), TextAlign.End)
        }
        // A row that runs across the columns and is centred: it holds a sentence
        // rather than figures, so nothing has to line up under it.
        readout.note?.let { Text(it, fontSize = 12.sp) }
        readout.tilt?.let { Text(it, fontSize = 12.sp) }
    }
}

@Composable
private fun Head(text: String) = Text(text, fontSize = 9.sp, color = DimText)

@Composable
private fun Cell(
    text: String,
    modifier: Modifier,
    align: TextAlign,
    color: Color = Color.White,
    size: androidx.compose.ui.unit.TextUnit = 12.sp,
) = Text(text, modifier = modifier, fontSize = size, color = color, textAlign = align, maxLines = 1)

/**
 * How much of the width the glass offers on a row [yFraction] of the way down the
 * screen. A round display is a circle, so the rows nearest the top and bottom are
 * the narrowest on it; a flat-sided one is as wide on every row.
 */
private fun chordFraction(round: Boolean, yFraction: Float): Float {
    if (!round) return 0.92f
    val dy = (yFraction - 0.5f) * 2f
    return (sqrt((1f - dy * dy).coerceAtLeast(0f)) * 0.92f).coerceAtLeast(0.34f)
}

/**
 * The numbers and words the chrome shows, worked out from the live frame.
 *
 * Held behind a derivedStateOf so that the text only recomposes when a digit it
 * displays actually changes, rather than on every one of the fifty sensor samples a
 * second that redraw the sky.
 */
private fun SkyState.readout(target: SkyObject?): Readout? {
    val frame = frame ?: return null
    val snapshot = sky ?: return null

    val aimElev = DeviceAim.aimElevation(frame)
    val aimAz = DeviceAim.aimHeading(frame)
    val scales = buildList {
        if (Settings.horizon > 0) add("${Settings.horizon} az/alt")
        if (Settings.equatorial > 0) add("${Settings.equatorial} ra/dec")
    }

    // With no object chosen, the screen names whatever the crosshair is over. There
    // is no turn/tilt to give in that case: you are already pointing at it.
    val identifying = target == null
    val placed = if (identifying) snapshot.identify(frame) else snapshot.find(target!!)
    if (placed == null) {
        return Readout(
            null, scales, degrees(aimAz), signedDegrees(aimElev),
            null, null, false, null, null,
        )
    }

    val enu = placed.enu
    val offset = DeviceAim.viewOffset(frame, enu[0], enu[1], enu[2])

    // Forward is the cosine of the angle off the aim, so it settles the lock on its own.
    var note: String? = null
    var tilt: String? = null
    if (identifying) {
        note = detail(placed, snapshot.jd)
    } else if (SkyMath.dacos(offset[2]) < LOCK_DEGREES) {
        note = "On target"
    } else {
        // Written guidance is measured against gravity rather than the watch's own
        // axes, so rolling the wrist leaves it alone: it says how to swing your arm,
        // which does not depend on how the watch is turned in your hand. These two
        // are the differences down the columns above.
        //
        // Aimed within a couple of degrees of straight up or down there is no
        // sensible "turn left" to give, since every direction is sideways from
        // there. The picture above still holds, so only these two lines drop out.
        val basis = DeviceAim.aimBasis(aimElev, aimAz)
        if (basis == null) {
            note = "Straight up/down"
        } else {
            val aimed = DeviceAim.project(basis, enu[0], enu[1], enu[2])
            note = "Turn ${abs(aimed[0]).roundToInt()} " + if (aimed[0] >= 0) "right" else "left"
            tilt = "Tilt ${abs(aimed[1]).roundToInt()} " + if (aimed[1] >= 0) "up" else "down"
        }
    }

    return Readout(
        placed.obj.name,
        scales,
        degrees(aimAz),
        signedDegrees(aimElev),
        degrees(placed.azDeg),
        signedDegrees(placed.altDeg),
        placed.altDeg < 0,
        note,
        tilt,
    )
}

/**
 * The one extra fact worth the room for an object being named: how bright a star
 * is, and what phase the Moon is at. The Sun and the planets have nothing to add
 * that the picture is not already showing.
 */
private fun detail(placed: Placed, jd: Double): String? = when (placed.obj.type) {
    SkyType.STAR -> if (placed.obj.mag.isNaN()) null else "mag ${"%.2f".format(placed.obj.mag)}"
    SkyType.MOON -> "${SolarLunar.moonPhaseName(jd)}  " +
        "${(SolarLunar.moonIllumination(jd) * 100).roundToInt()}%"
    else -> null
}

private fun degrees(deg: Double) = SkyMath.norm360(deg).roundToInt().toString()

private fun signedDegrees(deg: Double): String {
    val rounded = deg.roundToInt()
    return if (rounded >= 0) "+$rounded" else rounded.toString()
}
