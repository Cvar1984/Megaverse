package com.cvar1984.megaverse.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlinx.coroutines.delay

/**
 * Moving the sky off the present.
 *
 * The screen holds one number, the offset from now, and the rest of the app reads
 * the sky through it: the objects, the grids, the paths, the calendar. Nothing else
 * had to learn about time travel, because there was only ever one place the clock
 * was read for the astronomy.
 *
 * A step size and two arrows rather than a field to type into. There is no keyboard
 * worth the name on a watch, and the useful moves are relative anyway - an hour on,
 * a day back, this time next week - so the arrows say what you actually mean and the
 * step cycles through the scales those questions come at.
 */

/**
 * How far each tap moves, and what that reads as. Cycled by the middle button.
 *
 * Every one of these is written Long from the first factor, and that is load-bearing
 * rather than style: 30 * 86_400_000 in Int arithmetic is larger than Int holds and
 * silently comes back negative, which would turn the longest step into a jump
 * backwards. A test holds them to being positive and increasing.
 */
internal val STEPS = listOf(
    "1 sec" to 1_000L,
    "1 min" to 60_000L,
    "10 min" to 10L * 60_000,
    "1 hr" to 60L * 60_000,
    "6 hr" to 6L * 60 * 60_000,
    "1 day" to 24L * 60 * 60_000,
    "1 week" to 7L * 24 * 60 * 60_000,
    "30 day" to 30L * 24 * 60 * 60_000,
)

/**
 * Where the step starts: a second, the finest there is.
 *
 * It starts at the fine end rather than at a useful travelling distance because
 * landing exactly on a time is the harder of the two things to do and the one worth
 * making easy. A second of sky is fifteen arcseconds, far under a pixel, so at this
 * step the crown moves nothing anyone can see - which is why the time on screen
 * carries its seconds. Covering any distance means stepping the size up first.
 */
const val DEFAULT_STEP = 0

/**
 * How much scroll has to arrive before the sky moves one step.
 *
 * The first calibration knob, and it needs one. Android hands a rotary event over as
 * the number of pixels it would have scrolled a list by - the raw detent multiplied
 * by the view's own scroll factor - so it is a different number on every watch and
 * nothing in the API says what one click is worth. Measured on a Wear emulator at
 * this density, one detent arrives as 1.77 pixels, so this sits just under that and
 * a click moves the sky exactly once.
 */
private const val DETENT_PIXELS = 1.5f

/**
 * Turning the crown or the bezel moves the sky, by whatever step is currently set.
 *
 * It is on the sky screen as much as on the time screen, and that is the point:
 * reach for the crown and the sky moves, with nothing to open first. You watch the
 * Moon climb as you turn rather than setting a time and going to look.
 *
 * Every detent that arrives is spent. A turn is not rate limited and nothing is
 * rounded away, so the sky keeps up with the hand turning it however fast that is,
 * and a spin runs on as far as you care to spin it.
 *
 * Rotary needs focus, and getting that right is most of the work here.
 * [requestFocusOnHierarchyActive] hands it to whichever destination is the live one,
 * so coming back from the settings list returns the crown to the sky underneath.
 * Requesting it once from a LaunchedEffect instead would take focus on the way in
 * and never take it back afterwards, leaving the crown dead on a screen that looks
 * perfectly alive - which is the failure this API exists to prevent, and why its own
 * documentation says not to mix the two.
 */
@Composable
fun Modifier.travelOnRotary(state: SkyState): Modifier {
    // Carried between events rather than rounded away: a slow turn arrives as a
    // string of small deltas, and dropping each one would mean the sky never moved.
    var carried by remember { mutableFloatStateOf(0f) }
    return this
        .onRotaryScrollEvent { event ->
            carried += event.verticalScrollPixels
            // Whole detents, however many of them this event was worth, so a fast
            // flick that arrives as one large event still moves the sky by all of it.
            val detents = (carried / DETENT_PIXELS).toInt()
            if (detents != 0) {
                // What is left over is dropped rather than carried on, and that is
                // the difference between a click meaning something and a click
                // meaning roughly something. A detent arrives a little larger than
                // the threshold, so carrying the excess forward quietly earns a
                // free extra step every sixth click - twenty clicks measured as
                // twenty-four hours. Dropping it makes one click exactly one step,
                // and costs a high-resolution crown a fraction of a detent that it
                // has thousands of.
                carried = 0f
                state.travelBy(detents * STEPS[state.travelStep].second)
            }
            true
        }
        .requestFocusOnHierarchyActive()
        .focusable()
}

/** Amber, the one colour in the app that means "not the present". */
val TravelLabel = Color(0xFFFFB300)

@Composable
fun TimeScreen(state: SkyState) {
    val step = state.travelStep
    val zone = remember { ZoneId.systemDefault() }
    val offset = state.timeOffsetMillis
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    ScreenScaffold {
        Column(
            // Cleared past the top of the glass on purpose: the system draws the
            // real clock up there, and a date sliding under it is the one thing on
            // this screen that must not be hard to read.
            Modifier
                .fillMaxSize()
                .padding(start = 8.dp, end = 8.dp, top = 22.dp, bottom = 10.dp)
                .travelOnRotary(state),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Ticked, now that it shows seconds. It was read once at composition
            // while it stopped at minutes, where being up to a minute stale never
            // showed; a frozen seconds hand is simply wrong, and this screen is the
            // one place where two adjacent seconds are the whole point.
            val shown = Instant.ofEpochMilli(now + offset).atZone(zone)
            Text(
                shown.format(DateTimeFormatter.ofPattern("EEE d MMM")),
                fontSize = 13.sp,
                color = DimText,
            )
            Text(
                shown.format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                fontSize = 22.sp,
                color = if (offset == 0L) Color.White else TravelLabel,
            )
            Text(
                travelLabel(offset),
                fontSize = 11.sp,
                color = if (offset == 0L) DimText else TravelLabel,
                maxLines = 1,
            )

            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Step("-", Modifier.weight(1f)) { state.travelBy(-STEPS[step].second) }
                // The size itself is the button that changes it, so the three
                // controls are the three things there are to say: back, by this
                // much, forward.
                Step(STEPS[step].first, Modifier.weight(2f)) {
                    state.travelStep = (step + 1) % STEPS.size
                }
                Step("+", Modifier.weight(1f)) { state.travelBy(STEPS[step].second) }
            }

            Button(
                onClick = { state.travel(0) },
                label = {
                    Text("Now", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                },
                contentPadding = TIGHT,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(BUTTON_HEIGHT),
            )
        }
    }
}

/**
 * Shorter than a button usually is, because there are four rows of this screen and a
 * round display has the least glass exactly where the first and last of them are.
 */
private val BUTTON_HEIGHT = 44.dp

/**
 * No side padding to speak of. A button's usual padding is most of the width of the
 * narrow ones here, which leaves a single character nowhere to sit and gets it
 * replaced by an ellipsis - a minus sign that renders as "..." reads as a bug.
 */
private val TIGHT = PaddingValues(horizontal = 2.dp, vertical = 0.dp)

@Composable
private fun Step(label: String, modifier: Modifier, onClick: () -> Unit) = Button(
    onClick = onClick,
    contentPadding = TIGHT,
    label = {
        // One line, always. A step that wrapped would push the row taller than the
        // two beside it and leave the three buttons out of line.
        Text(
            label,
            fontSize = 14.sp,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    },
    modifier = modifier.height(BUTTON_HEIGHT),
)

/**
 * How far off the present the sky is, in the largest unit that still says something.
 *
 * Reported rather than left to be read off the date, because the date alone does not
 * say how far you have come: "Thu 2 Oct" is a fortnight away or a year and a
 * fortnight, and which one it is matters.
 *
 * The ladder runs to years because a crown that spins on without stopping will get
 * there, and "+3650000000 d" is a number nobody can read.
 */
fun travelLabel(offsetMillis: Long): String {
    if (offsetMillis == 0L) return "now"
    val sign = if (offsetMillis > 0) "+" else "-"
    val seconds = abs(offsetMillis) / 1000
    val minutes = seconds / 60
    val days = minutes / (24 * 60)
    return when {
        seconds < 60 -> "$sign$seconds s"
        minutes < 60 -> "$sign$minutes min ${seconds % 60} s"
        minutes < 48 * 60 -> "$sign${minutes / 60} h ${minutes % 60} min"
        days < 365 -> "$sign$days d ${(minutes / 60) % 24} h"
        // Years of 365 days, which is not a calendar year and is not meant to be:
        // the offset is a span of milliseconds, so every unit here is a fixed length
        // and the date above says where that actually lands.
        else -> "$sign${days / 365} y ${days % 365} d"
    }
}
