package com.cvar1984.megaverse

import com.cvar1984.megaverse.presentation.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The settings ring and the labels under it.
 *
 * Selecting an item steps it to its next value in place rather than opening a list
 * to pick from, so what the menu shows is the only record of what is stored: a
 * label that goes stale is the whole setting going wrong.
 */
class SettingsTest {

    /** A singleton outlives a test, so every one of them starts from the defaults. */
    @Before
    fun reset() {
        for (spec in Settings.specs) {
            var guard = 0
            while (Settings[spec] != spec.choices.first() && guard++ < 10) Settings.cycle(spec)
        }
    }

    @Test
    fun everythingStartsOffAndHeldStill() {
        // The sky is what the screen is for, and a reference that stays put is
        // easier to read against than one that drifts.
        assertEquals(0, Settings.horizon)
        assertEquals(0, Settings.equatorial)
        assertEquals(false, Settings.constellations)
        assertEquals(0, Settings.ecliptic)
        assertEquals(0, Settings.moonPath)
        assertEquals(false, Settings.dynEquatorial)
        assertEquals(false, Settings.dynAzimuth)
        assertEquals(0, Settings.locationMinutes)
    }

    @Test
    fun cyclingRoundTheRingComesBackToWhereItStarted() {
        for (spec in Settings.specs) {
            val start = Settings[spec]
            val seen = mutableListOf<Int>()
            repeat(spec.choices.size) {
                Settings.cycle(spec)
                seen.add(Settings[spec])
            }
            assertEquals("${spec.key} should return to $start", start, Settings[spec])
            assertEquals("${spec.key} should visit every value", spec.choices.toSet(), seen.toSet())
        }
    }

    @Test
    fun offIsInEachGridsRing() {
        // Either grid can be put away without leaving the item it is set on.
        val horizon = Settings.specs.first { it.key == "horizonGrid" }
        assertTrue(0 in horizon.choices)
        Settings.cycle(horizon)
        assertTrue("first step should switch it on", Settings.horizon > 0)
    }

    @Test
    fun gridSpacingsRunCoarseToFine() {
        // Finer spacings cost frame time, so the ring walks towards them rather
        // than away, and a user who overshoots wraps back round to Off.
        val horizon = Settings.specs.first { it.key == "horizonGrid" }
        val spacings = horizon.choices.drop(1)
        assertEquals(spacings.sortedDescending(), spacings)
    }

    @Test
    fun spacingsWorthAWholeNumberOfHoursSaySo() {
        // The sky turns 360 degrees in 24 hours, so a grid stepped in 15s divides
        // it into hour-wide cells.
        val horizon = Settings.specs.first { it.key == "horizonGrid" }
        val labels = buildList {
            repeat(horizon.choices.size) {
                add(Settings.label(horizon))
                Settings.cycle(horizon)
            }
        }
        assertTrue("Off" in labels)
        assertTrue("60 deg - 4 h" in labels)
        assertTrue("30 deg - 2 h" in labels)
        assertTrue("45 deg - 3 h" in labels)
        assertTrue("15 deg - 1 h" in labels)
        // Ten degrees is the one spacing that is not a whole number of hours, so it
        // says only the angle.
        assertTrue("10 deg" in labels)
    }

    @Test
    fun azimuthMotionSaysWhenItHasNothingToFollow() {
        // It has nothing but position updates to follow, since the horizon frame
        // has no clock in it. Switched on with the fixes off, it must say so rather
        // than read as on and do nothing.
        val azimuth = Settings.specs.first { it.key == "dynAzimuth" }
        val location = Settings.specs.first { it.key == "locationMinutes" }

        assertEquals("Held still", Settings.label(azimuth))

        Settings.cycle(azimuth)
        assertEquals("On - no updates", Settings.label(azimuth))

        Settings.cycle(location)
        assertEquals("Follows position", Settings.label(azimuth))
        assertEquals("Every 5 min", Settings.label(location))
    }

    @Test
    fun oneFixOnlyIsTheDefaultForLocation() {
        // A fix costs battery, so nothing is asked for until it is asked for.
        val location = Settings.specs.first { it.key == "locationMinutes" }
        assertEquals("One fix only", Settings.label(location))
    }

    @Test
    fun togglesReadOnAndOff() {
        val constellations = Settings.specs.first { it.key == "constellations" }
        assertEquals("Off", Settings.label(constellations))
        Settings.cycle(constellations)
        assertEquals("On", Settings.label(constellations))

        val equatorial = Settings.specs.first { it.key == "dynEquatorial" }
        assertEquals("Held still", Settings.label(equatorial))
        Settings.cycle(equatorial)
        assertEquals("Turns with sky", Settings.label(equatorial))
    }

    @Test
    fun everySettingHasATitleAndAStorageKey() {
        assertEquals(8, Settings.specs.size)
        assertEquals(Settings.specs.size, Settings.specs.map { it.key }.toSet().size)
        for (spec in Settings.specs) {
            assertTrue(spec.key.isNotBlank())
            assertTrue(spec.title.isNotBlank())
            assertTrue("${spec.key} needs something to step through", spec.choices.size >= 2)
        }
    }

    @Test
    fun eachPathSaysWhetherItIsCarryingItsDates() {
        // Three states, not two: a line you cannot read dates off is a different
        // thing from one you can, and the label is the only place that distinction
        // is visible before you leave the menu.
        for (key in listOf("ecliptic", "moonPath")) {
            val spec = Settings.specs.first { it.key == key }
            val labels = buildList {
                repeat(spec.choices.size) {
                    add(Settings.label(spec))
                    Settings.cycle(spec)
                }
            }
            assertEquals("$key should read Off, Line, Line + dates in that order",
                listOf("Off", "Line", "Line + dates"), labels)
        }
    }

    @Test
    fun theTogglesReadBackThroughTheirOwnAccessors() {
        // The labels are what the menu shows; these are what the sky screen reads.
        // They are separate code paths and only one of them was being checked.
        val cases = listOf(
            "constellations" to { Settings.constellations },
            "dynEquatorial" to { Settings.dynEquatorial },
            "dynAzimuth" to { Settings.dynAzimuth },
        )
        for ((key, read) in cases) {
            val spec = Settings.specs.first { it.key == key }
            assertEquals("$key starts off", false, read())
            Settings.cycle(spec)
            assertEquals("$key should read on after one step", true, read())
            Settings.cycle(spec)
            assertEquals("$key should come back off", false, read())
        }
    }

    @Test
    fun thePathSettingsAreReadBackAsTheirStep() {
        // Unlike the toggles these are three-valued, so the accessor hands back the
        // step rather than a boolean and the draw code switches on it.
        val ecliptic = Settings.specs.first { it.key == "ecliptic" }
        assertEquals(0, Settings.ecliptic)
        Settings.cycle(ecliptic)
        assertEquals(1, Settings.ecliptic)
        Settings.cycle(ecliptic)
        assertEquals(2, Settings.ecliptic)

        val moon = Settings.specs.first { it.key == "moonPath" }
        assertEquals(0, Settings.moonPath)
        Settings.cycle(moon)
        assertEquals(1, Settings.moonPath)
    }

    @Test
    fun aStoredValueThisBuildNoLongerOffersFallsBack() {
        // Someone who set the grid to 10 degrees on an older build, or who has a
        // key left behind from a ring that has since changed, must not end up with
        // the app trying to draw a spacing it does not have.
        for (spec in Settings.specs) {
            for (good in spec.choices) {
                assertEquals("$spec.key should keep $good", good, Settings.sanitise(spec, good))
            }
            for (junk in listOf(-1, 7, 99, Int.MAX_VALUE, Int.MIN_VALUE)) {
                assertEquals(
                    "${spec.key} should fall back on $junk",
                    spec.choices.first(),
                    Settings.sanitise(spec, junk),
                )
            }
        }
    }
}
