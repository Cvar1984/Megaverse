package com.cvar1984.megaverse.presentation

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateMapOf

/**
 * One setting: where it is stored, what the menu calls it, and the values it steps
 * through. The first value is the default, which is off or held still for every one
 * of them: the sky is what the screen is for, and a still reference is easier to
 * read against than one that drifts.
 */
class SettingSpec(val key: String, val title: String, val choices: List<Int>)

/**
 * The choices the app keeps between runs.
 *
 * Each value is read from storage once and then held in Compose state, written
 * through as it changes, so the draw loop can ask for one every frame without going
 * near flash and a change in the settings screen redraws the sky behind it.
 *
 * Booleans are kept as 0/1 so that one spec table covers every setting.
 */
object Settings {
    /** The sky turns a full circle in 24 hours, so 15 degrees is one hour of it. */
    const val DEGREES_PER_HOUR = 15

    // Grid spacings run coarse to fine, wrapping, with Off in the ring. The Garmin
    // build stopped at 15 or 30 degrees on watches with under 128 KB for the whole
    // app; a Wear OS watch has no such ceiling, so every spacing is offered.
    private val GRID = listOf(0, 60, 45, 30, 15, 10)
    private val TOGGLE = listOf(0, 1)

    // Off, the bare line, then the line with the dates marked along it. Each path
    // carries its own ring: they run within five degrees of each other, so wanting
    // one on screen is no reason to want both.
    private val PATH = listOf(0, 1, 2)

    // Minutes between position refreshes, 0 for one fix and no more. A fix costs
    // battery, so the intervals are long: anywhere you can walk to inside half an
    // hour is far below what a wrist compass can resolve.
    private val LOCATION = listOf(0, 5, 15, 30, 60)

    /**
     * The two spacings come first because they are what is on the screen; what is
     * allowed to move follows.
     */
    val specs = listOf(
        SettingSpec("horizonGrid", "Horizon Grid", GRID),
        SettingSpec("eqGridStep", "Equatorial Grid", GRID),
        SettingSpec("constellations", "Constellations", TOGGLE),
        SettingSpec("ecliptic", "Sun Path", PATH),
        SettingSpec("moonPath", "Moon Path", PATH),
        SettingSpec("dynEquatorial", "Equatorial Motion", TOGGLE),
        SettingSpec("dynAzimuth", "Azimuth Motion", TOGGLE),
        SettingSpec("locationMinutes", "Update Location", LOCATION),
    )

    private var prefs: SharedPreferences? = null
    private val values = mutableStateMapOf<String, Int>()

    fun load(context: Context) {
        if (prefs != null) return
        val store = context.getSharedPreferences("sky", Context.MODE_PRIVATE)
        prefs = store
        for (spec in specs) {
            // Storage outlives any one version of the app, so anything no longer in
            // the list falls back to the default rather than being trusted.
            val stored = store.getInt(spec.key, spec.choices.first())
            values[spec.key] = if (stored in spec.choices) stored else spec.choices.first()
        }
    }

    operator fun get(spec: SettingSpec): Int = values[spec.key] ?: spec.choices.first()

    /**
     * Steps a setting to the next value in its list and round to the start again. A
     * short list is quicker to thumb through in place than to open a submenu for,
     * and it cannot go stale behind the menu that shows it.
     */
    fun cycle(spec: SettingSpec) {
        val next = spec.choices[(spec.choices.indexOf(this[spec]) + 1) % spec.choices.size]
        values[spec.key] = next
        prefs?.edit()?.putInt(spec.key, next)?.apply()
    }

    // Looked up by key rather than by position in the list, so that adding a
    // setting cannot quietly re-point every accessor below it at its neighbour.
    private val byKey = specs.associateBy { it.key }

    private operator fun get(key: String) = this[byKey.getValue(key)]

    val horizon: Int get() = this["horizonGrid"]
    val equatorial: Int get() = this["eqGridStep"]
    val constellations: Boolean get() = this["constellations"] != 0
    val dynEquatorial: Boolean get() = this["dynEquatorial"] != 0
    val dynAzimuth: Boolean get() = this["dynAzimuth"] != 0
    val locationMinutes: Int get() = this["locationMinutes"]

    /** 0 draws nothing, 1 the bare line, 2 the line with its dates marked. */
    val ecliptic: Int get() = this["ecliptic"]

    /** 0 draws nothing, 1 the bare line, 2 the line with its dates marked. */
    val moonPath: Int get() = this["moonPath"]

    /** What the menu shows under each item. */
    fun label(spec: SettingSpec): String {
        val v = this[spec]
        return when (spec.key) {
            "horizonGrid", "eqGridStep" -> gridLabel(v)
            "locationMinutes" -> if (v <= 0) "One fix only" else "Every $v min"
            "dynEquatorial" -> if (v != 0) "Turns with sky" else "Held still"
            "ecliptic", "moonPath" -> when (v) {
                0 -> "Off"
                1 -> "Line"
                else -> "Line + dates"
            }
            // Azimuth Motion has nothing but position updates to follow, since the
            // horizon frame has no clock in it. Says so when it is switched on with
            // nothing arriving, instead of reading as on and doing nothing.
            "dynAzimuth" -> when {
                v == 0 -> "Held still"
                locationMinutes <= 0 -> "On - no updates"
                else -> "Follows position"
            }
            else -> if (v != 0) "On" else "Off"
        }
    }

    /**
     * Spacings that come to a whole number of hours say so, since that is what a
     * grid stepped in 15s divides the sky into.
     */
    private fun gridLabel(step: Int): String {
        if (step <= 0) return "Off"
        val text = "$step deg"
        return if (step % DEGREES_PER_HOUR == 0) {
            "$text - ${step / DEGREES_PER_HOUR} h"
        } else {
            text
        }
    }
}
