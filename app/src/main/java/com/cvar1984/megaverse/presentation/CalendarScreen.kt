package com.cvar1984.megaverse.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.cvar1984.megaverse.sky.DayEvents
import com.cvar1984.megaverse.sky.RiseSet
import com.cvar1984.megaverse.sky.SkyCatalog
import com.cvar1984.megaverse.sky.SolarLunar
import com.cvar1984.megaverse.sky.epochMillisFromJulianDay
import com.cvar1984.megaverse.sky.julianDayFromEpochMillis
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** How many days ahead the calendar runs. */
private const val DAYS = 14

private val DayLabel = Color(0xFFBFC8FF)
private val HeadLabel = Color(0xFF8A8A8A)
private val SunLabel = Color(0xFFFFD24A)
private val MoonLabel = Color(0xFFCFCFCF)

/**
 * Rise, transit and set for the Sun and the Moon, a day to a row, with the Moon's
 * phase alongside.
 *
 * Each row works its own day out as it scrolls into view rather than the whole
 * fortnight up front: a day costs a few hundred evaluations of the lunar series,
 * and the list only ever shows a handful at a time.
 */
@Composable
fun CalendarScreen(state: SkyState) {
    val fix = state.location
    // Rise and set are nothing without a position, and this screen is reachable
    // straight from the menu, so it asks for its own rather than relying on a sky
    // screen having been opened first.
    LaunchedEffect(Unit) {
        while (true) {
            state.ensureLocation()
            state.refreshLocation()
            delay(3000)
        }
    }

    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(contentPadding = contentPadding, state = listState) {
            item {
                ListHeader(
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                ) { Text("Sun & Moon") }
            }

            if (fix == null) {
                // Rise and set depend entirely on where you are standing, so there
                // is nothing to show until the fix lands.
                item {
                    Text(
                        if (state.locationAllowed) "Locating..." else "Location permission\nneeded",
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        textAlign = TextAlign.Center,
                        fontSize = 13.sp,
                    )
                }
                return@TransformingLazyColumn
            }

            items(DAYS) { offset ->
                DayRow(offset, fix.latitude, fix.longitude)
            }
        }
    }
}

@Composable
private fun DayRow(offset: Int, latDeg: Double, lonDeg: Double) {
    val zone = remember { ZoneId.systemDefault() }
    val date = remember(offset) { LocalDate.now(zone).plusDays(offset.toLong()) }

    // Midnight where you are standing, so a row holds one local calendar day rather
    // than one UTC day offset by the time zone.
    val day = remember(offset, latDeg, lonDeg) {
        val startJd = julianDayFromEpochMillis(
            date.atStartOfDay(zone).toInstant().toEpochMilli()
        )
        val sun = SkyCatalog.findById("sun")!!
        val moon = SkyCatalog.findById("moon")!!
        Triple(
            RiseSet.events(sun, startJd, latDeg, lonDeg),
            RiseSet.events(moon, startJd, latDeg, lonDeg),
            // Read at midday rather than midnight: it is the phase you would see
            // that night, and it is what the row is labelled with.
            startJd + 0.5,
        )
    }
    val (sun, moon, noonJd) = day

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            date.format(DateTimeFormatter.ofPattern("EEE d MMM")),
            fontSize = 14.sp,
            color = DayLabel,
        )
        // Column heads on every day rather than once at the top of the list. The
        // list scrolls, so the head of it is usually off screen, and two bare times
        // side by side give no way of telling which one is which.
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            Head("rise")
            Head("set")
        }
        BodyRow("Sun", SunLabel, sun, zone)
        BodyRow("Moon", MoonLabel, moon, zone)
        Text(
            "${SolarLunar.moonPhaseName(noonJd)}  " +
                "${(SolarLunar.moonIllumination(noonJd) * 100).roundToInt()}%",
            fontSize = 11.sp,
            color = MoonLabel,
            maxLines = 1,
        )
    }
}

/** A column head: dim and small, so the times below it read as the content. */
@Composable
private fun RowScope.Head(text: String) = Text(
    text,
    modifier = Modifier.weight(1f),
    fontSize = 9.sp,
    color = HeadLabel,
    textAlign = TextAlign.End,
    maxLines = 1,
)

/**
 * One line for a body's day: what it is, when it came up, and when it went down,
 * lined up under the heads so the two times cannot be mistaken for each other.
 *
 * A body that never sets has no times worth printing, so it says so across both
 * columns instead of showing two dashes and leaving you to work out why.
 */
@Composable
private fun BodyRow(name: String, color: Color, events: DayEvents, zone: ZoneId) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            name,
            modifier = Modifier.weight(1f),
            fontSize = 12.sp,
            color = color,
            textAlign = TextAlign.Start,
            maxLines = 1,
        )
        val note = when {
            events.alwaysUp -> "up all day"
            events.alwaysDown -> "never rises"
            else -> null
        }
        if (note != null) {
            Text(
                note,
                modifier = Modifier.weight(2f),
                fontSize = 12.sp,
                color = color,
                textAlign = TextAlign.End,
                maxLines = 1,
            )
        } else {
            for (jd in listOf(events.riseJd, events.setJd)) {
                Text(
                    clock(jd, zone),
                    modifier = Modifier.weight(1f),
                    fontSize = 12.sp,
                    color = color,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                )
            }
        }
    }
}

private fun clock(jd: Double?, zone: ZoneId): String {
    if (jd == null) return "--:--"
    return Instant.ofEpochMilli(epochMillisFromJulianDay(jd))
        .atZone(zone)
        .toLocalTime()
        .format(DateTimeFormatter.ofPattern("HH:mm"))
}
